package com.vibemusic.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitService 限流器测试
 * <p>
 * 纯 Mockito 单元测试，Mock Redis，验证滑动窗口限流逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 限流器测试")
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RateLimitService rateLimitService;

    private static final String KEY = "assistant:user:1";
    private static final int MAX = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("首次请求应放行并设置过期时间")
    void shouldAllowFirstRequestAndSetExpire() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);

        assertTrue(result);
        verify(stringRedisTemplate).expire(eq("ratelimit:" + KEY), eq(WINDOW));
    }

    @Test
    @DisplayName("未超限请求应放行")
    void shouldAllowRequestsUnderLimit() {
        when(valueOps.increment(anyString())).thenReturn(5L);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);

        assertTrue(result);
    }

    @Test
    @DisplayName("刚好达到上限应放行（边界值）")
    void shouldAllowAtExactLimit() {
        when(valueOps.increment(anyString())).thenReturn((long) MAX);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);

        assertTrue(result);
    }

    @Test
    @DisplayName("超出上限应限流")
    void shouldBlockWhenExceedingLimit() {
        when(valueOps.increment(anyString())).thenReturn((long) (MAX + 1));

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);

        assertFalse(result);
    }

    @Test
    @DisplayName("Redis 返回 null 时应放行（容错降级）")
    void shouldAllowWhenRedisReturnsNull() {
        when(valueOps.increment(anyString())).thenReturn(null);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);

        assertTrue(result);
    }

    @Test
    @DisplayName("非首次请求不应重复设置过期时间")
    void shouldNotSetExpireOnSubsequentRequests() {
        when(valueOps.increment(anyString())).thenReturn(2L);

        rateLimitService.tryAcquire(KEY, MAX, WINDOW);

        verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("不同用户限流应独立计数")
    void shouldCountDifferentUsersIndependently() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        rateLimitService.tryAcquire("assistant:user:1", MAX, WINDOW);
        rateLimitService.tryAcquire("assistant:user:2", MAX, WINDOW);

        verify(valueOps).increment("ratelimit:assistant:user:1");
        verify(valueOps).increment("ratelimit:assistant:user:2");
    }
}
