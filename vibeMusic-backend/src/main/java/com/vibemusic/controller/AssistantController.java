package com.vibemusic.controller;

import com.vibemusic.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * AI 音乐助手 — SiliconFlow 平台 Qwen 模型
 *
 * 技术验证：传统 Java 后端集成大语言模型（OpenAI 兼容 API）。
 * 面试亮点：「能用标准 REST 调用 LLM，通过 Prompt Engineering 控制输出质量」
 *
 * 配置：环境变量 AI_API_KEY=sk-xxx
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "AI 助手", description = "Qwen 音乐推荐伴侣")
public class AssistantController {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String MODEL = "Qwen/Qwen3.5-4B";

    public AssistantController(RestTemplate restTemplate,
                               @Value("${ai.api-key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
    }

    @PostMapping("/chat")
    @Operation(summary = "AI 音乐聊天（Qwen）")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        if (apiKey == null || apiKey.isBlank()) {
            return Result.ok(Map.of("reply", "AI 助手暂未配置 API Key，请设置环境变量 AI_API_KEY"));
        }

        String userMessage = (String) body.getOrDefault("message", "推荐一首歌给我");
        String songContext = (String) body.getOrDefault("context", "");

        try {
            String reply = callLLM(userMessage, songContext);
            Map<String, Object> result = new HashMap<>();
            result.put("reply", reply);
            result.put("model", MODEL);
            return Result.ok(result);
        } catch (Exception e) {
            log.error("LLM API 调用失败", e);
            return Result.ok(Map.of("reply", "AI 助手暂时无法响应：" + e.getMessage()));
        }
    }

    private String callLLM(String userMessage, String context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String systemPrompt = """
            你是一个音乐推荐助手，风格像网易云音乐的评论区一样温暖、有共情力。
            规则：
            1. 回复控制在 100 字以内
            2. 如果用户让推荐歌曲，推荐真实存在的华语歌曲（周杰伦、林俊杰、陈奕迅等）
            3. 语气像朋友聊天，不要官方
            4. 不要输出 Markdown 格式
            5. 如果提供了当前歌曲信息，结合它来回答
            """ + (context.isEmpty() ? "" : "\n当前歌曲信息：" + context);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        requestBody.put("max_tokens", 200);
        requestBody.put("temperature", 0.7);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                API_URL, new HttpEntity<>(requestBody, headers), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
}
