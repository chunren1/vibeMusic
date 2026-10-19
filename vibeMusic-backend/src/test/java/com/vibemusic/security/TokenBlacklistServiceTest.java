package com.vibemusic.security;

import com.vibemusic.common.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TokenBlacklistService 纯单元测试（Mockito + 可控时钟，无 sleep）。
 * <p>
 * 覆盖：TTL 严格等于 token 剩余寿命（含 7 天 refresh token，不得截断）、已过期不吊销、
 * 本地表 fail-closed 语义、Redis 读降级 fail-open / fail-closed 双配置、容量淘汰与计数器、
 * Redis 写失败降级计数。
 */
@DisplayName("TokenBlacklistService 本地表 + Redis 双层")
class TokenBlacklistServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");

    private JwtUtils jwtUtils;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jwtUtils = mock(JwtUtils.class);
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOps = ops;
        when(redis.opsForValue()).thenReturn(ops);
        clock = new MutableClock(T0);
    }

    private TokenBlacklistService service(String degraded, int maxEntries) {
        return new TokenBlacklistService(redis, jwtUtils, degraded, clock, maxEntries);
    }

    /** 让 token 在「距当前时钟 remainMs」后过期 */
    private void givenRemaining(String token, long remainMs) {
        when(jwtUtils.getExpirationFromToken(token)).thenReturn(clock.millis() + remainMs);
    }

    @Nested
    @DisplayName("写路径：TTL 与本地表")
    class WritePath {

        @Test
        @DisplayName("TTL 严格等于 token 剩余寿命：refresh token 7 天不得被截断成 15 分钟")
        void shouldUseExactRemainingLifetimeAsTtl() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("refresh-tok", Duration.ofDays(7).toMillis());

            svc.revoke("refresh-tok");

            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(startsWith(TokenBlacklistService.TOKEN_BLACKLIST_PREFIX), eq("1"), ttl.capture());
            assertEquals(Duration.ofDays(7), ttl.getValue(), "TTL 必须等于剩余寿命，截断会留出 6 天 23 小时的洞");
            assertNotEquals(Duration.ofMinutes(15), ttl.getValue());
            assertTrue(svc.isRevoked("refresh-tok"));
        }

        @Test
        @DisplayName("access token 15 分钟剩余 → TTL 15 分钟（边界精确）")
        void shouldUseExactTtlForAccessToken() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("access-tok", Duration.ofMinutes(15).toMillis());

            svc.revoke("access-tok");

            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(anyString(), eq("1"), ttl.capture());
            assertEquals(Duration.ofMinutes(15), ttl.getValue());
        }

        @Test
        @DisplayName("token 剩余 ≤ 0 → 既不写 Redis 也不进本地表")
        void shouldSkipRevokeWhenTokenAlreadyExpired() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("dead", -1);

            svc.revoke("dead");

            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
            assertEquals(0, svc.localEntryCount());
            assertFalse(svc.isRevoked("dead"));
        }

        @Test
        @DisplayName("解析过期时间抛异常 → 吞掉，不写任何状态")
        void shouldSwallowExpirationParsingFailure() {
            TokenBlacklistService svc = service("fail-open", 100);
            when(jwtUtils.getExpirationFromToken("broken")).thenThrow(new IllegalArgumentException("bad token"));

            assertDoesNotThrow(() -> svc.revoke("broken"));

            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
            assertEquals(0, svc.localEntryCount());
        }

        @Test
        @DisplayName("仅吊销传入的 token，不影响其它 token")
        void shouldOnlyRevokeGivenToken() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("tok-a", Duration.ofMinutes(10).toMillis());

            svc.revoke("tok-a");

            assertTrue(svc.isRevoked("tok-a"));
            assertFalse(svc.isRevoked("tok-b"), "未吊销的 token 不得被误判（Redis 未命中）");
        }

        @Test
        @DisplayName("Redis 写失败 → 本地表仍生效 + 降级计数器 +1")
        void shouldKeepLocalRevocationWhenRedisWriteFails() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("tok", Duration.ofMinutes(5).toMillis());
            doThrow(new RuntimeException("redis down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));

            svc.revoke("tok");

            assertEquals(1, svc.getDegradedWriteCount());
            assertTrue(svc.isRevoked("tok"), "Redis 写失败时本地表必须兜底（否则登出静默失效）");
        }

        @Test
        @DisplayName("SHA-256 不可用 → 摘要降级 hashCode，依然能吊销与命中")
        void shouldFallbackToHashCodeWhenSha256Unavailable() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("tok", Duration.ofMinutes(5).toMillis());

            try (var digest = mockStatic(java.security.MessageDigest.class)) {
                digest.when(() -> java.security.MessageDigest.getInstance("SHA-256"))
                        .thenThrow(new java.security.NoSuchAlgorithmException("no provider"));

                svc.revoke("tok");

                verify(valueOps).set(startsWith(TokenBlacklistService.TOKEN_BLACKLIST_PREFIX), eq("1"), any(Duration.class));
                assertTrue(svc.isRevoked("tok"));
            }
        }
    }

    @Nested
    @DisplayName("读路径：本地命中 / Redis 降级配置")
    class ReadPath {

        @Test
        @DisplayName("本地命中且未过期 → true，且不访问 Redis（Redis 挂了也认）")
        void shouldReturnTrueFromLocalTableWithoutTouchingRedis() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("tok", Duration.ofMinutes(10).toMillis());
            svc.revoke("tok");
            reset(redis); // 模拟 Redis 此后彻底不可用（任何交互都会 NPE）

            assertTrue(svc.isRevoked("tok"));
        }

        @Test
        @DisplayName("本地项到期后回落 Redis：Redis 命中 → true")
        void shouldFallBackToRedisAfterLocalEntryExpires() {
            TokenBlacklistService svc = service("fail-open", 100);
            givenRemaining("tok", Duration.ofMinutes(10).toMillis());
            svc.revoke("tok");
            clock.advanceMillis(Duration.ofMinutes(10).toMillis() + 1); // 本地项过期（token 同时过期）
            when(redis.hasKey(anyString())).thenReturn(true);

            assertTrue(svc.isRevoked("tok"));
        }

        @Test
        @DisplayName("Redis 读抛异常 + 默认 fail-open → 未知状态放行，读降级计数器 +1")
        void shouldFailOpenWhenRedisReadFails() {
            TokenBlacklistService svc = service("fail-open", 100);
            when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

            assertFalse(svc.isRevoked("unknown-tok"));
            assertEquals(1, svc.getDegradedReadCount());
        }

        @Test
        @DisplayName("Redis 读抛异常 + fail-closed 配置 → 未知状态拒绝（为多实例预留的开关）")
        void shouldFailClosedWhenConfigured() {
            TokenBlacklistService svc = service("fail-closed", 100);
            when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

            assertTrue(svc.isRevoked("unknown-tok"));
            assertEquals(1, svc.getDegradedReadCount());
        }

        @Test
        @DisplayName("本地命中优先于 fail-closed 配置：即使配置 fail-closed 也有明确答案（true）")
        void shouldPreferLocalHitOverConfiguration() {
            TokenBlacklistService svc = service("fail-closed", 100);
            givenRemaining("tok", Duration.ofMinutes(10).toMillis());
            svc.revoke("tok");
            when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

            assertTrue(svc.isRevoked("tok"));
            assertEquals(0, svc.getDegradedReadCount());
        }

        @Test
        @DisplayName("空 token / null → 一律 false，不触碰 Redis")
        void shouldReturnFalseForBlankToken() {
            TokenBlacklistService svc = service("fail-open", 100);

            assertFalse(svc.isRevoked(null));
            assertFalse(svc.isRevoked(""));
            assertFalse(svc.isRevoked("   "));
            svc.revoke(null);
            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("容量控制：清扫过期项 + 淘汰最早过期项")
    class Capacity {

        @Test
        @DisplayName("超上限：淘汰最早过期项，被淘汰项不再被本地拦截 + 淘汰计数 +1")
        void shouldEvictEarliestExpiringEntry() {
            TokenBlacklistService svc = service("fail-open", 2);
            givenRemaining("tok-a", Duration.ofMinutes(10).toMillis());
            givenRemaining("tok-b", Duration.ofMinutes(20).toMillis());
            givenRemaining("tok-c", Duration.ofMinutes(30).toMillis());

            svc.revoke("tok-a");
            svc.revoke("tok-b");
            svc.revoke("tok-c"); // 第 3 条触发淘汰：最早过期的 tok-a 出局

            assertEquals(2, svc.localEntryCount());
            assertEquals(1, svc.getEvictedCount());
            assertFalse(svc.isRevoked("tok-a"), "被淘汰项不再拦截（该条 token 吊销安全性下降，已 WARN + 计数）");
            assertTrue(svc.isRevoked("tok-b"));
            assertTrue(svc.isRevoked("tok-c"));
        }

        @Test
        @DisplayName("超上限时优先清扫过期项，未触发淘汰计数")
        void shouldSweepExpiredEntriesBeforeEvicting() {
            TokenBlacklistService svc = service("fail-open", 2);
            givenRemaining("tok-old", Duration.ofMinutes(1).toMillis());
            svc.revoke("tok-old");

            clock.advanceMillis(Duration.ofMinutes(2).toMillis()); // tok-old 本地项过期
            givenRemaining("tok-b", Duration.ofMinutes(10).toMillis());
            givenRemaining("tok-c", Duration.ofMinutes(20).toMillis());
            svc.revoke("tok-b");
            svc.revoke("tok-c");

            assertEquals(0, svc.getEvictedCount(), "过期项清扫已足够，不应牺牲有效吊销项");
            assertEquals(2, svc.localEntryCount());
            assertTrue(svc.isRevoked("tok-b"));
            assertTrue(svc.isRevoked("tok-c"));
        }

        @Test
        @DisplayName("默认容量上限 10000：写入第 10001 条时淘汰最早过期项")
        void shouldHonorDefaultCapacity() {
            assertEquals(10_000, TokenBlacklistService.DEFAULT_MAX_ENTRIES);
            TokenBlacklistService svc = service("fail-open", TokenBlacklistService.DEFAULT_MAX_ENTRIES);
            java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
            when(jwtUtils.getExpirationFromToken(anyString()))
                    .thenAnswer(inv -> clock.millis() + Duration.ofMinutes(1).toMillis() + seq.incrementAndGet());

            for (int i = 0; i <= TokenBlacklistService.DEFAULT_MAX_ENTRIES; i++) {
                svc.revoke("tok-" + i);
            }

            assertEquals(TokenBlacklistService.DEFAULT_MAX_ENTRIES, svc.localEntryCount(), "本地表不得超过上限");
            assertEquals(1, svc.getEvictedCount());
            assertFalse(svc.isRevoked("tok-0"), "最早过期项被淘汰");
            assertTrue(svc.isRevoked("tok-10000"), "最新写入项不受影响");
        }

        @Test
        @DisplayName("可注入时钟：本地项过期判定精确到毫秒（无 sleep）")
        void shouldExpireLocalEntryExactlyAtDeadline() {
            TokenBlacklistService svc = service("fail-open", 10);
            givenRemaining("tok", 1_000);
            svc.revoke("tok");

            assertTrue(svc.isRevoked("tok"));
            clock.advanceMillis(999);
            assertTrue(svc.isRevoked("tok"), "未到截止时刻仍应拦截");
            clock.advanceMillis(1);
            when(redis.hasKey(anyString())).thenReturn(false);
            assertFalse(svc.isRevoked("tok"), "到达截止时刻本地项失效，且 Redis 无记录 → 放行");
        }
    }

    @Test
    @DisplayName("生产构造器（3 参）默认 fail-open：Redis 读异常 → 放行未知状态")
    void productionConstructorShouldDefaultToFailOpen() {
        TokenBlacklistService svc = new TokenBlacklistService(redis, jwtUtils, "fail-open");
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(svc.isRevoked("unknown-tok"));
        assertEquals(1, svc.getDegradedReadCount());
    }
}
