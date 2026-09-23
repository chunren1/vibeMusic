package com.vibemusic.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 慢请求观测（round6 体验/排查专项）。
 *
 * <p>背景：2026-09-24 OOM 事故时，日志里只有散落的业务行，无法回答"是哪类请求
 * 在堆积/变慢"。本过滤器只对**超过阈值的请求**打一行结构化日志（方法/路径/状态/耗时），
 * 常规请求零输出——每接口时延的常态观测已由 Micrometer 的
 * {@code http.server.requests} 指标覆盖，不在此重复。
 *
 * <p>跳过流式/二进制端点：它们"耗时长"是正常形态（一首歌几分钟），打出来全是噪声；
 * 其上游耗时已由 [API-LAYER]/[CACHE-LAYER]/降级链日志覆盖。
 *
 * <p>顺序：在 {@link TraceIdFilter}（HIGHEST_PRECEDENCE）之后，慢日志自动带上 traceId，
 * 可与同请求的其他日志串起来。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SlowRequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestLogFilter.class);

    /** 慢请求阈值：搜索链路上限 4s，超过 1.5s 即值得看。 */
    static final long SLOW_THRESHOLD_MS = 1_500L;

    /** 流式/二进制端点前缀：长耗时是正常形态，不参与慢请求判定。 */
    private static final boolean isStreaming(String uri) {
        return uri.startsWith("/api/songs/stream")
                || uri.startsWith("/api/image-proxy")
                || uri.startsWith("/api/assistant/stream");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isStreaming(request.getRequestURI());
    }

    /** 纯函数：该请求是否需要打慢日志（流式端点永不参与，阈值以上才打）。 */
    static boolean shouldLog(String uri, long costMs) {
        return !isStreaming(uri) && costMs >= SLOW_THRESHOLD_MS;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (shouldLog(request.getRequestURI(), cost)) {
                log.warn("[SLOW-REQUEST] {} {} -> {} ({}ms)",
                        request.getMethod(), request.getRequestURI(),
                        response.getStatus(), cost);
            }
        }
    }
}
