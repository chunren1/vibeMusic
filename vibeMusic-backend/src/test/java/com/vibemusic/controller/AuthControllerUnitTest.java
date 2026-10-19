package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.dto.AuthRequest;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.security.TokenBlacklistService;
import com.vibemusic.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthController 纯单元测试（Mockito 直调，不启动 Spring）。
 * <p>
 * 与集成测试 AuthControllerTest 互补：这里覆盖 Bean Validation 之前的
 * 手工校验分支、未登录分支、Cookie Secure 判定、黑名单写入失败降级、
 * refresh 全链路失败分支。
 * <p>
 * 2026-10-19 重构说明：黑名单逻辑已收敛到 {@link TokenBlacklistService}，本测试改为
 * 注入「真实 service + mock StringRedisTemplate」，原先对 JsonCacheService 的黑名单断言
 * 等价平移为对 Redis 写入的断言，覆盖强度不变（TTL 精度/容量等专项在 TokenBlacklistServiceTest）。
 */
@DisplayName("AuthController 分支单元测试")
class AuthControllerUnitTest {

    private AuthenticationManager authenticationManager;
    private UserService userService;
    private JwtUtils jwtUtils;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TokenBlacklistService tokenBlacklistService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        userService = mock(UserService.class);
        jwtUtils = mock(JwtUtils.class);
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOps = ops;
        when(redis.opsForValue()).thenReturn(ops);
        tokenBlacklistService = new TokenBlacklistService(redis, jwtUtils, "fail-open");
        controller = new AuthController(authenticationManager, userService, jwtUtils, tokenBlacklistService);
        SecurityContextHolder.clearContext();
        when(jwtUtils.generateAccessToken(anyString())).thenReturn("access-token");
        when(jwtUtils.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(jwtUtils.getAccessExpiration()).thenReturn(900_000L);
        when(jwtUtils.getRefreshExpiration()).thenReturn(604_800_000L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 辅助 ----------

    private static User user() {
        return User.builder().id(1L).username("testuser").nickname("测试").password("encoded")
                .avatar("a.png").bgImage("bg.png").gender("男").birthday("2000-01-01").enabled(true).build();
    }

    private void loginAs(User u) {
        CustomUserDetails details = new CustomUserDetails(u);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private void stubSuccessfulAuthenticate(User u) {
        Authentication auth = new UsernamePasswordAuthenticationToken(new CustomUserDetails(u), null, List.of());
        when(authenticationManager.authenticate(any())).thenReturn(auth);
    }

    private static String setCookies(MockHttpServletResponse resp) {
        List<String> values = resp.getHeaders("Set-Cookie");
        return String.join("\n", values);
    }

    @Nested
    @DisplayName("POST /api/auth/register")
    class Register {

        @Test
        @DisplayName("正常注册：下发双 Cookie、返回 token 与用户信息")
        void shouldRegister() {
            stubSuccessfulAuthenticate(user());
            MockHttpServletResponse resp = new MockHttpServletResponse();

            Result<Map<String, Object>> result = controller.register(
                    new AuthRequest.RegisterRequest("newbie", "pass1234", "小新"),
                    new MockHttpServletRequest(), resp);

            assertEquals(200, result.getCode());
            assertEquals("access-token", result.getData().get("token"));
            assertEquals(1L, result.getData().get("userId"));
            // 返回体取自认证主体（AuthController 用 principal 构建），注册入参昵称只用于落库
            assertEquals("测试", result.getData().get("nickname"));
            verify(userService).register("newbie", "pass1234", "小新");
            String cookies = setCookies(resp);
            assertTrue(cookies.contains("VIBE_TOKEN=access-token"));
            assertTrue(cookies.contains("VIBE_REFRESH=refresh-token"));
            assertFalse(cookies.contains("Secure"), "http 请求不应带 Secure");
        }

        @Test
        @DisplayName("nickname 为 null → 用 username 兜底")
        void shouldFallbackNicknameToUsername() {
            stubSuccessfulAuthenticate(user());

            controller.register(new AuthRequest.RegisterRequest("newbie", "pass1234", null),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            verify(userService).register("newbie", "pass1234", "newbie");
        }

        @Test
        @DisplayName("X-Forwarded-Proto=https → Cookie 追加 Secure")
        void shouldAddSecureFlagBehindHttpsProxy() {
            stubSuccessfulAuthenticate(user());
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-Forwarded-Proto", "https");
            MockHttpServletResponse resp = new MockHttpServletResponse();

            controller.register(new AuthRequest.RegisterRequest("newbie", "pass1234", null), req, resp);

            assertTrue(setCookies(resp).contains("; Secure"));
        }

        @Test
        @DisplayName("request.isSecure() 为真 → Cookie 追加 Secure")
        void shouldAddSecureFlagWhenRequestSecure() {
            stubSuccessfulAuthenticate(user());
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setSecure(true);
            MockHttpServletResponse resp = new MockHttpServletResponse();

            controller.register(new AuthRequest.RegisterRequest("newbie", "pass1234", null), req, resp);

            assertTrue(setCookies(resp).contains("; Secure"));
        }

        @Test
        @DisplayName("username 为 null → 手工校验拦截，不落库")
        void shouldRejectNullUsername() {
            Result<Map<String, Object>> result = controller.register(
                    new AuthRequest.RegisterRequest(null, "pass1234", null),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            assertEquals("用户名不能为空", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("username 全空白 → 手工校验拦截")
        void shouldRejectBlankUsername() {
            Result<Map<String, Object>> result = controller.register(
                    new AuthRequest.RegisterRequest("   ", "pass1234", null),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("password 为 null → 返回“密码至少8位”")
        void shouldRejectNullPassword() {
            Result<Map<String, Object>> result = controller.register(
                    new AuthRequest.RegisterRequest("newbie", null, null),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            assertEquals("密码至少8位", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("password 短于 8 位 → 手工校验拦截")
        void shouldRejectShortPassword() {
            Result<Map<String, Object>> result = controller.register(
                    new AuthRequest.RegisterRequest("newbie", "1234567", null),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            assertEquals("密码至少8位", result.getMessage());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("正常登录：认证成功 + 双 Cookie + token")
        void shouldLogin() {
            stubSuccessfulAuthenticate(user());
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockHttpServletRequest req = new MockHttpServletRequest();

            Result<Map<String, Object>> result = controller.login(
                    new AuthRequest.LoginRequest("testuser", "pass1234"), req, resp);

            assertEquals(200, result.getCode());
            assertEquals("testuser", result.getData().get("username"));
            assertEquals("access-token", result.getData().get("token"));
            assertTrue(setCookies(resp).contains("VIBE_TOKEN=access-token"));
        }

        @Test
        @DisplayName("username 为 null → 手工校验返回错误")
        void shouldRejectNullUsername() {
            Result<Map<String, Object>> result = controller.login(
                    new AuthRequest.LoginRequest(null, "pass1234"),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            assertEquals("用户名和密码不能为空", result.getMessage());
            verifyNoInteractions(authenticationManager);
        }

        @Test
        @DisplayName("password 为 null → 手工校验返回错误")
        void shouldRejectNullPassword() {
            Result<Map<String, Object>> result = controller.login(
                    new AuthRequest.LoginRequest("testuser", null),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            verifyNoInteractions(authenticationManager);
        }

        @Test
        @DisplayName("username 全空白（trim 后为空）→ 格式不正确")
        void shouldRejectBlankUsernameAfterTrim() {
            Result<Map<String, Object>> result = controller.login(
                    new AuthRequest.LoginRequest("   ", "pass1234"),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            assertEquals("用户名格式不正确", result.getMessage());
        }

        @Test
        @DisplayName("username 超 30 字符 → 格式不正确")
        void shouldRejectOverlongUsernameAfterTrim() {
            Result<Map<String, Object>> result = controller.login(
                    new AuthRequest.LoginRequest("u".repeat(31), "pass1234"),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(400, result.getCode());
            assertEquals("用户名格式不正确", result.getMessage());
        }

        @Test
        @DisplayName("username 前后空格被 trim 后参与认证")
        void shouldTrimUsername() {
            stubSuccessfulAuthenticate(user());

            controller.login(new AuthRequest.LoginRequest("  testuser  ", "pass1234"),
                    new MockHttpServletRequest(), new MockHttpServletResponse());

            verify(authenticationManager).authenticate(argThat(a ->
                    "testuser".equals(((UsernamePasswordAuthenticationToken) a).getPrincipal())));
        }

        @Test
        @DisplayName("认证失败异常向上抛出（由全局异常处理器兜底）")
        void shouldPropagateBadCredentials() {
            when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

            assertThrows(BadCredentialsException.class, () -> controller.login(
                    new AuthRequest.LoginRequest("testuser", "wrong"),
                    new MockHttpServletRequest(), new MockHttpServletResponse()));
        }
    }

    @Nested
    @DisplayName("GET /api/auth/health 与 /me")
    class HealthAndMe {

        @Test
        @DisplayName("health 回显远端地址并返回 OK")
        void shouldReturnHealth() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setRemoteAddr("10.1.2.3");

            Result<String> result = controller.health(req);

            assertEquals(200, result.getCode());
            assertTrue(result.getData().startsWith("OK - 10.1.2.3 - "));
        }

        @Test
        @DisplayName("未登录访问 /me → 401")
        void shouldRejectUnauthenticatedMe() {
            Result<Map<String, Object>> result = controller.me();

            assertEquals(401, result.getCode());
            assertEquals("未登录", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("已登录访问 /me → 返回个人信息")
        void shouldReturnMe() {
            loginAs(user());
            when(userService.findById(1L)).thenReturn(user());

            Result<Map<String, Object>> result = controller.me();

            assertEquals(200, result.getCode());
            assertEquals(1L, result.getData().get("userId"));
            assertEquals("testuser", result.getData().get("username"));
            assertEquals("测试", result.getData().get("nickname"));
            assertEquals("2000-01-01", result.getData().get("birthday"));
        }

        @Test
        @DisplayName("已登录但 DB 无此人 → BusinessException 向上抛出")
        void shouldPropagateWhenUserGone() {
            loginAs(user());
            when(userService.findById(1L)).thenThrow(new BusinessException(404, "用户不存在"));

            assertThrows(BusinessException.class, () -> controller.me());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/change-password")
    class ChangePassword {

        @Test
        @DisplayName("未登录 → 401")
        void shouldRejectWhenNotLoggedIn() {
            Result<String> result = controller.changePassword(Map.of("oldPassword", "old12345", "newPassword", "new12345"));

            assertEquals(401, result.getCode());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("新旧密码缺一 → 参数不能为空")
        void shouldRejectMissingParams() {
            loginAs(user());

            Result<String> result = controller.changePassword(Map.of("oldPassword", "old12345"));

            assertEquals(400, result.getCode());
            assertEquals("参数不能为空", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("新密码短于 8 位 → 拒绝")
        void shouldRejectShortNewPassword() {
            loginAs(user());

            Result<String> result = controller.changePassword(Map.of("oldPassword", "old12345", "newPassword", "1234567"));

            assertEquals(400, result.getCode());
            assertEquals("新密码至少8位", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("oldPassword 为 null（只传新密码）→ 参数不能为空")
        void shouldRejectMissingOldPassword() {
            loginAs(user());

            Result<String> result = controller.changePassword(Map.of("newPassword", "new12345"));

            assertEquals(400, result.getCode());
            assertEquals("参数不能为空", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("正常修改 → 调用 service 并返回成功")
        void shouldChangePassword() {
            loginAs(user());

            Result<String> result = controller.changePassword(Map.of("oldPassword", "old12345", "newPassword", "new12345"));

            assertEquals(200, result.getCode());
            assertEquals("密码修改成功", result.getData());
            verify(userService).changePassword(1L, "old12345", "new12345");
        }

        @Test
        @DisplayName("service 原密码校验失败 → 异常向上抛出")
        void shouldPropagateWrongOldPassword() {
            loginAs(user());
            doThrow(new BusinessException(400, "原密码错误"))
                    .when(userService).changePassword(eq(1L), anyString(), anyString());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> controller.changePassword(Map.of("oldPassword", "bad", "newPassword", "new12345")));
            assertEquals(400, ex.getCode());
        }
    }

    @Nested
    @DisplayName("PUT /api/auth/profile")
    class UpdateProfile {

        @Test
        @DisplayName("未登录 → 401")
        void shouldRejectWhenNotLoggedIn() {
            Result<Map<String, Object>> result = controller.updateProfile(Map.of("nickname", "x"));

            assertEquals(401, result.getCode());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("昵称全空白 → 拒绝")
        void shouldRejectBlankNickname() {
            loginAs(user());

            Result<Map<String, Object>> result = controller.updateProfile(Map.of("nickname", "   "));

            assertEquals(400, result.getCode());
            assertEquals("昵称长度不能超过30个字符", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("昵称超 30 字符 → 拒绝")
        void shouldRejectLongNickname() {
            loginAs(user());

            Result<Map<String, Object>> result = controller.updateProfile(Map.of("nickname", "n".repeat(31)));

            assertEquals(400, result.getCode());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("性别不在白名单 → 拒绝")
        void shouldRejectInvalidGender() {
            loginAs(user());

            Result<Map<String, Object>> result = controller.updateProfile(Map.of("gender", "其他"));

            assertEquals(400, result.getCode());
            assertEquals("性别参数无效", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("性别白名单三值均放行")
        void shouldAcceptWhitelistedGenders() {
            loginAs(user());
            when(userService.updateProfile(eq(1L), any(), any(), any())).thenReturn(user());

            for (String gender : List.of("男", "女", "保密")) {
                assertEquals(200, controller.updateProfile(Map.of("gender", gender)).getCode());
            }
        }

        @Test
        @DisplayName("生日格式非法 → 拒绝")
        void shouldRejectInvalidBirthday() {
            loginAs(user());

            Result<Map<String, Object>> result = controller.updateProfile(Map.of("birthday", "2020/01/01"));

            assertEquals(400, result.getCode());
            assertEquals("生日格式不正确（应为 YYYY-MM-DD）", result.getMessage());
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("生日空串 → 视为未填，不校验格式")
        void shouldSkipEmptyBirthday() {
            loginAs(user());
            when(userService.updateProfile(eq(1L), any(), any(), any())).thenReturn(user());

            assertEquals(200, controller.updateProfile(Map.of("birthday", "")).getCode());
        }

        @Test
        @DisplayName("三个字段全 null（空 body）→ 直接透传 service")
        void shouldAcceptAllNullFields() {
            loginAs(user());
            when(userService.updateProfile(eq(1L), any(), any(), any())).thenReturn(user());

            Result<Map<String, Object>> result = controller.updateProfile(new java.util.HashMap<>());

            assertEquals(200, result.getCode());
            verify(userService).updateProfile(1L, null, null, null);
            assertEquals("测试", result.getData().get("nickname"));
        }

        @Test
        @DisplayName("合法资料更新 → 返回更新后的数据")
        void shouldUpdateProfile() {
            loginAs(user());
            User updated = user();
            updated.setNickname("新昵称");
            updated.setGender("女");
            updated.setBirthday("1999-12-31");
            when(userService.updateProfile(1L, "新昵称", "女", "1999-12-31")).thenReturn(updated);

            Result<Map<String, Object>> result = controller.updateProfile(
                    Map.of("nickname", "新昵称", "gender", "女", "birthday", "1999-12-31"));

            assertEquals(200, result.getCode());
            assertEquals("新昵称", result.getData().get("nickname"));
            assertEquals("女", result.getData().get("gender"));
            assertEquals("1999-12-31", result.getData().get("birthday"));
        }

        @Test
        @DisplayName("实体昵称为 null → 回退 username 填充返回体")
        void shouldFallbackNicknameInResponse() {
            loginAs(user());
            User noNick = User.builder().id(2L).username("nonick").enabled(true).build();
            when(userService.updateProfile(eq(2L), any(), any(), any())).thenReturn(noNick);
            CustomUserDetails details = new CustomUserDetails(noNick);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));

            Result<Map<String, Object>> result = controller.updateProfile(Map.of("gender", "男"));

            assertEquals("nonick", result.getData().get("nickname"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/logout")
    class Logout {

        @Test
        @DisplayName("无任何 token → 依然成功并清 Cookie")
        void shouldLogoutWithoutTokens() {
            MockHttpServletResponse resp = new MockHttpServletResponse();

            Result<String> result = controller.logout(new MockHttpServletRequest(), resp);

            assertEquals(200, result.getCode());
            assertEquals("已退出", result.getData());
            verifyNoInteractions(redis);
            assertEquals(2, resp.getCookies().length, "应清除 VIBE_TOKEN / VIBE_REFRESH");
            for (Cookie c : resp.getCookies()) assertEquals(0, c.getMaxAge());
        }

        @Test
        @DisplayName("access + refresh 未过期 → 双双写入黑名单（TTL=剩余有效期）")
        void shouldBlacklistBothTokens() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "Bearer access-x");
            req.setCookies(new Cookie("VIBE_REFRESH", "refresh-x"));
            when(jwtUtils.getExpirationFromToken("access-x")).thenReturn(System.currentTimeMillis() + 60_000);
            when(jwtUtils.getExpirationFromToken("refresh-x")).thenReturn(System.currentTimeMillis() + 120_000);

            Result<String> result = controller.logout(req, new MockHttpServletResponse());

            assertEquals(200, result.getCode());
            verify(valueOps, times(2)).set(startsWith("token:blacklist:"), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("token 已过期（剩余 ≤ 0）→ 不写黑名单")
        void shouldSkipBlacklistForExpiredToken() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "Bearer expired");
            when(jwtUtils.getExpirationFromToken("expired")).thenReturn(System.currentTimeMillis() - 1);

            controller.logout(req, new MockHttpServletResponse());

            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("解析过期时间抛异常 → 吞掉异常，登出仍成功")
        void shouldSwallowBlacklistFailure() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "Bearer broken");
            when(jwtUtils.getExpirationFromToken("broken")).thenThrow(new IllegalArgumentException("bad token"));

            Result<String> result = controller.logout(req, new MockHttpServletResponse());

            assertEquals(200, result.getCode());
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("SHA-256 不可用（NoSuchAlgorithmException）→ blacklistToken 降级 hashCode 摘要，登出仍成功")
        void shouldFallbackToHashCodeWhenSha256Unavailable() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "Bearer tok");
            when(jwtUtils.getExpirationFromToken("tok")).thenReturn(System.currentTimeMillis() + 60_000);

            try (MockedStatic<MessageDigest> digest = mockStatic(MessageDigest.class)) {
                digest.when(() -> MessageDigest.getInstance("SHA-256"))
                        .thenThrow(new NoSuchAlgorithmException("no SHA-256 provider"));

                Result<String> result = controller.logout(req, new MockHttpServletResponse());

                assertEquals(200, result.getCode());
                verify(valueOps).set(startsWith("token:blacklist:"), eq("1"), any(Duration.class));
            }
        }

        @Test
        @DisplayName("Redis 写入抛异常 → 登出仍成功（未阻塞用户退出）且降级被计数")
        void shouldSwallowRedisWriteFailure() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("Authorization", "Bearer tok");
            when(jwtUtils.getExpirationFromToken("tok")).thenReturn(System.currentTimeMillis() + 60_000);
            doThrow(new RuntimeException("redis down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));

            assertEquals(200, controller.logout(req, new MockHttpServletResponse()).getCode());
            assertEquals(1, tokenBlacklistService.getDegradedWriteCount(), "写降级必须可观测");
            // 关键：Redis 写失败也必须已落本地吊销表（否则登出静默失效）
            assertTrue(tokenBlacklistService.isRevoked("tok"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/refresh")
    class Refresh {

        private MockHttpServletRequest withRefreshCookie(String value) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/refresh");
            req.setCookies(new Cookie("VIBE_REFRESH", value));
            return req;
        }

        @Test
        @DisplayName("无 VIBE_REFRESH Cookie → 401")
        void shouldRejectWhenCookieMissing() {
            Result<Map<String, Object>> result =
                    controller.refresh(new MockHttpServletRequest(), new MockHttpServletResponse());

            assertEquals(401, result.getCode());
            assertEquals("缺少 refresh token", result.getMessage());
            verifyNoInteractions(jwtUtils);
        }

        @Test
        @DisplayName("只有其它 Cookie（无 VIBE_REFRESH）→ 401")
        void shouldIgnoreUnrelatedCookies() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setCookies(new Cookie("VIBE_TOKEN", "access-x"));

            assertEquals(401, controller.refresh(req, new MockHttpServletResponse()).getCode());
        }

        @Test
        @DisplayName("refresh token 校验失败（过期/签名坏）→ 401")
        void shouldRejectInvalidToken() {
            when(jwtUtils.validateToken("bad")).thenReturn(false);

            Result<Map<String, Object>> result =
                    controller.refresh(withRefreshCookie("bad"), new MockHttpServletResponse());

            assertEquals(401, result.getCode());
            assertEquals("refresh token 无效或已过期", result.getMessage());
            verify(jwtUtils, never()).isRefreshToken(anyString());
        }

        @Test
        @DisplayName("拿 access token 当 refresh 用 → 401 非法类型")
        void shouldRejectAccessToken() {
            when(jwtUtils.validateToken("access-x")).thenReturn(true);
            when(jwtUtils.isRefreshToken("access-x")).thenReturn(false);

            Result<Map<String, Object>> result =
                    controller.refresh(withRefreshCookie("access-x"), new MockHttpServletResponse());

            assertEquals(401, result.getCode());
            assertEquals("非法的 token 类型", result.getMessage());
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("refresh token 已被拉黑 → 401 已注销")
        void shouldRejectBlacklistedToken() {
            when(jwtUtils.validateToken("revoked")).thenReturn(true);
            when(jwtUtils.isRefreshToken("revoked")).thenReturn(true);
            when(redis.hasKey(startsWith("token:blacklist:"))).thenReturn(true);

            Result<Map<String, Object>> result =
                    controller.refresh(withRefreshCookie("revoked"), new MockHttpServletResponse());

            assertEquals(401, result.getCode());
            assertEquals("refresh token 已注销", result.getMessage());
            verify(jwtUtils, never()).generateAccessToken(anyString());
        }

        @Test
        @DisplayName("Redis 不可用 + 本地表未命中 → 未知状态降级放行、继续签发（有意保留，见 fail-open 配置）")
        void shouldSkipBlacklistWhenRedisDown() {
            when(jwtUtils.validateToken("rtok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("rtok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
            when(jwtUtils.getUserIdFromToken("rtok")).thenReturn("42");
            when(jwtUtils.getExpirationFromToken("rtok")).thenReturn(System.currentTimeMillis() + 60_000);

            Result<Map<String, Object>> result =
                    controller.refresh(withRefreshCookie("rtok"), new MockHttpServletResponse());

            assertEquals(200, result.getCode());
            assertEquals("access-token", result.getData().get("token"));
        }

        @Test
        @DisplayName("Redis 不可用 + 本地表已命中 → 401 已注销（不再“跳过检查”）")
        void shouldRejectLocallyRevokedRefreshTokenWhenRedisDown() {
            when(jwtUtils.validateToken("revoked-rtok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("revoked-rtok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
            when(jwtUtils.getExpirationFromToken("revoked-rtok")).thenReturn(System.currentTimeMillis() + 60_000);
            tokenBlacklistService.revoke("revoked-rtok");

            Result<Map<String, Object>> result =
                    controller.refresh(withRefreshCookie("revoked-rtok"), new MockHttpServletResponse());

            assertEquals(401, result.getCode());
            assertEquals("refresh token 已注销", result.getMessage());
            verify(jwtUtils, never()).generateAccessToken(anyString());
        }

        @Test
        @DisplayName("token 解析不出 userId → 401 无效 token")
        void shouldRejectWhenUserIdNull() {
            when(jwtUtils.validateToken("rtok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("rtok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("rtok")).thenReturn(null);

            Result<Map<String, Object>> result =
                    controller.refresh(withRefreshCookie("rtok"), new MockHttpServletResponse());

            assertEquals(401, result.getCode());
            assertEquals("无效的 token", result.getMessage());
        }

        @Test
        @DisplayName("解析 userId 抛异常 → 401（2026-10-19 修复：不再冒泡成 500）")
        void shouldReturn401WhenUserIdParsingThrows() {
            when(jwtUtils.validateToken("rtok")).thenReturn(true);
            when(jwtUtils.isRefreshToken("rtok")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("rtok")).thenThrow(new IllegalStateException("malformed claim"));

            Result<Map<String, Object>> result =
                    controller.refresh(withRefreshCookie("rtok"), new MockHttpServletResponse());

            assertEquals(401, result.getCode());
            assertEquals("无效的 token", result.getMessage());
            verify(jwtUtils, never()).generateAccessToken(anyString());
        }

        @Test
        @DisplayName("正常刷新：签发新 token 对、旧 refresh 拉黑、下发新 Cookie")
        void shouldRotateTokens() {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            when(jwtUtils.validateToken("old-refresh")).thenReturn(true);
            when(jwtUtils.isRefreshToken("old-refresh")).thenReturn(true);
            when(redis.hasKey(anyString())).thenReturn(false);
            when(jwtUtils.getUserIdFromToken("old-refresh")).thenReturn("42");
            when(jwtUtils.getExpirationFromToken("old-refresh")).thenReturn(System.currentTimeMillis() + 60_000);

            Result<Map<String, Object>> result = controller.refresh(withRefreshCookie("old-refresh"), resp);

            assertEquals(200, result.getCode());
            assertEquals("access-token", result.getData().get("token"));
            verify(jwtUtils).generateAccessToken("42");
            verify(jwtUtils).generateRefreshToken("42");
            verify(valueOps).set(startsWith("token:blacklist:"), eq("1"), any(Duration.class));
            String cookies = setCookies(resp);
            assertTrue(cookies.contains("VIBE_TOKEN=access-token"));
            assertTrue(cookies.contains("VIBE_REFRESH=refresh-token"));
            assertTrue(cookies.contains("Path=/api/auth/refresh"), "refresh cookie 必须限制路径");
        }
    }
}
