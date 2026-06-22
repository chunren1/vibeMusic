package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.Result;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.service.SongSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AI 音乐助手 — 对话 + 结构化歌曲推荐
 * <p>
 * 提供两种接口：
 * - POST /chat   → 传统同步（兼容旧客户端）
 * - POST /stream → SSE 流式（减少阻塞时间）
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "AI 助手", description = "音乐聊天 + 智能推荐")
public class AssistantController {

    private final RestTemplate restTemplate;
    private final SongSearchService songSearchService;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String MODEL = "Qwen/Qwen3.5-4B";

    public AssistantController(RestTemplate restTemplate,
                               SongSearchService songSearchService,
                               ObjectMapper objectMapper,
                               @Value("${ai.api-key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.songSearchService = songSearchService;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    @PostMapping("/chat")
    @Operation(summary = "AI 音乐聊天（对话 + 搜歌推荐）")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        if (apiKey == null || apiKey.isBlank()) {
            return Result.ok(Map.of("reply", "AI 助手暂未配置 API Key，请设置环境变量 AI_API_KEY", "model", MODEL));
        }

        // 并行调用：AI 对话 + 歌曲搜索
        CompletableFuture<String> aiFuture = CompletableFuture.supplyAsync(() -> callLLM(userMessage, songContext));
        CompletableFuture<List<Map<String, Object>>> searchFuture = CompletableFuture.supplyAsync(() -> searchSongs(userMessage));

        try {
            String reply = aiFuture.get(15, TimeUnit.SECONDS);
            List<Map<String, Object>> songs = searchFuture.get(5, TimeUnit.SECONDS);

            Map<String, Object> result = new HashMap<>();
            result.put("reply", reply);
            result.put("model", MODEL);
            result.put("songs", songs);
            return Result.ok(result);
        } catch (Exception e) {
            log.error("AI / 搜索调用失败", e);
            return Result.ok(Map.of("reply", "让我想想...请稍后再试", "model", MODEL, "songs", List.of()));
        }
    }

    /**
     * SSE 流式聊天 — 避免阻塞 servlet 线程直到 AI 完整响应
     * <p>
     * 事件格式：
     *   data: {"type":"token","content":"你"}      ← AI 逐字输出
     *   data: {"type":"done","full":"完整回复"}     ← 完成
     *   data: {"type":"songs","list":[...]}         ← 歌曲搜索结果
     *   data: {"type":"error","message":"..."}      ← 异常
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 流式聊天（SSE，逐字输出）")
    public SseEmitter streamChat(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(30_000L); // 30s 超时
        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        if (apiKey == null || apiKey.isBlank()) {
            sendEvent(emitter, "error", Map.of("message", "AI_API_KEY 未配置"));
            emitter.complete();
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 先发歌曲搜索结果
                List<Map<String, Object>> songs = searchSongs(userMessage);
                if (!songs.isEmpty()) {
                    sendEvent(emitter, "songs", Map.of("list", songs));
                }

                // 流式调用 LLM，逐 token 推送
                String fullReply = callLLMStreaming(userMessage, songContext, emitter);
                sendEvent(emitter, "done", Map.of("full", fullReply, "model", MODEL));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 流式调用失败", e);
                sendEvent(emitter, "error", Map.of("message", "AI 服务暂时不可用"));
                emitter.complete();
            }
        });

        emitter.onError(emitter::completeWithError);
        emitter.onTimeout(emitter::complete);
        return emitter;
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

    /**
     * 流式调用 LLM，逐 token 通过 emitter 推送
     */
    private String callLLMStreaming(String userMessage, String context, SseEmitter emitter) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String systemPrompt = buildSystemPrompt(context);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        requestBody.put("max_tokens", 200);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true); // 开启流式

        StringBuilder fullReply = new StringBuilder();
        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(API_URL, entity, String.class);

            if (response.getBody() == null) {
                sendEvent(emitter, "token", Map.of("content", "我现在有点迷糊，稍等再聊～"));
                return "我现在有点迷糊，稍等再聊～";
            }

            // 解析 SSE 流响应（SiliconFlow 格式: data:{...}）
            for (String line : response.getBody().split("\n")) {
                if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                    String json = line.substring(6);
                    try {
                        Map<String, Object> chunk = objectMapper.readValue(json, Map.class);
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                            if (delta != null) {
                                String content = (String) delta.get("content");
                                if (content != null && !content.isEmpty()) {
                                    fullReply.append(content);
                                    sendEvent(emitter, "token", Map.of("content", content));
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("LLM 流式调用异常: {}", e.getMessage());
            if (fullReply.isEmpty()) {
                fullReply.append("网络不太好，再问我一次吧～");
                sendEvent(emitter, "token", Map.of("content", fullReply.toString()));
            }
        }
        return fullReply.toString();
    }

    // ---- 非流式 AI 对话（/chat 端点使用）----

    private String callLLM(String userMessage, String context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", buildSystemPrompt(context)),
                Map.of("role", "user", "content", userMessage)
        ));
        requestBody.put("max_tokens", 200);
        requestBody.put("temperature", 0.7);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                API_URL, new HttpEntity<>(requestBody, headers), Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        if (body == null) return "我现在有点迷糊，稍等再聊～";
        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        if (choices == null || choices.isEmpty()) return "网络不太好，再问我一次吧～";
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }

    private String buildSystemPrompt(String context) {
        String prompt = """
            你是一个温暖的音乐推荐助手，叫 vibe 音乐精灵。
            规则：
            1. 回复自然、有共情力，像朋友聊天
            2. 控制在 100 字以内
            3. 如果用户让推荐歌曲，推荐真实华语歌曲并解释推荐理由
            4. 不要输出 Markdown 或 JSON
            5. 如果用户问"你是谁"之类的 → 说你是 vibe 音乐精灵""";
        if (context != null && !context.isEmpty()) {
            prompt += "\n当前用户正在听：" + context;
        }
        return prompt;
    }

    // ---- 歌曲搜索（根据用户消息提取关键词） ----

    private List<Map<String, Object>> searchSongs(String userMessage) {
        try {
            List<SongDTO> results = songSearchService.search(userMessage, 1, 6, "qq");
            if (results == null || results.isEmpty()) {
                results = songSearchService.search(userMessage, 1, 6);
            }
            if (results == null || results.isEmpty()) return List.of();

            return results.stream().map(s -> {
                Map<String, Object> card = new HashMap<>();
                card.put("sourceId", s.getSourceId());
                card.put("name", s.getName());
                card.put("artist", s.getArtist() != null ? s.getArtist() : "");
                card.put("coverUrl", s.getCoverUrl() != null ? s.getCoverUrl() : "");
                card.put("duration", s.getDuration() != null ? s.getDuration() : 0);
                card.put("platform", s.getPlatform() != null ? s.getPlatform() : "");
                return card;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Assistant 搜索失败: {}", e.getMessage());
            return List.of();
        }
    }
}
