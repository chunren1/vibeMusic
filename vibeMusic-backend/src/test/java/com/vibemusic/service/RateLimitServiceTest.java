package com.vibemusic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitService 限流器测试（Lua 原子操作版）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 限流器测试")
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private RateLimitService rateLimitService;

    private static final String KEY = "assistant:user:1";
    private static final int MAX = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Test
    @DisplayName("首次请求应放行（count=1）")
    void shouldAllowFirstRequest() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);
        assertTrue(result);
    }

    @Test
    @DisplayName("未超限请求应放行")
    void shouldAllowRequestsUnderLimit() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(5L);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);
        assertTrue(result);
    }

    @Test
    @DisplayName("刚好达到上限应放行（边界值）")
    void shouldAllowAtExactLimit() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn((long) MAX);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);
        assertTrue(result);
    }

    @Test
    @DisplayName("超出上限应限流")
    void shouldBlockWhenExceedingLimit() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn((long) (MAX + 1));

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);
        assertFalse(result);
    }

    @Test
    @DisplayName("Redis 返回 null 时应放行（容错降级）")
    void shouldAllowWhenRedisReturnsNull() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(null);

        boolean result = rateLimitService.tryAcquire(KEY, MAX, WINDOW);
        assertTrue(result);
    }

    @Test
    @DisplayName("首次请求时 Lua 脚本传入 EXPIRE 秒数")
    void shouldPassExpireSecondsToLuaScript() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L);

        rateLimitService.tryAcquire(KEY, MAX, WINDOW);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("ratelimit:" + KEY)),
                eq(String.valueOf(WINDOW.getSeconds()))
        );
    }

    @Test
    @DisplayName("不同用户限流应独立计数")
    void shouldCountDifferentUsersIndependently() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L);

        rateLimitService.tryAcquire("assistant:user:1", MAX, WINDOW);
        rateLimitService.tryAcquire("assistant:user:2", MAX, WINDOW);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("ratelimit:assistant:user:1")),
                anyString()
        );
        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("ratelimit:assistant:user:2")),
                anyString()
        );
    }
}
