package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.Result;
import com.vibemusic.common.utils.AnonymousIdentityUtils;
import com.vibemusic.config.RestTemplateConfig;
import com.vibemusic.dto.AssistantChatRequest;
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
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>
 * 拆分（P1-8）：本类只保留端点与同步编排——/chat 的 Function Calling 循环、系统提示、
 * 消息组装、清除历史。上游调用见 {@link AssistantClient}，SSE 编排见
 * {@link AssistantStreamOrchestrator}，身份/准入判定见 {@link AssistantRequestIdentity}。
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "AI 助手", description = "音乐聊天 + 智能推荐 + Function Calling")
public class AssistantController {

    private final AiToolService aiToolService;
    private final ChatMemoryService chatMemoryService;
    private final RateLimitService rateLimitService;
    private final String apiKey;
    private final String aiModel;
    /** 单次请求的 AI 总预算（ms）：Function Calling 的全部 LLM 调用共享这一份。 */
    private final long aiTotalBudgetMs;
    /** 上游 LLM 调用与工具结果解析。 */
    private final AssistantClient assistantClient;
    /** SSE 流式编排（准入 → 订阅 → 逐 token → 终态）。 */
    private final AssistantStreamOrchestrator streamOrchestrator;

    /** 同步入口复用 SSE 入口的同一套限流口径（单用户 10 次/分钟）。 */
    private static final int AI_RATE_LIMIT = AssistantRequestIdentity.AI_RATE_LIMIT;
    /** 单次请求默认总预算 45s：把 3×60s 的最坏耗时收敛到 45s。 */
    private static final long DEFAULT_AI_TOTAL_BUDGET_MS = 45_000L;
    /** 流式首包默认 20s：上游不吐响应头时不让连接无限挂着。 */
    private static final long DEFAULT_STREAM_FIRST_BYTE_TIMEOUT_MS = 20_000L;
    /** Function Calling 最多几轮工具调用（每轮 = 1 次 LLM 调用）。 */
    private static final int MAX_TOOL_ROUNDS = 2;
    /** 预算耗尽/调用失败时复用的友好文案（对前端信封不变）。 */
    private static final String AI_FALLBACK_REPLY = "让我想想...请稍后再试";

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
        this.aiToolService = aiToolService;
        this.chatMemoryService = chatMemoryService;
        this.rateLimitService = rateLimitService;
        this.apiKey = apiKey;
        this.aiModel = aiModel;
        this.aiTotalBudgetMs = Math.max(1L, aiTotalBudgetMs);
        // 流式路径用独立 connector：responseTimeout 只管「首包/响应头」，
        // 总时长上限由 Flux 侧按同一预算截断（见 AssistantStreamOrchestrator#stream）。
        HttpClient nettyClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) RestTemplateConfig.CONNECT_TIMEOUT.toMillis())
                .responseTimeout(Duration.ofMillis(Math.max(1L, streamFirstByteTimeoutMs)));
        WebClient webClient = webClientBuilder
                .baseUrl(apiUrl)
                .clientConnector(new ReactorClientHttpConnector(nettyClient))
                .build();

        this.assistantClient = new AssistantClient(
                restTemplate, objectMapper, aiToolService, apiUrl, apiKey, aiModel, thinkingMode);
        this.streamOrchestrator = new AssistantStreamOrchestrator(
                webClient, objectMapper, chatMemoryService, rateLimitService,
                this::buildMessagesWithHistory,
                apiKey, aiModel, thinkingMode,
                this.aiTotalBudgetMs, Math.max(1L, streamFirstByteTimeoutMs));
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
    public Result<Map<String, Object>> chat(@Valid @RequestBody AssistantChatRequest req,
                                            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
                                            @RequestParam(value = "deviceId", required = false) String paramDeviceId,
                                            HttpServletRequest request) {
        String userMessage = req.message() != null ? req.message() : "推荐一首歌给我";
        String songContext = req.context() != null ? req.context() : "";

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
        if (!rateLimitService.tryAcquire(ipRateKey(request), AssistantRequestIdentity.AI_IP_RATE_LIMIT,
                java.time.Duration.ofMinutes(1)))
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
            Map<String, Object> llmResponse = assistantClient.callLLMWithTools(messages, budget);

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
                llmResponse = assistantClient.callLLMWithTools(messages, budget);
            }

            String reply = AssistantPayloads.extractContent(llmResponse);
            if (reply == null || reply.isBlank()) reply = AI_FALLBACK_REPLY;

            // 保存对话到记忆
            chatMemoryService.appendMessage(userId, "user", userMessage, anonymousId);
            chatMemoryService.appendMessage(userId, "assistant", reply, anonymousId);

            // 从工具结果中提取歌曲列表
            List<Map<String, Object>> songs = assistantClient.extractSongsFromToolResults(toolResults);

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
        result.put("songs", assistantClient.extractSongsFromToolResults(toolResults));
        result.put("toolCalls", toolResults.size());
        return result;
    }

    // ========================== /stream SSE 真正流式 ==========================

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 流式聊天（WebClient 真正流式 + 逐 token 输出）")
    public SseEmitter streamChat(@Valid @RequestBody AssistantChatRequest req,
                                 @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
                                 @RequestParam(value = "deviceId", required = false) String paramDeviceId,
                                 HttpServletRequest request) {
        return streamOrchestrator.stream(req.toBodyMap(), headerDeviceId, paramDeviceId, request);
    }

    // ========================== 请求身份/准入（测试入口，实现在 AssistantRequestIdentity） ==========================

    /** Key 是否可用于外呼（空白/占位值视为未配置）。 */
    static boolean isApiKeyConfigured(String apiKey) {
        return AssistantRequestIdentity.isApiKeyConfigured(apiKey);
    }

    /** 流式会话落库守卫：仅「回复非空」且「首次进入」才落库。 */
    static boolean markPersistedOnce(AtomicBoolean persisted, int replyLength) {
        return AssistantRequestIdentity.markPersistedOnce(persisted, replyLength);
    }

    /** 按 IP 兜底限流的来源 IP（仅内网对端信任 X-Real-IP）。 */
    static String clientIp(String remoteAddr, String realIpHeader) {
        return AssistantRequestIdentity.clientIp(remoteAddr, realIpHeader);
    }

    /** 是否内网/回环对端。 */
    static boolean isPrivatePeer(String ip) {
        return AssistantRequestIdentity.isPrivatePeer(ip);
    }

    /** 按 IP 兜底限流的 Redis 键。 */
    private static String ipRateKey(HttpServletRequest request) {
        return AssistantRequestIdentity.ipRateKey(request);
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
     * 构建带历史记忆的消息列表（同时供 {@link AssistantStreamOrchestrator} 注入使用，
     * 保证同步/流式共用同一份系统提示与历史拼接逻辑）。
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
