package com.vibemusic.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JwtAuthenticationFilter 纯单元测试（Mockito，不启动 Spring 上下文）。
 * <p>
 * 重点覆盖失败/异常路径：无 token、坏 token、黑名单命中、refresh token 拒访、
 * Redis 不可用降级、缓存脏数据回退 DB、userId 不可解析、缓存回写失败。
 */
@DisplayName("JwtAuthenticationFilter 认证分支")
class JwtAuthenticationFilterTest {

    private JwtUtils jwtUtils;
    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TokenBlacklistService tokenBlacklistService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtils = mock(JwtUtils.class);
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOps = ops;
        when(redis.opsForValue()).thenReturn(valueOps);
        // 黑名单逻辑已收敛到 TokenBlacklistService（2026-10-19），过滤器通过它读写黑名单；
        // 这里用真实 service + mock Redis 装配，保持“过滤器行为”测试语义不变。
        tokenBlacklistService = new TokenBlacklistService(redis, jwtUtils, "fail-open");
        filter = new JwtAuthenticationFilter(jwtUtils, userMapper, new ObjectMapper(), redis, tokenBlacklistService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 辅助 ----------

    private MockHttpServletRequest tokenRequest(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    /** 调用过滤器，返回是否真正放行了请求链 */
    private boolean invoke(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chained.set(true);
        filter.doFilter(request, response, chain);
        return chained.get();
    }

    private static User user(long id, String nickname) {
        return User.builder().id(id).username("u" + id).nickname(nickname).password("p").enabled(true).build();
    }

    @Nested
    @DisplayName("无 token / 坏 token")
    class NoOrInvalidToken {

        @Test
        @DisplayName("请求完全无 token → 放行且不认证、不校验 JWT")
        void shouldPassThroughWithoutToken() throws Exception {
            assertTrue(invoke(new MockHttpServletRequest("GET", "/api/songs"), new MockHttpServletResponse()));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verifyNoInteractions(jwtUtils);
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("Bearer 头格式不合法（无前缀）→ 视作无 token 放行")
        void shouldIgnoreMalformedBearerHeader() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
            req.addHeader("Authorization", "Token abc");
            assertTrue(invoke(req, new MockHttpServletResponse()));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(jwtUtils, never()).validateToken(anyString());
        }

        @Test
        @DisplayName("token 校验失败（过期/签名坏）→ 放行且不查库、不认证")
        void shouldSkipWhenTokenInvalid() throws Exception {
            when(jwtUtils.validateToken("bad")).thenReturn(false);

            assertTrue(invoke(tokenRequest("bad"), new MockHttpServletResponse()));

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(jwtUtils, never()).getUserIdFromToken(anyString());
            verifyNoInteractions(userMapper);
            verify(redis, never()).hasKey(anyString());
        }
    }

    @Nested
    @DisplayName("拒绝路径：黑名单 / refresh token")
    class RejectPaths {

        @Test
        @DisplayName("access token 命中黑名单 → 放行但跳过认证（不查库）")
        void shouldSkipWhenBlacklisted() throws Exception {
            when(jwtUtils.validateToken("logged-out")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(true);

            assertTrue(invoke(tokenRequest("logged-out"), new MockHttpServletResponse()));

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(redis).hasKey(startsWith("token:blacklist:"));
            verify(jwtUtils, never()).getUserIdFromToken(anyString());
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("黑名单 Redis 抛异常 + token 未在本地吊销表 → 未知状态降级放行、继续认证")
        void shouldFailOpenWhenBlacklistRedisDownAndTokenUnknownLocally() throws Exception {
            // 2026-10-19 决策变更：登出吊销改为「本地表 + Redis」双层。
            // 本地表未命中 + Redis 不可用 = 平台对“该 token 是否已吊销”处于“未知状态”，
            // 默认 fail-open 放行以不阻塞正常请求（auth.blacklist.redis-degraded=fail-open）。
            // 注意：这已不等于“Redis 挂了就一律放行”——本地已吊销的 token 见下一条用例。
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("7");
            when(userMapper.selectById(7L)).thenReturn(user(7L, "缓存降级"));

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            verify(userMapper).selectById(7L);
            assertEquals(1, tokenBlacklistService.getDegradedReadCount(), "读降级必须可观测");
        }

        @Test
        @DisplayName("黑名单 Redis 抛异常 + token 已在本地吊销表 → 仍必须不认证（fail-closed）")
        void shouldRejectLocallyRevokedTokenWhenBlacklistRedisDown() throws Exception {
            // 2026-10-19 决策变更：登出先写本地表再写 Redis，因此“Redis 抖动时已登出 token 仍可用”
            // 的老缺陷被堵死：本地命中即拒绝，Redis 挂掉也不例外。
            // 该用例的期望与上一条相反是有意为之，不是回归。
            when(jwtUtils.validateToken("logged-out")).thenReturn(true);
            when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
            // 模拟“Redis 写也失败”的登出：本地表仍然记住吊销
            doThrow(new RuntimeException("redis down"))
                    .when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
            when(jwtUtils.getExpirationFromToken("logged-out"))
                    .thenReturn(System.currentTimeMillis() + 60_000);
            tokenBlacklistService.revoke("logged-out");

            assertTrue(invoke(tokenRequest("logged-out"), new MockHttpServletResponse()));

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(jwtUtils, never()).getUserIdFromToken(anyString());
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("refresh token 访问业务接口 → 放行但不认证、不查库")
        void shouldRejectRefreshTokenOnBusinessEndpoint() throws Exception {
            when(jwtUtils.validateToken("refresh-tok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(false);
            when(jwtUtils.isRefreshToken("refresh-tok")).thenReturn(true);

            assertTrue(invoke(tokenRequest("refresh-tok"), new MockHttpServletResponse()));

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(jwtUtils, never()).getUserIdFromToken(anyString());
            verifyNoInteractions(userMapper);
        }
    }

    @Nested
    @DisplayName("成功路径：缓存命中 / 回退 DB / Cookie 取 token")
    class SuccessPaths {

        @Test
        @DisplayName("Redis 缓存命中 → 直接建认证，不查 DB")
        void shouldAuthenticateFromRedisCache() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(false);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("5");
            when(valueOps.get("user:auth:5"))
                    .thenReturn("{\"id\":5,\"username\":\"cached\",\"nickname\":\"缓存用户\",\"password\":\"p\",\"enabled\":true}");

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            CustomUserDetails details = (CustomUserDetails) auth.getPrincipal();
            assertEquals(5L, details.getUserId());
            assertEquals("缓存用户", details.getNickname());
            assertInstanceOf(WebAuthenticationDetails.class, auth.getDetails());
            assertTrue(auth.isAuthenticated());
            assertTrue(details.getAuthorities().isEmpty());
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("缓存未命中 → 查 DB 并回填缓存（TTL 5min）")
        void shouldLoadFromDbAndCacheOnMiss() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(false);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("6");
            when(valueOps.get("user:auth:6")).thenReturn(null);
            when(userMapper.selectById(6L)).thenReturn(user(6L, "DB 用户"));

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            CustomUserDetails details =
                    (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertEquals("DB 用户", details.getNickname());
            verify(valueOps).set(eq("user:auth:6"), contains("\"id\":6"), eq(java.time.Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("Cookie（VIBE_TOKEN）取 token 同样可认证")
        void shouldExtractTokenFromCookie() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
            req.setCookies(new jakarta.servlet.http.Cookie("VIBE_TOKEN", "cookie-tok"));
            when(jwtUtils.validateToken("cookie-tok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(false);
            when(jwtUtils.isRefreshToken("cookie-tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("cookie-tok")).thenReturn("9");
            when(userMapper.selectById(9L)).thenReturn(user(9L, null));

            assertTrue(invoke(req, new MockHttpServletResponse()));

            CustomUserDetails details =
                    (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertEquals("u9", details.getNickname()); // nickname 为空回退 username
        }
    }

    @Nested
    @DisplayName("降级路径：缓存脏数据 / Redis 不可用 / userId 非法")
    class DegradationPaths {

        @Test
        @DisplayName("缓存 JSON 脏数据 → 回退查 DB")
        void shouldFallbackToDbOnCorruptCache() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("8");
            when(valueOps.get("user:auth:8")).thenReturn("{ 这不是 JSON");
            when(userMapper.selectById(8L)).thenReturn(user(8L, "降级"));

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            assertEquals("降级",
                    ((CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getNickname());
        }

        @Test
        @DisplayName("Redis 读缓存抛异常 → 回退查 DB 并认证成功")
        void shouldFallbackToDbWhenRedisReadFails() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("8");
            when(valueOps.get("user:auth:8")).thenThrow(new RuntimeException("redis down"));
            when(userMapper.selectById(8L)).thenReturn(user(8L, "DB 兜底"));

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            assertEquals("DB 兜底",
                    ((CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getNickname());
        }

        @Test
        @DisplayName("缓存回写失败不影响本次认证")
        void shouldAuthenticateEvenIfCacheWriteFails() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("8");
            when(valueOps.get("user:auth:8")).thenReturn(null);
            when(userMapper.selectById(8L)).thenReturn(user(8L, "写失败"));
            doThrow(new RuntimeException("redis write down"))
                    .when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            assertEquals("写失败",
                    ((CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getNickname());
        }

        @Test
        @DisplayName("DB 查无此用户 → 放行但不认证")
        void shouldNotAuthenticateWhenUserMissing() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("404");
            when(userMapper.selectById(404L)).thenReturn(null);

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));
        }

        @Test
        @DisplayName("subject 非数字（脏 token）→ NumberFormatException 被吞，放行不认证")
        void shouldSwallowNonNumericUserId() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("not-a-number");

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("getUserIdFromToken 抛异常 → 吞掉、放行不认证（2026-10-19 修复：不再冒泡成 500）")
        void shouldSwallowParserException() throws Exception {
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenThrow(new IllegalStateException("malformed claim"));

            // 2026-10-19 决策变更：该调用原先落在 try 之外，异常会冒泡到容器 → 受保护端点变 500。
            // 现移入 try，与本文件既有语义一致：解析失败 → 不认证 → 受保护端点得到 401。
            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("Redis 整体不可用（opsForValue 返回 null）→ NPE 被吞，仍回退 DB 认证")
        void shouldFallbackWhenRedisTemplateUnavailable() throws Exception {
            when(redis.opsForValue()).thenReturn(null);
            when(jwtUtils.validateToken("tok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("tok")).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("tok")).thenReturn("3");
            when(userMapper.selectById(3L)).thenReturn(user(3L, "无Redis"));

            assertTrue(invoke(tokenRequest("tok"), new MockHttpServletResponse()));

            assertEquals("无Redis",
                    ((CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getNickname());
        }
    }

    @Test
    @DisplayName("请求链异常仍向上抛出（过滤器不吞业务异常）")
    void shouldPropagateChainException() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/songs");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain exploding = (r, s) -> {
            throw new IllegalStateException("downstream boom");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(req, resp, exploding));
    }

    @Test
    @DisplayName("请求链只被调用一次（OncePerRequest 语义）")
    void shouldChainExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                calls.incrementAndGet();
            }
        });
        filter.doFilter(new MockHttpServletRequest("GET", "/api/songs"), new MockHttpServletResponse(), chain);
        assertEquals(1, calls.get());
    }
}
