package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.utils.AnonymousIdentityUtils;
import com.vibemusic.config.RestTemplateConfig;
import com.vibemusic.service.AiCallBudget;
import com.vibemusic.service.ChatMemoryService;
import com.vibemusic.service.RateLimitService;
import com.vibemusic.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 流式对话（SSE）编排（从 AssistantController 抽出，行为不变）。
 *
 * <p>职责边界：准入判定（长度/Key/限流）→ 组装消息 → 订阅上游 SSE 逐 token 推送 →
 * 终态收尾（done/error 只发一次，complete 幂等，订阅必解绑）。上游请求体构造也在此，
 * 消息与系统提示仍由控制器的 {@link MessageAssembler} 提供（提示词只有一处）。
 *
 * <p>不抛异常：上游超时/异常一律降级为既有 error 事件与兜底文案，再 complete。
 */
@Slf4j
class AssistantStreamOrchestrator {

    /** 流式失败且一个字都没吐出来时的兜底文案（沿用既有文案）。 */
    private static final String STREAM_FALLBACK_TEXT = "网络不太好，再问我一次吧～";
    /** SseEmitter 自身超时在总预算之外预留的收尾余量。 */
    private static final long SSE_COMPLETE_GRACE_MS = 5_000L;
    /** 单条用户消息长度上限（SSE 入口与同步入口同口径）。 */
    private static final int MAX_MESSAGE_LENGTH = 2_000;

    /** 由控制器注入：组装「系统提示 + 历史记忆 + 本轮用户消息」，避免提示词副本。 */
    @FunctionalInterface
    interface MessageAssembler {
        List<Map<String, Object>> assemble(Long userId, String anonymousId, String userMessage, String songContext);
    }

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ChatMemoryService chatMemoryService;
    private final RateLimitService rateLimitService;
    private final MessageAssembler messageAssembler;
    private final String apiKey;
    private final String aiModel;
    private final String thinkingMode;
    private final long aiTotalBudgetMs;
    private final long streamFirstByteTimeoutMs;

    AssistantStreamOrchestrator(WebClient webClient,
                                ObjectMapper objectMapper,
                                ChatMemoryService chatMemoryService,
                                RateLimitService rateLimitService,
                                MessageAssembler messageAssembler,
                                String apiKey,
                                String aiModel,
                                String thinkingMode,
                                long aiTotalBudgetMs,
                                long streamFirstByteTimeoutMs) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.chatMemoryService = chatMemoryService;
        this.rateLimitService = rateLimitService;
        this.messageAssembler = messageAssembler;
        this.apiKey = apiKey;
        this.aiModel = aiModel;
        this.thinkingMode = thinkingMode;
        this.aiTotalBudgetMs = aiTotalBudgetMs;
        this.streamFirstByteTimeoutMs = streamFirstByteTimeoutMs;
    }

    /**
     * 完整流式会话：准入 → 组消息 → 订阅上游 → 推送 token → 终态收尾。
     * 无论成功失败都返回同一个 emitter（失败也走 SSE error 事件，不抛异常）。
     */
    SseEmitter stream(Map<String, Object> body,
                      String headerDeviceId,
                      String paramDeviceId,
                      HttpServletRequest request) {
        // 一次请求一份总预算：流式首包超时与总时长上限都从这份预算里取
        AiCallBudget budget = AiCallBudget.start(
                Duration.ofMillis(aiTotalBudgetMs), RestTemplateConfig.AI_RESPONSE_TIMEOUT);
        // SseEmitter 自身超时略大于总预算：先让「预算超时」走发 error 事件 + complete 的优雅路径
        SseEmitter emitter = new SseEmitter(budget.remainingMillis() + SSE_COMPLETE_GRACE_MS);
        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        if (userMessage != null && userMessage.length() > MAX_MESSAGE_LENGTH) {
            sendEvent(emitter, "error", Map.of("message", "消息过长，请限制在 2000 字以内"));
            emitter.complete();
            return emitter;
        }

        if (!AssistantRequestIdentity.isApiKeyConfigured(apiKey)) {
            sendEvent(emitter, "error", Map.of("message", "AI 助手暂未配置 API Key"));
            emitter.complete();
            return emitter;
        }

        Long userId = UserService.getCurrentUserId();
        final String anonymousId = userId != null ? null
                : AnonymousIdentityUtils.resolveAnonymousId(paramDeviceId, headerDeviceId);
        String rateKey = "assistant:" + (userId != null ? "user:" + userId : "anon:" + anonymousId);
        if (!rateLimitService.tryAcquire(rateKey, AssistantRequestIdentity.AI_RATE_LIMIT, Duration.ofMinutes(1))) {
            sendEvent(emitter, "error", Map.of("message", "请求太频繁，请稍后再试"));
            emitter.complete();
            return emitter;
        }
        if (!rateLimitService.tryAcquire(AssistantRequestIdentity.ipRateKey(request),
                AssistantRequestIdentity.AI_IP_RATE_LIMIT, Duration.ofMinutes(1))) {
            sendEvent(emitter, "error", Map.of("message", "请求太频繁，请稍后再试"));
            emitter.complete();
            return emitter;
        }

        List<Map<String, Object>> messages = messageAssembler.assemble(userId, anonymousId, userMessage, songContext);

        // 使用 WebClient 流式消费 LLM SSE 响应
        final Disposable[] disposableHolder = new Disposable[1];
        StringBuilder fullReply = new StringBuilder();
        // [DONE] 分支与 onComplete 都会到达「流结束」语义：守卫只允许落库一次，
        // 否则每轮对话在会话记忆里存两份（历史翻倍、token 成本翻倍）。
        final AtomicBoolean persisted = new AtomicBoolean(false);
        final Runnable persistOnce = () -> {
            if (!AssistantRequestIdentity.markPersistedOnce(persisted, fullReply.length())) return;
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
     * 构建流式请求体
     */
    private Map<String, Object> buildStreamRequestBody(List<Map<String, Object>> messages) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", aiModel);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", AssistantClient.MAX_TOKENS);
        requestBody.put("temperature", AssistantClient.TEMPERATURE);
        requestBody.put("stream", true);
        requestBody.put("thinking", Map.of("type", thinkingMode));
        return requestBody;
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
}
