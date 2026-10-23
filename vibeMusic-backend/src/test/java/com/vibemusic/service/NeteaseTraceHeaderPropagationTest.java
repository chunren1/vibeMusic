package com.vibemusic.service;

import com.sun.net.httpserver.HttpServer;
import com.vibemusic.config.NeteaseApiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 HTTP 往返测试：证明 {@code NeteaseApiService} 真的把 {@code X-Trace-Id}
 * 发到了 musicapi，而不是只在工具类里拼了头。
 *
 * <p>用 JDK 自带 {@link HttpServer} 起临时端口 + 临时 RestTemplate（不加任何新依赖），
 * 服务端原样捕获请求头断言；无 MDC 时必须不发该头。
 */
@DisplayName("NeteaseApiService 出站 X-Trace-Id 真实 HTTP 透传")
class NeteaseTraceHeaderPropagationTest {

    private HttpServer server;
    private NeteaseApiService apiService;
    private final AtomicReference<String> capturedTraceId = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/netease/search", exchange -> {
            capturedTraceId.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            requestCount.incrementAndGet();
            byte[] body = "{\"code\":200,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        NeteaseApiConfig config = new NeteaseApiConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        apiService = new NeteaseApiService(config, new RestTemplate(), RestClient.builder());
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("MDC 有 traceId → 真实请求携带 X-Trace-Id，上游可读且值一致")
    void shouldSendTraceHeaderOverHttp() {
        MDC.put("traceId", "trace-http-abc123");

        Map<String, Object> body = apiService.searchNetease("晴天", 1);

        assertEquals(1, requestCount.get(), "必须真的发出一次 HTTP 请求");
        assertEquals("trace-http-abc123", capturedTraceId.get(), "网关侧应收到同一 traceId");
        assertEquals(200, body.get("code"), "响应正常解析，说明请求未被额外头破坏");
    }

    @Test
    @DisplayName("MDC 无 traceId → 不发送该头（不伪造空值）")
    void shouldOmitTraceHeaderOverHttpWhenMdcAbsent() {
        MDC.clear();

        apiService.searchNetease("晴天", 1);

        assertEquals(1, requestCount.get());
        assertNull(capturedTraceId.get(), "无 traceId 时不应出现 X-Trace-Id 头");
    }
}
