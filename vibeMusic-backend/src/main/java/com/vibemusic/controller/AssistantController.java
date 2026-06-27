package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.Result;
import com.vibemusic.service.AiToolService;
import com.vibemusic.service.ChatMemoryService;
import com.vibemusic.service.RateLimitService;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.*;

/**
 * AI 音乐助手 — Function Calling + 多轮对话记忆 + 真正 SSE 流式
 * <p>
 * 三大升级：
 * 1. Function Calling：AI 自主决定调用 search_songs / get_user_history 工具
 * 2. 多轮对话记忆：基于 Redis 存储会话历史，支持上下文关联
 * 3. 真正 SSE 流式：WebClient 流式读取 LLM 响应，逐 token 推送
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
    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String MODEL = "deepseek-ai/DeepSeek-V4-Flash";
    private static final int AI_RATE_LIMIT = 10;

    public AssistantController(RestTemplate restTemplate,
                               WebClient.Builder webClientBuilder,
                               AiToolService aiToolService,
                               ChatMemoryService chatMemoryService,
                               RateLimitService rateLimitService,
                               ObjectMapper objectMapper,
                               @Value("${ai.api-key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.webClient = webClientBuilder.baseUrl(API_URL).build();
        this.aiToolService = aiToolService;
        this.chatMemoryService = chatMemoryService;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    // ========================== /chat 同步对话 ==========================

    @PostMapping("/chat")
    @Operation(summary = "AI 音乐聊天（Function Calling + 多轮记忆）")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        if (userMessage != null && userMessage.length() > 2000)
            return Result.error("消息过长，请限制在 2000 字以内");

        if (apiKey == null || apiKey.isBlank())
            return Result.ok(Map.of("reply", "AI 助手暂未配置 API Key，请设置环境变量 AI_API_KEY", "model", MODEL));

        Long userId = UserService.getCurrentUserId();
        String rateKey = "assistant:" + (userId != null ? "user:" + userId : "anonymous");
        if (!rateLimitService.tryAcquire(rateKey, AI_RATE_LIMIT, java.time.Duration.ofMinutes(1)))
            return Result.error(429, "请求太频繁，请稍后再试（每分钟最多 " + AI_RATE_LIMIT + " 次）");

        try {
            // 构建带历史记忆的消息列表
            List<Map<String, Object>> messages = buildMessagesWithHistory(userId, userMessage, songContext);

            // 第一轮：带 Function Calling 调用 LLM
            Map<String, Object> llmResponse = callLLMWithTools(messages);
            List<Map<String, Object>> toolResults = new ArrayList<>();

            // 处理 Function Calling（最多 2 轮工具调用）
            int toolRounds = 0;
            while (hasToolCalls(llmResponse) && toolRounds < 2) {
                toolRounds++;
                List<Map<String, Object>> toolCalls = extractToolCalls(llmResponse);

                // 把 assistant 的 tool_calls 消息加入历史
                messages.add(extractAssistantMessage(llmResponse));

                // 执行每个工具调用
                for (Map<String, Object> toolCall : toolCalls) {
                    String functionName = (String) ((Map<?, ?>) toolCall.get("function")).get("name");
                    String arguments = (String) ((Map<?, ?>) toolCall.get("function")).get("arguments");
                    String toolCallId = (String) toolCall.get("id");

                    log.info("[Function Calling] 执行工具: {} args={}", functionName, arguments);
                    String result = aiToolService.executeTool(functionName, arguments);
                    toolResults.add(Map.of("name", functionName, "result", result));

                    // 把工具结果以 tool 角色回传给 LLM
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", toolCallId,
                        "content", result
                    ));
                }

                // 第二轮：带上工具结果再次调用 LLM 生成最终回复
                llmResponse = callLLMWithTools(messages);
            }

            String reply = extractContent(llmResponse);
            if (reply == null || reply.isBlank()) reply = "让我想想...请稍后再试";

            // 保存对话到记忆
            chatMemoryService.appendMessage(userId, "user", userMessage);
            chatMemoryService.appendMessage(userId, "assistant", reply);

            // 从工具结果中提取歌曲列表
            List<Map<String, Object>> songs = extractSongsFromToolResults(toolResults);

            Map<String, Object> result = new HashMap<>();
            result.put("reply", reply);
            result.put("model", MODEL);
            result.put("songs", songs);
            result.put("toolCalls", toolResults.size());
            return Result.ok(result);
        } catch (Exception e) {
            log.error("AI 对话失败", e);
            return Result.ok(Map.of("reply", "让我想想...请稍后再试", "model", MODEL, "songs", List.of()));
        }
    }

    // ========================== /stream SSE 真正流式 ==========================

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 流式聊天（WebClient 真正流式 + 逐 token 输出）")
    public SseEmitter streamChat(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(60_000L);
        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        if (userMessage != null && userMessage.length() > 2000) {
            sendEvent(emitter, "error", Map.of("message", "消息过长，请限制在 2000 字以内"));
            emitter.complete();
            return emitter;
        }

        if (apiKey == null || apiKey.isBlank()) {
            sendEvent(emitter, "error", Map.of("message", "AI_API_KEY 未配置"));
            emitter.complete();
            return emitter;
        }

        Long userId = UserService.getCurrentUserId();
        String rateKey = "assistant:" + (userId != null ? "user:" + userId : "anonymous");
        if (!rateLimitService.tryAcquire(rateKey, AI_RATE_LIMIT, java.time.Duration.ofMinutes(1))) {
            sendEvent(emitter, "error", Map.of("message", "请求太频繁，请稍后再试"));
            emitter.complete();
            return emitter;
        }

        List<Map<String, Object>> messages = buildMessagesWithHistory(userId, userMessage, songContext);

        // 使用 WebClient 流式消费 LLM SSE 响应
        final Disposable[] disposableHolder = new Disposable[1];
        StringBuilder fullReply = new StringBuilder();

        disposableHolder[0] = webClient.post()
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(buildStreamRequestBody(messages))
            .retrieve()
            .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .subscribe(
                sse -> {
                    String data = sse.data();
                    if (data == null || "[DONE]".equals(data.trim())) {
                        // 流结束
                        chatMemoryService.appendMessage(userId, "user", userMessage);
                        chatMemoryService.appendMessage(userId, "assistant", fullReply.toString());
                        sendEvent(emitter, "done", Map.of("full", fullReply.toString(), "model", MODEL));
                        emitter.complete();
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
                    log.error("SSE 流式调用失败: {}", error.getMessage());
                    if (fullReply.isEmpty()) {
                        sendEvent(emitter, "token", Map.of("content", "网络不太好，再问我一次吧～"));
                    }
                    sendEvent(emitter, "error", Map.of("message", "AI 服务暂时不可用"));
                    emitter.complete();
                },
                () -> {
                    if (fullReply.length() > 0) {
                        chatMemoryService.appendMessage(userId, "user", userMessage);
                        chatMemoryService.appendMessage(userId, "assistant", fullReply.toString());
                    }
                    sendEvent(emitter, "done", Map.of("full", fullReply.toString(), "model", MODEL));
                    emitter.complete();
                }
            );

        emitter.onCompletion(() -> { if (disposableHolder[0] != null) disposableHolder[0].dispose(); });
        emitter.onTimeout(() -> { if (disposableHolder[0] != null) disposableHolder[0].dispose(); emitter.complete(); });
        emitter.onError(e -> { if (disposableHolder[0] != null) disposableHolder[0].dispose(); });

        return emitter;
    }

    // ========================== 清除对话记忆 ==========================

    @DeleteMapping("/history")
    @Operation(summary = "清除 AI 对话历史")
    public Result<Void> clearHistory() {
        Long userId = UserService.getCurrentUserId();
        chatMemoryService.clearHistory(userId);
        return Result.ok(null);
    }

    // ========================== 内部方法 ==========================

    /**
     * 构建带历史记忆的消息列表
     */
    private List<Map<String, Object>> buildMessagesWithHistory(Long userId, String userMessage, String songContext) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(songContext)));

        // 加载对话历史
        List<Map<String, String>> history = chatMemoryService.getHistory(userId);
        for (Map<String, String> msg : history) {
            messages.add(new HashMap<>(msg));
        }

        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    /**
     * 调用 LLM（带 Function Calling 工具定义）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callLLMWithTools(List<Map<String, Object>> messages) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 800);
        requestBody.put("temperature", 0.7);
        requestBody.put("tools", aiToolService.getToolDefinitions());
        requestBody.put("tool_choice", "auto");

        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            org.springframework.http.ResponseEntity<Map> response = restTemplate.postForEntity(
                API_URL, new org.springframework.http.HttpEntity<>(requestBody, headers), Map.class);

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
     * 构建流式请求体
     */
    private Map<String, Object> buildStreamRequestBody(List<Map<String, Object>> messages) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", 800);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);
        return requestBody;
    }

    @SuppressWarnings("unchecked")
    private boolean hasToolCalls(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        return message != null && message.get("tool_calls") != null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolCalls(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        return (List<Map<String, Object>>) message.get("tool_calls");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractAssistantMessage(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", message.getOrDefault("content", ""));
        msg.put("tool_calls", message.get("tool_calls"));
        return msg;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        if (message == null) return null;
        return (String) message.get("content");
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
