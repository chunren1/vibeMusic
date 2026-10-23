package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.Result;
import com.vibemusic.common.utils.AnonymousIdentityUtils;
import com.vibemusic.config.RestTemplateConfig;
import com.vibemusic.service.AiCallBudget;
import com.vibemusic.service.AiToolCallGuard;
import com.vibemusic.service.AiToolService;
import com.vibemusic.service.ChatMemoryService;
import com.vibemusic.service.RateLimitService;
import com.vibemusic.service.UserService;
import io.netty.channel.ChannelOption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * AI 音乐助手 — Function Calling + 多轮对话记忆 + 真正 SSE 流式
 * <p>
 * 三大升级：
 * 1. Function Calling：AI 自主决定调用 search_songs / get_user_history 工具
 * 2. 多轮对话记忆：基于 Redis 存储会话历史，支持上下文关联
 * 3. 真正 SSE 流式：WebClient 流式读取 LLM 响应，逐 token 推送
 * 4. 总预算：一次请求一份 deadline（默认 45s，{@code ai.total-budget-ms} 可覆盖），
 *    LLM 调用的 socket 超时 = min(单次上限 60s, 剩余预算)，工具调用同样受约束；
 *    预算耗尽即返回既有友好降级文案，不再发起新调用（避免 3×60s=180s 占着线程）
 * <p>
 * 接口：
 * - POST /chat   → 同步对话（含 Function Calling + 多轮记忆）
 * - POST /stream → SSE 真正流式（逐 token 输出）
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "AI 助手", description = "音乐聊天 + 智能推荐 + Function Calling")
public class AssistantController {

    private final RestTemplate restTemplate;
    private final WebClient webClient;
    private final AiToolService aiToolService;
    private final ChatMemoryService chatMemoryService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String aiModel;
    private final String thinkingMode;
    private final String apiUrl;
    /** 单次请求的 AI 总预算（ms）：Function Calling 的全部 LLM 调用共享这一份。 */
    private final long aiTotalBudgetMs;
    /** 流式首包（响应头）超时（ms），必须小于总预算。 */
    private final long streamFirstByteTimeoutMs;
    private static final int AI_RATE_LIMIT = 10;
    /** 单次请求默认总预算 45s：把 3×60s 的最坏耗时收敛到 45s。 */
    private static final long DEFAULT_AI_TOTAL_BUDGET_MS = 45_000L;
    /** 流式首包默认 20s：上游不吐响应头时不让连接无限挂着。 */
    private static final long DEFAULT_STREAM_FIRST_BYTE_TIMEOUT_MS = 20_000L;
    /** SseEmitter 自身超时在总预算之外预留的收尾余量。 */
    private static final long SSE_COMPLETE_GRACE_MS = 5_000L;
    /** Function Calling 最多几轮工具调用（每轮 = 1 次 LLM 调用）。 */
    private static final int MAX_TOOL_ROUNDS = 2;
    /** 预算耗尽/调用失败时复用的友好文案（对前端信封不变）。 */
    private static final String AI_FALLBACK_REPLY = "让我想想...请稍后再试";
    /** 流式失败且一个字都没吐出来时的兜底文案（沿用既有文案）。 */
    private static final String STREAM_FALLBACK_TEXT = "网络不太好，再问我一次吧～";
    /**
     * 按 IP 的粗粒度兜底限流（防伪造 deviceId 换桶无限刷）。
     * 放宽到远高于单人配额，正常 NAT 出口用户不会触达。
     */
    private static final int AI_IP_RATE_LIMIT = 30;
    /** 172.16.0.0/12（docker 网段） */
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    /** 示例/占位 Key：一律视为未配置，绝不外呼（change-me 必然 401，还要白等超时） */
    private static final Set<String> PLACEHOLDER_API_KEYS = Set.of("change-me", "changeme");

    @Autowired
    public AssistantController(@Qualifier("aiRestTemplate") RestTemplate restTemplate,
                               WebClient.Builder webClientBuilder,
                               AiToolService aiToolService,
                               ChatMemoryService chatMemoryService,
                               RateLimitService rateLimitService,
                               ObjectMapper objectMapper,
                               @Value("${ai.api-key:}") String apiKey,
                               @Value("${ai.model:deepseek-flash}") String aiModel,
                               @Value("${ai.thinking-mode:disabled}") String thinkingMode,
                               @Value("${ai.api-url:https://api.deepseek.com/chat/completions}") String apiUrl,
                               @Value("${ai.total-budget-ms:" + DEFAULT_AI_TOTAL_BUDGET_MS + "}") long aiTotalBudgetMs,
                               @Value("${ai.stream-first-byte-timeout-ms:" + DEFAULT_STREAM_FIRST_BYTE_TIMEOUT_MS + "}") long streamFirstByteTimeoutMs) {
        this.restTemplate = restTemplate;
        this.aiToolService = aiToolService;
        this.chatMemoryService = chatMemoryService;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.aiModel = aiModel;
        this.thinkingMode = thinkingMode;
        this.apiUrl = apiUrl;
        this.aiTotalBudgetMs = Math.max(1L, aiTotalBudgetMs);
        this.streamFirstByteTimeoutMs = Math.max(1L, streamFirstByteTimeoutMs);
        // 流式路径用独立 connector：responseTimeout 只管「首包/响应头」，
        // 总时长上限由 Flux 侧按同一预算截断（见 streamChat）。
        HttpClient nettyClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) RestTemplateConfig.CONNECT_TIMEOUT.toMillis())
                .responseTimeout(Duration.ofMillis(this.streamFirstByteTimeoutMs));
        this.webClient = webClientBuilder
                .baseUrl(apiUrl)
                .clientConnector(new ReactorClientHttpConnector(nettyClient))
                .build();
    }

    /** 便捷构造：全部按默认预算（单测/本地手搓实例用）。 */
    public AssistantController(RestTemplate restTemplate,
                               WebClient.Builder webClientBuilder,
                               AiToolService aiToolService,
                               ChatMemoryService chatMemoryService,
                               RateLimitService rateLimitService,
                               ObjectMapper objectMapper,
                               String apiKey,
                               String aiModel,
                               String thinkingMode,
                               String apiUrl) {
        this(restTemplate, webClientBuilder, aiToolService, chatMemoryService, rateLimitService, objectMapper,
                apiKey, aiModel, thinkingMode, apiUrl,
                DEFAULT_AI_TOTAL_BUDGET_MS, DEFAULT_STREAM_FIRST_BYTE_TIMEOUT_MS);
    }

    // ========================== /chat 同步对话 ==========================

    @PostMapping("/chat")
    @Operation(summary = "AI 音乐聊天（Function Calling + 多轮记忆）")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, Object> body,
                                            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
                                            @RequestParam(value = "deviceId", required = false) String paramDeviceId,
                                            HttpServletRequest request) {
        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        if (userMessage != null && userMessage.length() > 2000)
            return Result.error("消息过长，请限制在 2000 字以内");

        if (!isApiKeyConfigured(apiKey))
            return Result.ok(Map.of("reply", "AI 助手暂未配置 API Key，请设置环境变量 AI_API_KEY", "model", aiModel));

        Long userId = UserService.getCurrentUserId();
        // 匿名用户按设备标识隔离限流桶与对话记忆，避免陌生人互相污染/耗尽共享配额
        String anonymousId = userId != null ? null
                : AnonymousIdentityUtils.resolveAnonymousId(paramDeviceId, headerDeviceId);
        String rateKey = "assistant:" + (userId != null ? "user:" + userId : "anon:" + anonymousId);
        if (!rateLimitService.tryAcquire(rateKey, AI_RATE_LIMIT, java.time.Duration.ofMinutes(1)))
            return Result.error(429, "请求太频繁，请稍后再试（每分钟最多 " + AI_RATE_LIMIT + " 次）");
        if (!rateLimitService.tryAcquire(ipRateKey(request), AI_IP_RATE_LIMIT, java.time.Duration.ofMinutes(1)))
            return Result.error(429, "请求太频繁，请稍后再试");

        // 一次请求只开一份总预算：LLM 调用 + 工具调用共享，耗尽后一律降级
        AiCallBudget budget = AiCallBudget.start(
                Duration.ofMillis(aiTotalBudgetMs), RestTemplateConfig.AI_RESPONSE_TIMEOUT);
        List<Map<String, Object>> toolResults = new ArrayList<>();
        try {
            // 构建带历史记忆的消息列表
            List<Map<String, Object>> messages = buildMessagesWithHistory(userId, anonymousId, userMessage, songContext);

            // 第一轮：带 Function Calling 调用 LLM
            if (!budget.canCall()) {
                log.warn("[AI 预算] 进入对话时预算已耗尽，直接降级: totalMs={}", budget.totalMillis());
                return Result.ok(degradedChatResult(toolResults));
            }
            Map<String, Object> llmResponse = callLLMWithTools(messages, budget);

            // 处理 Function Calling（最多 2 轮工具调用）
            int toolRounds = 0;
            while (AssistantPayloads.hasToolCalls(llmResponse) && toolRounds < MAX_TOOL_ROUNDS) {
                toolRounds++;
                List<Map<String, Object>> toolCalls = AssistantPayloads.extractToolCalls(llmResponse);

                // 把 assistant 的 tool_calls 消息加入历史
                messages.add(AssistantPayloads.extractAssistantMessage(llmResponse));

                // 执行每个工具调用（受同一 deadline 约束，单次工具不吃掉整份预算）
                for (Map<String, Object> toolCall : toolCalls) {
                    String functionName = (String) ((Map<?, ?>) toolCall.get("function")).get("name");
                    String arguments = (String) ((Map<?, ?>) toolCall.get("function")).get("arguments");
                    String toolCallId = (String) toolCall.get("id");

                    log.info("[Function Calling] 执行工具: {} args={} remainingMs={}",
                            functionName, arguments, budget.remainingMillis());
                    String result = AiToolCallGuard.executeWithin(aiToolService, functionName, arguments, budget);
                    toolResults.add(Map.of("name", functionName, "result", result));

                    // 把工具结果以 tool 角色回传给 LLM
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", toolCallId,
                        "content", result
                    ));
                }

                // 预算耗尽：不再发起新一轮 LLM 调用，直接给友好降级（信封不变）
                if (!budget.canCall()) {
                    log.warn("[AI 预算] 工具轮次后预算耗尽，降级返回: rounds={}, toolResults={}, totalMs={}",
                            toolRounds, toolResults.size(), budget.totalMillis());
                    return Result.ok(degradedChatResult(toolResults));
                }

                // 第二轮：带上工具结果再次调用 LLM 生成最终回复
                llmResponse = callLLMWithTools(messages, budget);
            }

            String reply = AssistantPayloads.extractContent(llmResponse);
            if (reply == null || reply.isBlank()) reply = AI_FALLBACK_REPLY;

            // 保存对话到记忆
            chatMemoryService.appendMessage(userId, "user", userMessage, anonymousId);
            chatMemoryService.appendMessage(userId, "assistant", reply, anonymousId);

            // 从工具结果中提取歌曲列表
            List<Map<String, Object>> songs = extractSongsFromToolResults(toolResults);

            Map<String, Object> result = new HashMap<>();
            result.put("reply", reply);
            result.put("model", aiModel);
            result.put("songs", songs);
            result.put("toolCalls", toolResults.size());
            return Result.ok(result);
        } catch (Exception e) {
            log.error("AI 对话失败", e);
            return Result.ok(Map.of("reply", AI_FALLBACK_REPLY, "model", aiModel, "songs", List.of()));
        }
    }

    /**
     * 降级结果：复用既有友好文案，字段与正常回复一致（reply/model/songs/toolCalls），
     * 已拿到的工具歌曲仍然返回，前端无需改。
     */
    private Map<String, Object> degradedChatResult(List<Map<String, Object>> toolResults) {
        Map<String, Object> result = new HashMap<>();
        result.put("reply", AI_FALLBACK_REPLY);
        result.put("model", aiModel);
        result.put("songs", extractSongsFromToolResults(toolResults));
        result.put("toolCalls", toolResults.size());
        return result;
    }

    // ========================== /stream SSE 真正流式 ==========================

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 流式聊天（WebClient 真正流式 + 逐 token 输出）")
    public SseEmitter streamChat(@RequestBody Map<String, Object> body,
                                 @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
                                 @RequestParam(value = "deviceId", required = false) String paramDeviceId,
                                 HttpServletRequest request) {
        // 一次请求一份总预算：流式首包超时与总时长上限都从这份预算里取
        AiCallBudget budget = AiCallBudget.start(
                Duration.ofMillis(aiTotalBudgetMs), RestTemplateConfig.AI_RESPONSE_TIMEOUT);
        // SseEmitter 自身超时略大于总预算：先让「预算超时」走发 error 事件 + complete 的优雅路径
        SseEmitter emitter = new SseEmitter(budget.remainingMillis() + SSE_COMPLETE_GRACE_MS);
        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        if (userMessage != null && userMessage.length() > 2000) {
            sendEvent(emitter, "error", Map.of("message", "消息过长，请限制在 2000 字以内"));
            emitter.complete();
            return emitter;
        }

        if (!isApiKeyConfigured(apiKey)) {
            sendEvent(emitter, "error", Map.of("message", "AI 助手暂未配置 API Key"));
            emitter.complete();
            return emitter;
        }

        Long userId = UserService.getCurrentUserId();
        final String anonymousId = userId != null ? null
                : AnonymousIdentityUtils.resolveAnonymousId(paramDeviceId, headerDeviceId);
        String rateKey = "assistant:" + (userId != null ? "user:" + userId : "anon:" + anonymousId);
        if (!rateLimitService.tryAcquire(rateKey, AI_RATE_LIMIT, java.time.Duration.ofMinutes(1))) {
            sendEvent(emitter, "error", Map.of("message", "请求太频繁，请稍后再试"));
            emitter.complete();
            return emitter;
        }
        if (!rateLimitService.tryAcquire(ipRateKey(request), AI_IP_RATE_LIMIT, java.time.Duration.ofMinutes(1))) {
            sendEvent(emitter, "error", Map.of("message", "请求太频繁，请稍后再试"));
            emitter.complete();
            return emitter;
        }

        List<Map<String, Object>> messages = buildMessagesWithHistory(userId, anonymousId, userMessage, songContext);

        // 使用 WebClient 流式消费 LLM SSE 响应
        final Disposable[] disposableHolder = new Disposable[1];
        StringBuilder fullReply = new StringBuilder();
        // [DONE] 分支与 onComplete 都会到达「流结束」语义：守卫只允许落库一次，
        // 否则每轮对话在会话记忆里存两份（历史翻倍、token 成本翻倍）。
        final AtomicBoolean persisted = new AtomicBoolean(false);
        final Runnable persistOnce = () -> {
            if (!markPersistedOnce(persisted, fullReply.length())) return;
            chatMemoryService.appendMessage(userId, "user", userMessage, anonymousId);
            chatMemoryService.appendMessage(userId, "assistant", fullReply.toString(), anonymousId);
        };
        // 终态只允许一次：done / error 事件格式与前端契约保持不变
        final AtomicBoolean terminal = new AtomicBoolean(false);
        Duration firstByteTimeout = AiCallBudget.shorter(
                Duration.ofMillis(streamFirstByteTimeoutMs), Duration.ofMillis(budget.remainingMillis()));
        Duration streamBudget = Duration.ofMillis(budget.remainingMillis());
        log.info("[AI 预算] 流式对话开始: totalMs={}, firstByteTimeoutMs={}, streamBudgetMs={}",
                budget.totalMillis(), firstByteTimeout.toMillis(), streamBudget.toMillis());

        disposableHolder[0] = webClient.post()
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(buildStreamRequestBody(messages))
            .retrieve()
            .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
            // 首包/响应头超时（reactor-netty responseTimeout 之外再兜一层，并覆盖中途静默）
            .timeout(firstByteTimeout)
            // 总时长上限：上游「细水长流」式滴 token 也不能突破同一份预算
            .takeUntilOther(Mono.delay(streamBudget).then(Mono.error(
                    new TimeoutException("AI 流式总预算耗尽: " + streamBudget.toMillis() + "ms"))))
            .subscribe(
                sse -> {
                    String data = sse.data();
                    if (data == null || "[DONE]".equals(data.trim())) {
                        // 流结束
                        persistOnce.run();
                        finishStream(emitter, terminal, Map.of("full", fullReply.toString(), "model", aiModel));
                        return;
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                            if (delta != null && delta.get("content") != null) {
                                String token = (String) delta.get("content");
                                fullReply.append(token);
                                sendEvent(emitter, "token", Map.of("content", token));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("SSE chunk 解析跳过: {}", data);
                    }
                },
                error -> {
                    // 超时（首包/总预算）与上游异常都走这里：发 error 事件后 complete，不抛未捕获异常
                    log.warn("[AI 预算] SSE 流式中止（超时/上游异常）: {}", error.toString());
                    failStream(emitter, terminal, fullReply);
                },
                () -> {
                    persistOnce.run();
                    finishStream(emitter, terminal, Map.of("full", fullReply.toString(), "model", aiModel));
                }
            );

        emitter.onCompletion(() -> disposeQuietly(disposableHolder));
        emitter.onTimeout(() -> {
            log.warn("[AI 预算] SSE 连接兜底超时，释放上游订阅并收尾");
            disposeQuietly(disposableHolder);
            completeQuietly(emitter);
        });
        emitter.onError(e -> disposeQuietly(disposableHolder));

        return emitter;
    }

    /** 终态只落一次：done 事件 + complete（[DONE] 与 onComplete 双触发不重复发事件）。 */
    private void finishStream(SseEmitter emitter, AtomicBoolean terminal, Map<String, Object> payload) {
        if (!terminal.compareAndSet(false, true)) return;
        sendEvent(emitter, "done", payload);
        completeQuietly(emitter);
    }

    /** 失败/超时终态：先给兜底文案再发 error 事件，最后 complete（信封/事件格式不变）。 */
    private void failStream(SseEmitter emitter, AtomicBoolean terminal, StringBuilder fullReply) {
        if (!terminal.compareAndSet(false, true)) return;
        if (fullReply.isEmpty()) {
            sendEvent(emitter, "token", Map.of("content", STREAM_FALLBACK_TEXT));
        }
        sendEvent(emitter, "error", Map.of("message", "AI 服务暂时不可用"));
        completeQuietly(emitter);
    }

    /** 幂等 complete：客户端已断开/已结束时容器可能抛异常，只记 debug。 */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 幂等收尾忽略: {}", e.getMessage());
        }
    }

    /** 释放上游订阅（complete/timeout/error/客户端断开都必须解绑，防订阅泄漏）。 */
    private void disposeQuietly(Disposable[] holder) {
        Disposable disposable = holder[0];
        if (disposable == null || disposable.isDisposed()) return;
        disposable.dispose();
    }

    /**
     * Key 是否可用于外呼：空白 或 占位示例值（change-me / changeme）都视为未配置。
     * <p>占位值不拦截的话，请求会真的打到上游并必然 401——用户看到的是「AI 服务暂时不可用」，
     * 而真实原因是没配 Key，徒增一次外呼与超时。</p>
     */
    static boolean isApiKeyConfigured(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return false;
        return !PLACEHOLDER_API_KEYS.contains(apiKey.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 纯函数：流式会话落库守卫。[DONE] 分支与 onComplete 都会触发"流结束"，
     * 只有「回复非空」且「首次进入」才落库——防止每轮对话在会话记忆里存两份。
     */
    static boolean markPersistedOnce(AtomicBoolean persisted, int replyLength) {
        if (replyLength <= 0) return false;
        return persisted.compareAndSet(false, true);
    }

    /**
     * 粗粒度按 IP 的匿名兜底限流键来源：仅当直连对端是内网（nginx 容器 / 回环）时
     * 才信任 {@code X-Real-IP}（nginx 用 {@code $remote_addr} 覆写，客户端无法伪造），
     * 否则直接用 TCP 对端地址——防止伪造头绕过按 IP 的限流。
     */
    static String clientIp(String remoteAddr, String realIpHeader) {
        if (remoteAddr == null) return "unknown";
        if (isPrivatePeer(remoteAddr) && realIpHeader != null && !realIpHeader.isBlank()) {
            String cleaned = realIpHeader.strip().replaceAll("[^0-9A-Fa-f:.]", "");
            if (!cleaned.isEmpty() && cleaned.length() <= 45) return cleaned;
        }
        return remoteAddr;
    }

    /** 纯函数：是否内网/回环对端（10/8、172.16-31、192.168/16、127.0.0.1、::1）。 */
    static boolean isPrivatePeer(String ip) {
        if (ip == null) return false;
        return ip.equals("127.0.0.1") || ip.equals("::1")
                || ip.startsWith("10.") || ip.startsWith("192.168.")
                || PRIVATE_172.matcher(ip).matches();
    }

    /** 按 IP 兜底限流的 Redis 键（键内只留 IP 允许字符）。 */
    private static String ipRateKey(HttpServletRequest request) {
        return "assistant:ip:" + clientIp(request.getRemoteAddr(), request.getHeader("X-Real-IP"));
    }

    // ========================== 清除对话记忆 ==========================

    @DeleteMapping("/history")
    @Operation(summary = "清除 AI 对话历史")
    public Result<Void> clearHistory(
            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
            @RequestParam(value = "deviceId", required = false) String paramDeviceId) {
        Long userId = UserService.getCurrentUserId();
        String anonymousId = userId != null ? null
                : AnonymousIdentityUtils.resolveAnonymousId(paramDeviceId, headerDeviceId);
        chatMemoryService.clearHistory(userId, anonymousId);
        return Result.ok(null);
    }

    // ========================== 内部方法 ==========================

    /**
     * 构建带历史记忆的消息列表
     */
    private List<Map<String, Object>> buildMessagesWithHistory(Long userId, String anonymousId, String userMessage, String songContext) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(songContext)));

        // 加载对话历史
        List<Map<String, String>> history = chatMemoryService.getHistory(userId, anonymousId);
        if (history == null) history = List.of();
        for (Map<String, String> msg : history) {
            messages.add(new HashMap<>(msg));
        }

        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    /**
     * 调用 LLM（带 Function Calling 工具定义）。
     * <p>
     * 本次调用的 socket 读超时 = {@code min(单次上限 60s, 剩余预算)}：
     * 通过请求级 {@code RequestConfig} 覆盖（共享同一个连接池的 HttpClient），
     * 不改全局 AI 客户端配置，也不影响其它调用方。
     *
     * @throws IllegalStateException 预算已耗尽（调用方应先判 {@code budget.canCall()}）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callLLMWithTools(List<Map<String, Object>> messages, AiCallBudget budget) {
        Duration timeout = budget.timeoutForNextCall();
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", aiModel);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 800);
        requestBody.put("temperature", 0.7);
        requestBody.put("tools", aiToolService.getToolDefinitions());
        requestBody.put("tool_choice", "auto");
        requestBody.put("thinking", Map.of("type", thinkingMode));

        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            if (timeout.toMillis() < 5_000L) {
                log.warn("[AI 预算] 剩余预算不足，收紧本次 LLM 超时: timeoutMs={}, remainingMs={}",
                        timeout.toMillis(), budget.remainingMillis());
            } else {
                log.debug("[AI 预算] LLM 调用: timeoutMs={}, remainingMs={}", timeout.toMillis(), budget.remainingMillis());
            }

            org.springframework.http.ResponseEntity<Map> response = withReadTimeout(restTemplate, timeout).postForEntity(
                apiUrl, new org.springframework.http.HttpEntity<>(requestBody, headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) return Map.of();

            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) return Map.of();

            return choices.get(0);
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            throw new RuntimeException("AI 调用失败", e);
        }
    }

    /**
     * 用「本次调用可用时长」派生一个一次性 RestTemplate：底层复用同一连接池的 HttpClient，
     * 只在请求级 {@code RequestConfig} 上覆盖响应超时（Spring 会把 readTimeout 合并进请求级配置）。
     * <p>
     * 拿不到底层 Apache 客户端时（如单测里的 mock）原样返回，行为与旧版一致。
     */
    static RestTemplate withReadTimeout(RestTemplate base, Duration timeout) {
        if (!(base.getRequestFactory() instanceof HttpComponentsClientHttpRequestFactory factory)) return base;
        if (!(factory.getHttpClient() instanceof CloseableHttpClient client)) return base;
        HttpComponentsClientHttpRequestFactory perCall = new HttpComponentsClientHttpRequestFactory(client);
        perCall.setReadTimeout(timeout);
        perCall.setConnectionRequestTimeout(
                AiCallBudget.shorter(RestTemplateConfig.CONNECTION_REQUEST_TIMEOUT, timeout));
        return new RestTemplate(perCall);
    }

    /**
     * 构建流式请求体
     */
    private Map<String, Object> buildStreamRequestBody(List<Map<String, Object>> messages) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", aiModel);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 800);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);
        requestBody.put("thinking", Map.of("type", thinkingMode));
        return requestBody;
    }

    /**
     * 从工具调用结果中提取歌曲列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSongsFromToolResults(List<Map<String, Object>> toolResults) {
        List<Map<String, Object>> songs = new ArrayList<>();
        for (Map<String, Object> tr : toolResults) {
            if ("search_songs".equals(tr.get("name"))) {
                try {
                    Map<String, Object> result = objectMapper.readValue((String) tr.get("result"), Map.class);
                    List<Map<String, Object>> songList = (List<Map<String, Object>>) result.get("songs");
                    if (songList != null) {
                        for (Map<String, Object> s : songList) {
                            Map<String, Object> card = new HashMap<>();
                            card.put("sourceId", s.get("sourceId"));
                            card.put("name", s.get("name"));
                            card.put("artist", s.getOrDefault("artist", ""));
                            card.put("platform", s.getOrDefault("platform", ""));
                            songs.add(card);
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析歌曲工具结果失败: {}", e.getMessage());
                }
            }
        }
        return songs;
    }

    private void sendEvent(SseEmitter emitter, String type, Object data) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("type", type);
            event.putAll((Map<String, Object>) data);
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.warn("SSE 发送事件失败: type={}", type, e);
        }
    }

    private String buildSystemPrompt(String context) {
        String prompt = """
            你是 vibe 音乐精灵，一个温暖的音乐推荐助手。
            你可以调用工具来搜索歌曲和查看用户播放历史，基于真实搜索结果给用户推荐。

            规则：
            1. 自然、有共情力，像朋友聊天，控制在 150 字以内
            2. 如果用户让推荐歌曲，调用 search_songs 工具搜索，基于结果推荐并简要说明推荐原因
            3. 如果用户问"我最近听了什么"或"根据我的口味推荐"，调用 get_user_history 工具
            4. 熟悉华语流行、民谣、说唱、摇滚、R&B、电子、纯音乐等风格
            5. 根据情绪推荐：开心→轻快节奏 伤感→治愈抒情 运动→燃曲 学习→纯音乐
            6. 不要输出 Markdown、JSON 或代码块
            7. 问"你是谁" → 说你是 vibe 音乐精灵""";
        if (context != null && !context.isEmpty()) {
            prompt += "\n当前用户正在听：" + context;
        }
        return prompt;
    }
}
