package com.vibemusic.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceIdFilter 纯单元测试：透传、生成、MDC 注入/清理、异常时清理、响应头回写。
 */
@DisplayName("TraceIdFilter 链路追踪")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("请求头已带 X-Trace-Id → 原样透传（不生成新值）")
    void shouldPropagateUpstreamTraceId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        req.addHeader("X-Trace-Id", "upstream-abc123");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicReference<String> mdcInsideChain = new AtomicReference<>();
        FilterChain chain = (r, s) -> mdcInsideChain.set(MDC.get("traceId"));

        filter.doFilter(req, resp, chain);

        assertEquals("upstream-abc123", mdcInsideChain.get());
        assertEquals("upstream-abc123", resp.getHeader("X-Trace-Id"));
        assertNull(MDC.get("traceId"), "请求结束后必须清理 MDC");
    }

    @Test
    @DisplayName("无请求头 → 生成 12 位 traceId 并回写响应头")
    void shouldGenerateTraceIdWhenAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicReference<String> mdcInsideChain = new AtomicReference<>();
        filter.doFilter(req, resp, (r, s) -> mdcInsideChain.set(MDC.get("traceId")));

        String traceId = resp.getHeader("X-Trace-Id");
        assertNotNull(traceId);
        assertEquals(12, traceId.length());
        assertTrue(traceId.matches("[0-9a-f]{12}"), "应为去横杠 UUID 前 12 位 hex，实际: " + traceId);
        assertEquals(traceId, mdcInsideChain.get());
        assertNull(MDC.get("traceId"));
    }

    @Test
    @DisplayName("空字符串请求头 → 视为缺失，生成新 traceId")
    void shouldGenerateWhenHeaderEmpty() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        req.addHeader("X-Trace-Id", "");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) -> { });

        String traceId = resp.getHeader("X-Trace-Id");
        assertNotNull(traceId);
        assertEquals(12, traceId.length());
    }

    @Test
    @DisplayName("两次请求生成不同 traceId（无串号）")
    void shouldGenerateUniqueTraceIds() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/a"), first, (r, s) -> { });
        filter.doFilter(new MockHttpServletRequest("GET", "/b"), second, (r, s) -> { });

        assertNotEquals(first.getHeader("X-Trace-Id"), second.getHeader("X-Trace-Id"));
    }

    @Test
    @DisplayName("下游抛异常 → 异常向上抛出且 MDC 仍被 finally 清理（不污染线程池）")
    void shouldClearMdcEvenOnException() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain exploding = (r, s) -> {
            assertNotNull(MDC.get("traceId"), "链内应有 traceId");
            throw new ServletException("downstream boom");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(req, resp, exploding));
        assertNull(MDC.get("traceId"), "异常路径也必须清理 MDC");
    }

    @Test
    @DisplayName("IOException 同样向上抛出且清理 MDC")
    void shouldClearMdcOnIoException() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain exploding = (r, s) -> {
            throw new IOException("client aborted");
        };

        assertThrows(IOException.class, () -> filter.doFilter(req, resp, exploding));
        assertNull(MDC.get("traceId"));
    }

    @Test
    @DisplayName("已有 MDC 值被本请求覆盖（不继承上一个请求的 traceId）")
    void shouldOverrideStaleMdcValue() throws Exception {
        MDC.put("traceId", "stale-value");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        req.addHeader("X-Trace-Id", "fresh-value");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) -> { });

        assertEquals("fresh-value", resp.getHeader("X-Trace-Id"));
        assertNull(MDC.get("traceId"));
    }

    @Test
    @DisplayName("超长上游 traceId 原样透传（不做长度校验、不抛错）")
    void shouldPassThroughLongUpstreamTraceId() throws Exception {
        String longId = "x".repeat(500);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        req.addHeader("X-Trace-Id", longId);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) -> { });

        assertEquals(longId, resp.getHeader("X-Trace-Id"));
    }
}
