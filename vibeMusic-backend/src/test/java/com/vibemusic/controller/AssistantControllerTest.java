package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.service.AiToolService;
import com.vibemusic.service.ChatMemoryService;
import com.vibemusic.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AssistantController AI 助手测试
 * <p>
 * MockMvc + Mockito 纯单元测试，不加载 Spring 上下文。
 * 覆盖：正常对话、Function Calling、限流、输入校验、清除历史。
 */
@DisplayName("AssistantController AI 助手测试")
class AssistantControllerTest {

    private AssistantController assistantController;
    private RestTemplate restTemplate;
    private AiToolService aiToolService;
    private ChatMemoryService chatMemoryService;
    private RateLimitService rateLimitService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class, RETURNS_DEEP_STUBS);
        aiToolService = mock(AiToolService.class);
        chatMemoryService = mock(ChatMemoryService.class);
        rateLimitService = mock(RateLimitService.class);
        // 使用真实 WebClient.Builder 避免 Mock 接口默认方法问题
        assistantController = new AssistantController(
                restTemplate,
                WebClient.builder(),
                aiToolService,
                chatMemoryService,
                rateLimitService,
                objectMapper,
                "sk-test-api-key");
        mockMvc = MockMvcBuilders.standaloneSetup(assistantController).build();
    }

    @Nested @DisplayName("POST /api/assistant/chat")
    class ChatEndpointTest {

        @Test @DisplayName("正常对话应返回 reply 字段")
        void shouldReturnReply() throws Exception {
            when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
            when(chatMemoryService.getHistory(any())).thenReturn(java.util.List.of());

            var responseBody = Map.of("choices", java.util.List.of(
                    Map.of("message", Map.of("role", "assistant", "content", "你好！我是音乐精灵～"))));
            when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(responseBody, org.springframework.http.HttpStatus.OK));

            mockMvc.perform(post("/api/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reply").isString())
                    .andExpect(jsonPath("$.data.model").value("deepseek-ai/DeepSeek-V4-Flash"));
        }

        @Test @DisplayName("消息超过 2000 字应返回错误")
        void shouldRejectLongMessage() throws Exception {
            String longMsg = "a".repeat(2001);

            mockMvc.perform(post("/api/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"" + longMsg + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("过长")));
        }

        @Test @DisplayName("未配置 API Key 应返回提示")
        void shouldReturnNoApiKeyMessage() throws Exception {
            // 创建一个 apiKey 为空的 Controller 实例
            var noKeyController = new AssistantController(
                    restTemplate, WebClient.builder(), aiToolService,
                    chatMemoryService, rateLimitService, objectMapper, "");
            var noKeyMockMvc = MockMvcBuilders.standaloneSetup(noKeyController).build();

            noKeyMockMvc.perform(post("/api/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reply").value(
                            org.hamcrest.Matchers.containsString("未配置")));
        }

        @Test @DisplayName("限流触发时应返回 429")
        void shouldReturn429OnRateLimit() throws Exception {
            when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

            mockMvc.perform(post("/api/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"推荐首歌\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(429))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("频繁")));
        }

        @Test @DisplayName("Function Calling 应触发工具调用")
        void shouldTriggerFunctionCalling() throws Exception {
            when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
            when(chatMemoryService.getHistory(any())).thenReturn(java.util.List.of());
            when(aiToolService.getToolDefinitions()).thenReturn(java.util.List.of(
                    Map.of("type", "function", "function",
                            Map.of("name", "search_songs", "description", "搜索歌曲"))));

            // 第一轮：LLM 返回 tool_calls
            java.util.List<Map<String, Object>> toolCalls = java.util.List.of(
                    Map.of("id", "call_1", "type", "function",
                            "function", Map.of("name", "search_songs", "arguments", "{\"keyword\":\"周杰伦\"}")));
            Map<String, Object> toolCallMsg = new java.util.HashMap<>();
            toolCallMsg.put("role", "assistant");
            toolCallMsg.put("content", null);
            toolCallMsg.put("tool_calls", toolCalls);
            var toolCallResponse = Map.of("choices", java.util.List.of(Map.of("message", toolCallMsg)));

            // 第二轮：LLM 返回最终回复
            var finalMsg = Map.of("message", Map.of("role", "assistant", "content", "为你找到周杰伦的歌～"));
            var finalResponse = Map.of("choices", java.util.List.of(finalMsg));

            when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(toolCallResponse, org.springframework.http.HttpStatus.OK))
                    .thenReturn(new org.springframework.http.ResponseEntity<>(finalResponse, org.springframework.http.HttpStatus.OK));
            when(aiToolService.executeTool(eq("search_songs"), eq("{\"keyword\":\"周杰伦\"}")))
                    .thenReturn("{\"songs\":[{\"name\":\"晴天\"}],\"total\":1}");

            mockMvc.perform(post("/api/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"推荐周杰伦的歌\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.toolCalls").value(1));
        }
    }

    @Nested @DisplayName("DELETE /api/assistant/history")
    class HistoryEndpointTest {

        @Test @DisplayName("清除历史应返回 200")
        void shouldClearHistory() throws Exception {
            mockMvc.perform(delete("/api/assistant/history"))
                    .andExpect(status().isOk());
        }
    }
}
