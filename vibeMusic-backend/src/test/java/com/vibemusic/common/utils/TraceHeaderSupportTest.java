package com.vibemusic.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceHeaderSupport 纯单元测试：只覆盖出站头注入的"有/无/非法"三种输入。
 */
@DisplayName("TraceHeaderSupport 出站 traceId 透传")
class TraceHeaderSupportTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 有 traceId → 注入 X-Trace-Id，且不覆盖其他头")
    void shouldSetTraceHeaderWhenMdcPresent() {
        MDC.put(TraceHeaderSupport.MDC_KEY, "trace-abc-123");
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0");

        TraceHeaderSupport.apply(headers);

        assertEquals("trace-abc-123", headers.getFirst(TraceHeaderSupport.TRACE_HEADER));
        assertEquals("Mozilla/5.0", headers.getFirst("User-Agent"), "不得影响既有头");
    }

    @Test
    @DisplayName("MDC 无 traceId → 不注入该头（不伪造空值）")
    void shouldNotSetTraceHeaderWithoutMdc() {
        HttpHeaders headers = new HttpHeaders();

        TraceHeaderSupport.apply(headers);

        assertNull(headers.getFirst(TraceHeaderSupport.TRACE_HEADER));
        assertTrue(headers.isEmpty(), "无 traceId 时不应产生任何头");
    }

    @Test
    @DisplayName("MDC 值为空白 → 不注入")
    void shouldIgnoreBlankTraceId() {
        MDC.put(TraceHeaderSupport.MDC_KEY, "   ");
        HttpHeaders headers = new HttpHeaders();

        TraceHeaderSupport.apply(headers);

        assertNull(headers.getFirst(TraceHeaderSupport.TRACE_HEADER));
    }

    @Test
    @DisplayName("MDC 值含 CRLF → 丢弃（防头注入/日志注入）")
    void shouldIgnoreTraceIdWithCrlf() {
        MDC.put(TraceHeaderSupport.MDC_KEY, "abc\r\nX-Injected: 1");
        HttpHeaders headers = new HttpHeaders();

        TraceHeaderSupport.apply(headers);

        assertNull(headers.getFirst(TraceHeaderSupport.TRACE_HEADER));
        assertNull(headers.getFirst("X-Injected"));
    }

    @Test
    @DisplayName("MDC 值超长 → 丢弃")
    void shouldIgnoreOversizedTraceId() {
        MDC.put(TraceHeaderSupport.MDC_KEY, "a".repeat(257));
        HttpHeaders headers = new HttpHeaders();

        TraceHeaderSupport.apply(headers);

        assertNull(headers.getFirst(TraceHeaderSupport.TRACE_HEADER));
    }

    @Test
    @DisplayName("headers 为 null → 静默忽略不抛异常")
    void shouldTolerateNullHeaders() {
        MDC.put(TraceHeaderSupport.MDC_KEY, "trace-abc-123");
        assertDoesNotThrow(() -> TraceHeaderSupport.apply(null));
    }
}
