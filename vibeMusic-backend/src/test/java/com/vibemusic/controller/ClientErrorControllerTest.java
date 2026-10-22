package com.vibemusic.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibemusic.dto.ClientErrorReport;
import com.vibemusic.service.RateLimitService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("前端错误上报接收端")
class ClientErrorControllerTest {

    private RateLimitService rateLimitService;
    private SimpleMeterRegistry meterRegistry;
    private ClientErrorController controller;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new ClientErrorController(rateLimitService, meterRegistry);

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(ClientErrorController.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ClientErrorController.class)).detachAppender(logAppender);
        meterRegistry.close();
    }

    private MockHttpServletRequest post(int contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/client-errors");
        request.setRequestURI("/api/client-errors");
        request.setRemoteAddr("10.0.0.7");
        // Spring 7 的 MockHttpServletRequest 没有 setContentLength，用真实字节数组体现长度
        if (contentLength > 0) {
            request.setContent(new byte[contentLength]);
        }
        return request;
    }

    private ClientErrorReport sample() {
        return new ClientErrorReport(
                "TypeError: x is undefined 联系 alice@example.com",
                "at /home/alice/app.js:10:3?token=abc123",
                "error", "/m/search?q=secret", "abc1234",
                "Mozilla/5.0 13812345678", "2026-10-23T00:00:00.000Z");
    }

    @Test
    @DisplayName("正常上报 → 204、计数器 +1、日志里敏感信息已脱敏")
    void acceptsAndScrubs() {
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);

        var response = controller.report(sample(), post(512));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        assertEquals(1.0, meterRegistry.get("client.error.reports").tag("kind", "error").counter().count());

        String logged = logAppender.list.get(0).getFormattedMessage();
        assertTrue(logged.contains("[CLIENT-ERROR]"), logged);
        assertFalse(logged.contains("alice@example.com"), logged);
        assertFalse(logged.contains("13812345678"), logged);
        assertFalse(logged.contains("token=abc123"), "query token 必须被抹掉: " + logged);
        assertTrue(logged.contains("token=<redacted>"), logged);
        assertTrue(logged.contains("<email>"), logged);
        assertFalse(logged.contains("/m/search?q=secret"), "route 只应保留 pathname: " + logged);
    }

    @Test
    @DisplayName("message 为空 → 400")
    void rejectsBlankMessage() {
        var response = controller.report(new ClientErrorReport("  ", null, "error", "/", "r", "ua", "ts"), post(100));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("body 超过 16KB → 413，且不落日志")
    void rejectsOversizedBody() {
        var response = controller.report(sample(), post(20 * 1024));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertTrue(logAppender.list.isEmpty());
    }

    @Test
    @DisplayName("超出每身份限额 → 429 + Retry-After")
    void rateLimited() {
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

        var response = controller.report(sample(), post(512));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("60", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    @DisplayName("限流组件异常 → fail-open（204），不影响上报可用性")
    void failsOpenWhenLimiterBroken() {
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        var response = controller.report(sample(), post(512));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    @DisplayName("非法 kind 归一到 error；超长字段被截断")
    void normalizesKindAndTruncates() {
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        String longMsg = "x".repeat(900);

        controller.report(new ClientErrorReport(longMsg, "y".repeat(5000), "hack",
                "/", "r".repeat(200), "u", "t"), post(9000));

        assertEquals(1.0, meterRegistry.get("client.error.reports").tag("kind", "error").counter().count());
        String logged = logAppender.list.get(0).getFormattedMessage();
        assertFalse(logged.contains("x".repeat(600)), "message 应截断到 500");
        assertFalse(logged.contains("y".repeat(4100)), "stack 应截断到 4000");
    }

    @Test
    @DisplayName("null body → 400（不 500）")
    void nullBodyHandled() {
        var response = controller.report(null, post(0));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("默认 kind=error（前端未传时）")
    void defaultKind() {
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        controller.report(new ClientErrorReport("boom", null, null, "/", null, null, null), post(64));
        assertEquals(1.0, meterRegistry.get("client.error.reports").tag("kind", "error").counter().count());
    }
}
