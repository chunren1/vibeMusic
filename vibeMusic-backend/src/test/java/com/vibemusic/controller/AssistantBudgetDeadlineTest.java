package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vibemusic.service.AiToolService;
import com.vibemusic.service.ChatMemoryService;
import com.vibemusic.service.RateLimitService;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 链路「总预算」测试（2026-10-23）。
 * <p>
 * 背景：Function Calling 最多 3 次 LLM 调用，客户端超时 60s ⇒ 单请求最坏 180s；
 * 流式路径缺 responseTimeout ⇒ 上游不回首包时连接无限挂着。
 * <p>
 * 这里用 JDK 自带 {@link HttpServer} 起一个「只接受连接、永不回响应头」的 stub
 * （零新依赖），配合毫秒级预算断言：请求必须在自己那份预算内中止并走既有降级分支。
 */
@DisplayName("AI 链路总预算（同步对话 / 流式 / 工具调用）")
class AssistantBudgetDeadlineTest {

    private static final String CHAT_PATH = "/chat/completions";
    private static final String FALLBACK_REPLY = "让我想想...请稍后再试";

    private RestTemplate restTemplate;
    private AiToolService aiToolService;
    private ChatMemoryService chatMemoryService;
    private RateLimitService rateLimitService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class, RETURNS_DEEP_STUBS);
        aiToolService = mock(AiToolService.class);
        chatMemoryService = mock(ChatMemoryService.class);
        rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(chatMemoryService.getHistory(any(), any())).thenReturn(List.of());
    }

    // ========================== 同步对话 ==========================

    @Test
    @DisplayName("同步对话：单次 LLM 超时被 min(60s 上限, 剩余预算) 截断，上游不回包也在预算内降级")
    void chatShouldAbortWithinBudgetWhenUpstreamNeverResponds() throws Exception {
        HttpServer server = startNeverRespondingServer();
        // 真实 Apache 客户端，默认读超时 60s——证明生效的是「请求级」预算而不是客户端默认值
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(60))
                        .build())
                .build()) {
            RestTemplate realTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
            MockMvc mvc = mockMvc(controller(realTemplate, urlOf(server), 400L, 20_000L));

            long startNanos = System.nanoTime();
            mvc.perform(post("/api/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"推荐首歌\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.reply").value(containsString("让我想想")));
            long elapsedMs = elapsedMs(startNanos);

            assertTrue(elapsedMs < 3_000L,
                    "400ms 预算的同步对话应在预算内返回降级文案，实际耗时 " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Function Calling：工具吃满预算后不再发起第 3 次 LLM 调用")
    void shouldNotStartNextLlmCallOnceBudgetIsExhausted() throws Exception {
        when(aiToolService.getToolDefinitions()).thenReturn(List.of());
        // 每次工具调用都很慢（500ms），把 800ms 总预算吃掉
        when(aiToolService.executeTool(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(500L);
            return "{\"songs\":[]}";
        });
        // 上游每次都回「我要调工具」：旧实现会一路 3 次 LLM × 60s
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenAnswer(invocation -> new ResponseEntity<>(alwaysToolCallChoice(), HttpStatus.OK));

        MockMvc mvc = mockMvc(controller(restTemplate, "http://127.0.0.1:9" + CHAT_PATH, 800L, 20_000L));

        long startNanos = System.nanoTime();
        mvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"推荐周杰伦的歌\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reply").value(containsString("让我想想")));
        long elapsedMs = elapsedMs(startNanos);

        // 关键断言：最多只发起前两次 LLM 调用（首次 + 第 1 轮工具后的续轮），第 3 次绝不外呼
        verify(restTemplate, atMost(2)).postForEntity(anyString(), any(), eq(Map.class));
        verify(aiToolService, atMost(2)).executeTool(anyString(), anyString());
        assertTrue(elapsedMs < 3_000L, "预算耗尽后应立即降级，实际耗时 " + elapsedMs + "ms");
    }

    // ========================== 流式对话 ==========================

    @Test
    @DisplayName("流式首包超时：上游不回响应头时在首包预算内中止并走降级分支")
    void streamShouldAbortWhenFirstByteTimeoutExpires() throws Exception {
        HttpServer server = startNeverRespondingServer();
        try {
            MockMvc mvc = mockMvc(controller(restTemplate, urlOf(server), 45_000L, 300L));

            long startNanos = System.nanoTime();
            MvcResult result = mvc.perform(post("/api/assistant/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\"}"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            result.getAsyncResult(5_000L);
            long elapsedMs = elapsedMs(startNanos);
            String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertTrue(content.contains("AI 服务暂时不可用"), "超时应发 error 事件而不是挂死: " + content);
            assertTrue(elapsedMs < 3_000L, "首包 300ms 超时应尽快收尾，实际耗时 " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("流式总预算截断首包超时：配置 20s 但总预算 400ms 时按预算中止")
    void streamTotalBudgetShouldCapFirstByteTimeout() throws Exception {
        HttpServer server = startNeverRespondingServer();
        try {
            // 首包配置远大于总预算：生效的必须是剩余预算
            MockMvc mvc = mockMvc(controller(restTemplate, urlOf(server), 400L, 20_000L));

            long startNanos = System.nanoTime();
            MvcResult result = mvc.perform(post("/api/assistant/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\"}"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            result.getAsyncResult(5_000L);
            long elapsedMs = elapsedMs(startNanos);
            String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertTrue(content.contains("AI 服务暂时不可用"), "超时应发 error 事件: " + content);
            assertTrue(elapsedMs < 3_000L, "总预算 400ms 时应在预算内收尾，实际耗时 " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }

    // ========================== 辅助 ==========================

    private AssistantController controller(RestTemplate template, String apiUrl, long budgetMs, long firstByteMs) {
        return new AssistantController(template, WebClient.builder(), aiToolService, chatMemoryService,
                rateLimitService, objectMapper, "sk-test-api-key", "deepseek-flash", "disabled",
                apiUrl, budgetMs, firstByteMs);
    }

    private MockMvc mockMvc(AssistantController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                // 与真实 Boot 一致（中文不要被 standalone 默认的 ISO-8859-1 打成 '?'）
                .setMessageConverters(
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(objectMapper),
                        new org.springframework.http.converter.StringHttpMessageConverter(StandardCharsets.UTF_8))
                .build();
    }

    /** 「每次都返回工具调用」的 LLM 响应。 */
    private static Map<String, Object> alwaysToolCallChoice() {
        Map<String, Object> function = Map.of("name", "search_songs", "arguments", "{\"keyword\":\"周杰伦\"}");
        Map<String, Object> toolCall = Map.of("id", "call_1", "type", "function", "function", function);
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", List.of(toolCall));
        return Map.of("choices", List.of(Map.of("message", message)));
    }

    /**
     * 只接受连接、永不回响应头的 stub：handler 阻塞住，模拟上游卡死。
     * 用 daemon 线程，测试结束后不阻塞 JVM。
     */
    private static HttpServer startNeverRespondingServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CHAT_PATH, exchange -> {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-never-respond");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }

    private static String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + CHAT_PATH;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
