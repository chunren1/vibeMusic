package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link LoginAttemptGuard} 单元测试（纯 Mockito，不启动 Spring / 不连 Redis）。
 * <p>覆盖：锁定阈值与 429 文案、成功清空、fail-open、用户名哈希（中文/特殊字符不进键名）。
 */
@DisplayName("按账号登录失败锁定")
class LoginAttemptGuardTest {

    private static final String USERNAME = "testuser";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOps = ops;
        when(redis.opsForValue()).thenReturn(ops);
        guard = new LoginAttemptGuard(redis);
    }

    private static String expectedKey(String username) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(username.getBytes(StandardCharsets.UTF_8));
            return "user:auth:login:fail:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("key 与阈值")
    class KeyAndThreshold {

        @Test
        @DisplayName("键 = user:auth:login:fail: + sha256(username)，不含原始用户名")
        void shouldHashUsernameIntoKey() {
            guard.recordFailure("张三_2026");

            verify(redis).execute(any(RedisScript.class), eq(List.of(expectedKey("张三_2026"))), any());
            verify(redis, never()).execute(any(RedisScript.class),
                    argThat(keys -> keys.contains("张三_2026")), any());
        }

        @Test
        @DisplayName("阈值常量：5 次失败 / 15 分钟")
        void shouldExposeExpectedThresholds() {
            assertEquals(5, LoginAttemptGuard.MAX_FAILURES);
            assertEquals(15, LoginAttemptGuard.LOCK_MINUTES);
        }
    }

    @Nested
    @DisplayName("checkLocked 锁定判定")
    class CheckLocked {

        @Test
        @DisplayName("无计数 → 放行")
        void shouldPassWhenNoCounter() {
            when(valueOps.get(expectedKey(USERNAME))).thenReturn(null);

            assertDoesNotThrow(() -> guard.checkLocked(USERNAME));
        }

        @Test
        @DisplayName("失败 4 次（未达上限）→ 仍放行")
        void shouldPassBelowThreshold() {
            when(valueOps.get(expectedKey(USERNAME))).thenReturn("4");

            assertDoesNotThrow(() -> guard.checkLocked(USERNAME));
        }

        @Test
        @DisplayName("失败 5 次 → 第 6 次尝试 429，消息含剩余分钟数")
        void shouldRejectWhenLocked() {
            when(valueOps.get(expectedKey(USERNAME))).thenReturn("5");
            when(redis.getExpire(eq(expectedKey(USERNAME)), eq(TimeUnit.SECONDS))).thenReturn(600L);

            BusinessException ex = assertThrows(BusinessException.class, () -> guard.checkLocked(USERNAME));

            assertEquals(429, ex.getCode());
            assertTrue(ex.getMessage().contains("10 分钟"), ex.getMessage());
        }

        @Test
        @DisplayName("TTL 缺失/≤0 → 用默认 15 分钟提示（不因读取失败少报锁定时长）")
        void shouldFallbackToDefaultWindowWhenTtlMissing() {
            when(valueOps.get(expectedKey(USERNAME))).thenReturn("5");
            when(redis.getExpire(eq(expectedKey(USERNAME)), eq(TimeUnit.SECONDS))).thenReturn(-1L);

            BusinessException ex = assertThrows(BusinessException.class, () -> guard.checkLocked(USERNAME));

            assertTrue(ex.getMessage().contains("15 分钟"), ex.getMessage());
        }

        @Test
        @DisplayName("剩余不足 1 分钟 → 仍提示至少 1 分钟（不显示 0）")
        void shouldRoundUpToAtLeastOneMinute() {
            when(valueOps.get(expectedKey(USERNAME))).thenReturn("5");
            when(redis.getExpire(eq(expectedKey(USERNAME)), eq(TimeUnit.SECONDS))).thenReturn(3L);

            BusinessException ex = assertThrows(BusinessException.class, () -> guard.checkLocked(USERNAME));

            assertTrue(ex.getMessage().contains("1 分钟"), ex.getMessage());
        }

        @Test
        @DisplayName("计数键被污染成非数字 → 按未锁定放行（不 500）")
        void shouldFailOpenOnCorruptedCounter() {
            when(valueOps.get(expectedKey(USERNAME))).thenReturn("not-a-number");

            assertDoesNotThrow(() -> guard.checkLocked(USERNAME));
        }

        @Test
        @DisplayName("Redis 读异常 → fail-open 放行")
        void shouldFailOpenWhenRedisDown() {
            when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> guard.checkLocked(USERNAME));
        }

        @Test
        @DisplayName("TTL 读取异常 → 仍按 429 拒绝并回落 15 分钟")
        void shouldStillRejectWhenTtlReadFails() {
            when(valueOps.get(expectedKey(USERNAME))).thenReturn("5");
            when(redis.getExpire(anyString(), any(TimeUnit.class))).thenThrow(new RuntimeException("redis down"));

            BusinessException ex = assertThrows(BusinessException.class, () -> guard.checkLocked(USERNAME));
            assertTrue(ex.getMessage().contains("15 分钟"), ex.getMessage());
        }

        @Test
        @DisplayName("null/空白用户名 → 直接放行且不碰 Redis（参数校验由调用方负责）")
        void shouldIgnoreBlankUsername() {
            assertDoesNotThrow(() -> guard.checkLocked(null));
            assertDoesNotThrow(() -> guard.checkLocked("   "));
            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("recordFailure 计数")
    class RecordFailure {

        @Test
        @DisplayName("首次失败 → INCR 且设 15 分钟 TTL（Lua 原子执行）")
        void shouldIncrementAndSetTtlAtomically() {
            guard.recordFailure(USERNAME);

            verify(redis).execute(any(RedisScript.class), eq(List.of(expectedKey(USERNAME))),
                    eq(String.valueOf(Duration.ofMinutes(15).getSeconds())));
        }

        @Test
        @DisplayName("Redis 异常 → 只 WARN 不抛（保持原 401 语义）")
        void shouldFailOpenWhenRedisDown() {
            when(redis.execute(any(RedisScript.class), anyList(), any()))
                    .thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> guard.recordFailure(USERNAME));
        }

        @Test
        @DisplayName("null/空白用户名 → 不计数")
        void shouldIgnoreBlankUsername() {
            guard.recordFailure(null);
            guard.recordFailure(" ");

            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("clear 清空计数")
    class Clear {

        @Test
        @DisplayName("登录成功 → 删除该账号的计数键")
        void shouldDeleteCounter() {
            guard.clear(USERNAME);

            verify(redis).delete(expectedKey(USERNAME));
        }

        @Test
        @DisplayName("Redis 异常 → 只 WARN 不抛（不能因清理失败让登录失败）")
        void shouldFailOpenWhenRedisDown() {
            when(redis.delete(anyString())).thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> guard.clear(USERNAME));
        }

        @Test
        @DisplayName("null/空白用户名 → 不删键")
        void shouldIgnoreBlankUsername() {
            guard.clear(null);
            guard.clear("");

            verifyNoInteractions(redis);
        }
    }
}
