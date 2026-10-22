package com.vibemusic.common.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.Result;
import com.vibemusic.common.utils.RequestIdentity;
import com.vibemusic.config.RateLimitProperties;
import com.vibemusic.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 贵端点限流（P0-3）。
 *
 * <p>只拦两个“单请求放大 N 倍成本”的入口：
 * <ul>
 *   <li>{@code GET /api/songs/search} —— 一次扇出 5 个上游平台；</li>
 *   <li>{@code GET /api/songs/stream} —— 音频字节流代理，不设限等于开放免费 CDN。</li>
 * </ul>
 * 其余端点直接放行，不做任何额外判断。</p>
 *
 * <p>计数落在 Redis（复用 {@link RateLimitService} 的原子 INCR + TTL 脚本），
 * 因此本过滤器自身<b>不持有任何跨请求状态</b>，不会随不同身份数量增长而泄漏内存。</p>
 *
 * <p><b>fail-open</b>：限流依赖 Redis，Redis 抖动时如果 fail-closed，会把正常听歌/搜索
 * 全部打成 429/500——用一个可用性组件换掉整个业务是不可接受的取舍。故限流组件抛异常时
 * 放行本次请求并打 WARN，由 nginx 的 {@code limit_req} 与上游预算兜底。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String SEARCH_PATH = "/api/songs/search";
    private static final String STREAM_PATH = "/api/songs/stream";

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled() || !HttpMethod.GET.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        int max = maxFor(request);
        if (max <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String bucket = SEARCH_PATH.equals(pathOf(request)) ? "search" : "stream";
        String key = bucket + ":" + RequestIdentity.resolve(request);
        Duration window = Duration.ofSeconds(Math.max(1, properties.getWindowSeconds()));

        boolean allowed = true;
        long retryAfter = window.getSeconds();
        try {
            allowed = rateLimitService.tryAcquire(key, max, window);
            if (!allowed) {
                retryAfter = rateLimitService.remainingSeconds(key, window.getSeconds());
            }
        } catch (RuntimeException e) {
            // fail-open：限流自身的故障不能放大成业务故障
            log.warn("[RATE-LIMIT] 限流组件异常，放行本次请求 key={} err={}", key, e.toString());
        }

        if (!allowed) {
            writeTooManyRequests(response, retryAfter);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** @return 该路径对应的每窗口上限；0 表示不限制 */
    private int maxFor(HttpServletRequest request) {
        String path = pathOf(request);
        if (SEARCH_PATH.equals(path)) {
            return properties.getSearchPerMin();
        }
        if (STREAM_PATH.equals(path)) {
            return properties.getStreamPerMin();
        }
        return 0;
    }

    /** 请求路径（不含查询串、容忍尾斜杠）。 */
    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String path = uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int semicolon = path.indexOf(';');
        return semicolon >= 0 ? path.substring(0, semicolon) : path;
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfter) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), Result.error(429, "请求过于频繁，请稍后再试"));
    }
}
