package com.vibemusic.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.Result;
import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.controller.AuthController;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.UserMapper;
import com.vibemusic.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 回归测试：登出吊销必须生效，即使 Redis 写失败。
 * <p>
 * 缺陷背景（2026-10-19 修复）：logout 只把吊销状态写 Redis，写失败仅 log.warn，
 * 且 JwtAuthenticationFilter 的 isBlacklisted 在 Redis 异常时 fail-open 放行 ——
 * 结果是「Redis 抖动时已登出 token 仍可用」。修复后吊销先落本地表，读路径本地命中即拒绝。
 */
@DisplayName("登出吊销回归：Redis 写失败也不得放行已登出 token")
class TokenRevocationRegressionTest {

    private static final String TOKEN = "tok-logged-out";

    private JwtUtils jwtUtils;
    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtils = mock(JwtUtils.class);
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOps = ops;
        when(redis.opsForValue()).thenReturn(ops);
        // 用真实 service + mock Redis 装配（黑名单逻辑已收敛到 TokenBlacklistService）
        tokenBlacklistService = new TokenBlacklistService(redis, jwtUtils, "fail-open");
    }

    /** 模拟 Redis 完全不可用：读写皆抛异常 */
    private void simulateRedisDown() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Result<String> logout(String token) {
        AuthController controller = new AuthController(mock(AuthenticationManager.class),
                mock(UserService.class), jwtUtils, tokenBlacklistService);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/logout");
        req.addHeader("Authorization", "Bearer " + token);
        return controller.logout(req, new MockHttpServletResponse());
    }

    /** 让 token 看起来完全合法：可用于认证一个真实存在的用户 */
    private void givenValidTokenForExistingUser(String token, long userId) {
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.isRefreshToken(token)).thenReturn(false);
        when(jwtUtils.getUserIdFromToken(token)).thenReturn(String.valueOf(userId));
        when(userMapper.selectById(userId))
                .thenReturn(User.builder().id(userId).username("u" + userId).password("p").enabled(true).build());
    }

    /** 用 token 打受保护端点（收藏列表），返回是否被认证 */
    private Authentication invokeProtectedEndpoint(String token) throws Exception {
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(jwtUtils, userMapper, new ObjectMapper(), redis, tokenBlacklistService);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/favorites/list");
        req.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> { });
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("登出时 Redis 写失败 → 同一 token 再打受保护端点必须不认证（修复前：仍被认证）")
    void shouldNotAuthenticateAfterLogoutWhenRedisWriteFails() throws Exception {
        simulateRedisDown();
        when(jwtUtils.getExpirationFromToken(TOKEN)).thenReturn(System.currentTimeMillis() + 60_000);

        // 1) 登出：Redis 写抛异常，但必须仍然吊销成功
        assertEquals(200, logout(TOKEN).getCode(), "登出必须保持幂等：仍返回 200/已退出");
        assertEquals(1, tokenBlacklistService.getDegradedWriteCount(), "Redis 写降级应被计数（可观测）");

        // 2) 同一 token 打受保护端点 → 不得认证
        givenValidTokenForExistingUser(TOKEN, 7L);

        assertNull(invokeProtectedEndpoint(TOKEN), "已登出的 token 不得再认证（吊销静默失效 = 安全缺陷）");
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("登出时 Redis 正常 → 本地表 + Redis 双写，token 同样不认证")
    void shouldNotAuthenticateAfterLogoutWhenRedisHealthy() throws Exception {
        when(jwtUtils.getExpirationFromToken(TOKEN)).thenReturn(System.currentTimeMillis() + 60_000);

        assertEquals(200, logout(TOKEN).getCode());
        assertEquals(0, tokenBlacklistService.getDegradedWriteCount());
        verify(valueOps).set(startsWith("token:blacklist:"), eq("1"), any(Duration.class));

        givenValidTokenForExistingUser(TOKEN, 7L);

        assertNull(invokeProtectedEndpoint(TOKEN));
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("登出后 Redis 抖动 + 本地项过期 → 本地表不再兜底（退化到「未知状态」降级语义）")
    void shouldRecheckRedisWhenLocalEntryExpires() throws Exception {
        // 用可控时钟推进本地项过期，避免 sleep
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T00:00:00Z"));
        tokenBlacklistService = new TokenBlacklistService(redis, jwtUtils, "fail-open", clock, 10_000);
        simulateRedisDown();
        when(jwtUtils.getExpirationFromToken(TOKEN)).thenReturn(clock.millis() + 1);

        assertEquals(200, logout(TOKEN).getCode());
        clock.advanceMillis(10); // 本地项过期（token 本身也已过寿命）

        givenValidTokenForExistingUser(TOKEN, 7L);

        // 本地项已过期 + Redis 不可用 + fail-open → 未知状态放行（有意识保留的降级）
        assertNotNull(invokeProtectedEndpoint(TOKEN));
        assertTrue(tokenBlacklistService.getDegradedReadCount() >= 1);
    }
}
