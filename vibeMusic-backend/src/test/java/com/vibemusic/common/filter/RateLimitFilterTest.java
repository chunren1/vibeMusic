package com.vibemusic.common.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.config.RateLimitProperties;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.service.RateLimitService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("贵端点限流过滤器")
class RateLimitFilterTest {

    private RateLimitService rateLimitService;
    private RateLimitProperties properties;
    private RateLimitFilter filter;

    private final AtomicInteger chainCalls = new AtomicInteger();
    private final FilterChain chain = (req, res) -> chainCalls.incrementAndGet();

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        properties = new RateLimitProperties();
        filter = new RateLimitFilter(rateLimitService, properties, new ObjectMapper());
        chainCalls.set(0);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    @Test
    @DisplayName("搜索第 21 次返回 429：信封 + Retry-After 正确，前 20 次放行")
    void searchLimitAllows20Then429() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        when(rateLimitService.tryAcquire(anyString(), eq(20), any(Duration.class)))
                .thenAnswer(inv -> counter.incrementAndGet() <= 20);
        when(rateLimitService.remainingSeconds(anyString(), anyLong())).thenReturn(37L);

        MockHttpServletResponse last = null;
        for (int i = 0; i < 21; i++) {
            last = new MockHttpServletResponse();
            filter.doFilter(get("/api/songs/search"), last, chain);
        }

        assertEquals(20, chainCalls.get(), "前 20 次应放行到业务链");
        assertEquals(429, last.getStatus());
        assertEquals("37", last.getHeader("Retry-After"));
        String body = last.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":429"), body);
        assertTrue(body.contains("请求过于频繁"), body);
        assertTrue(body.contains("\"data\":null"), body);
    }

    @Test
    @DisplayName("音频流用独立的 60 次/分钟额度")
    void streamUsesItsOwnLimit() throws Exception {
        when(rateLimitService.tryAcquire(anyString(), eq(60), any(Duration.class))).thenReturn(true);

        filter.doFilter(get("/api/songs/stream"), new MockHttpServletResponse(), chain);

        verify(rateLimitService).tryAcquire(eq("stream:ip:10.0.0.1"), eq(60), any(Duration.class));
        assertEquals(1, chainCalls.get());
    }

    @Test
    @DisplayName("非贵端点完全不碰限流器（banner/lyric 等不受影响）")
    void otherEndpointsAreNotLimited() throws Exception {
        filter.doFilter(get("/api/songs/banner"), new MockHttpServletResponse(), chain);

        verifyNoInteractions(rateLimitService);
        assertEquals(1, chainCalls.get());
    }

    @Test
    @DisplayName("限流组件异常时 fail-open：放行并打 WARN，绝不把正常请求打成 5xx")
    void failsOpenOnRedisError() throws Exception {
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(get("/api/songs/search"), response, chain);

        assertEquals(1, chainCalls.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("开关关闭时完全不限流")
    void disabledMeansNoLimit() throws Exception {
        properties.setEnabled(false);

        for (int i = 0; i < 100; i++) {
            filter.doFilter(get("/api/songs/search"), new MockHttpServletResponse(), chain);
        }

        verifyNoInteractions(rateLimitService);
        assertEquals(100, chainCalls.get());
    }

    @Test
    @DisplayName("非 GET 请求不计数（搜索/流均为 GET 语义）")
    void nonGetBypassesLimiter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/songs/search");
        request.setRequestURI("/api/songs/search");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(rateLimitService);
        assertEquals(1, chainCalls.get());
    }

    @Test
    @DisplayName("已登录用户按 userId 计数，而不是按 IP")
    void authenticatedUserBucketedByUserId() throws Exception {
        CustomUserDetails details = mock(CustomUserDetails.class);
        when(details.getUserId()).thenReturn(42L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, List.of()));
        when(rateLimitService.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);

        filter.doFilter(get("/api/songs/search"), new MockHttpServletResponse(), chain);

        verify(rateLimitService).tryAcquire(eq("search:user:42"), eq(20), any(Duration.class));
    }

    @Test
    @DisplayName("尾斜杠不影响端点匹配（/api/songs/search/ 同样受限）")
    void trailingSlashStillLimited() throws Exception {
        when(rateLimitService.tryAcquire(anyString(), eq(20), any(Duration.class))).thenReturn(true);

        filter.doFilter(get("/api/songs/search/"), new MockHttpServletResponse(), chain);

        verify(rateLimitService).tryAcquire(eq("search:ip:10.0.0.1"), eq(20), any(Duration.class));
    }
}
