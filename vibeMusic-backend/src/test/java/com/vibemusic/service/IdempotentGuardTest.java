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
 * IdempotentGuard 幂等性守卫测试
 * <p>
 * 纯 Mockito 单元测试，Mock Redis，验证防重放逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotentGuard 幂等性守卫测试")
class IdempotentGuardTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private IdempotentGuard idempotentGuard;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("首次请求应返回 true（获取到锁）")
    void shouldReturnTrueForFirstRequest() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        boolean result = idempotentGuard.tryAcquire("req-uuid-001");

        assertTrue(result);
        verify(valueOps).setIfAbsent(eq("idempotent:req-uuid-001"), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("重复请求应返回 false（被拦截）")
    void shouldReturnFalseForDuplicateRequest() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        boolean result = idempotentGuard.tryAcquire("req-uuid-001");

        assertFalse(result);
    }

    @Test
    @DisplayName("null Request-Id 应放行返回 true")
    void shouldReturnTrueForNullRequestId() {
        boolean result = idempotentGuard.tryAcquire(null);

        assertTrue(result);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("空白 Request-Id 应放行返回 true")
    void shouldReturnTrueForBlankRequestId() {
        boolean result = idempotentGuard.tryAcquire("   ");

        assertTrue(result);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 返回 null 时应放行（容错降级）")
    void shouldReturnTrueWhenRedisReturnsNull() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        boolean result = idempotentGuard.tryAcquire("req-uuid-002");

        assertTrue(result);
    }

    @Test
    @DisplayName("不同 Request-Id 应各自独立获取锁")
    void shouldHandleDifferentRequestIdsIndependently() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        boolean first = idempotentGuard.tryAcquire("req-A");
        boolean second = idempotentGuard.tryAcquire("req-B");

        assertTrue(first);
        assertTrue(second);
        verify(valueOps, times(2)).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }
}
