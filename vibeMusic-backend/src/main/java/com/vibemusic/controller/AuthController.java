package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.dto.AuthRequest;
import com.vibemusic.dto.ChangePasswordRequest;
import com.vibemusic.dto.UpdateProfileRequest;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.security.TokenBlacklistService;
import com.vibemusic.service.RegistrationGuard;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "注册、登录、注销")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtUtils jwtUtils;
    private final TokenBlacklistService tokenBlacklistService;
    private final RegistrationGuard registrationGuard;

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Map<String, Object>> register(@Valid @RequestBody AuthRequest.RegisterRequest req,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        String username = req.username();
        String password = req.password();
        String nickname = req.nickname() != null ? req.nickname() : username;

        if (username == null || username.trim().isEmpty()) return Result.error("用户名不能为空");
        username = username.trim();
        if (password == null || password.length() < 8) return Result.error("密码至少8位");

        // 注册防滥用（2026-11-06）：形态 → 邀请码 → 每网络每日配额。
        // 顺序不能反：填错邀请码不该烧掉当天的配额；邀请码爆破由 nginx limit_req zone=auth 兜底。
        registrationGuard.validateUsername(username);
        registrationGuard.validateInviteCode(req.inviteCode());
        registrationGuard.consumeQuota(request);

        userService.register(username, password, nickname);

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        CustomUserDetails details = (CustomUserDetails) auth.getPrincipal();
        String userId = String.valueOf(details.getUserId());
        String accessToken = jwtUtils.generateAccessToken(userId);
        String refreshToken = jwtUtils.generateRefreshToken(userId);

        setTokenCookies(request, response, accessToken, refreshToken);

        Map<String, Object> data = buildUserData(details);
        data.put("token", accessToken);
        return Result.ok(data);
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result<Map<String, Object>> login(@Valid @RequestBody AuthRequest.LoginRequest req,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        log.info("[/api/auth/login] 收到请求 - origin={}, contentType={}",
                request.getHeader("Origin"),
                request.getContentType());

        String username = req.username();
        String password = req.password();

        if (username == null || password == null) {
            log.warn("[/api/auth/login] 缺少参数");
            return Result.error("用户名和密码不能为空");
        }
        username = username.trim();
        if (username.isEmpty() || username.length() > 30) return Result.error("用户名格式不正确");

        log.info("[/api/auth/login] 开始认证: username={}", username);
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        CustomUserDetails details = (CustomUserDetails) auth.getPrincipal();
        String userId = String.valueOf(details.getUserId());
        String accessToken = jwtUtils.generateAccessToken(userId);
        String refreshToken = jwtUtils.generateRefreshToken(userId);

        setTokenCookies(request, response, accessToken, refreshToken);

        Map<String, Object> data = buildUserData(details);
        data.put("token", accessToken);
        log.info("[/api/auth/login] 登录成功: userId={}, username={}", details.getUserId(), username);
        return Result.ok(data);
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查（公开端点，用于诊断网络连通性）")
    public Result<String> health(HttpServletRequest request) {
        return Result.ok("OK - " + request.getRemoteAddr() + " - " + java.time.Instant.now());
    }

    /**
     * 注册配置（公开）：客户端据此决定是否显示「邀请码」输入框。
     *
     * <p>为什么不直接把邀请码要求写死在客户端：服务端可能还没启用（{@code REGISTER_INVITE_CODE} 为空），
     * 此时显示一个必填邀请码框会把新用户挡在门外。启用/停用是运营动作，客户端应该跟着服务端走。</p>
     */
    @GetMapping("/register-config")
    @Operation(summary = "注册配置（是否需要邀请码）")
    public Result<Map<String, Object>> registerConfig() {
        Map<String, Object> data = new HashMap<>();
        data.put("inviteCodeRequired", registrationGuard.isInviteCodeRequired());
        return Result.ok(data);
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    public ResponseEntity<Result<Map<String, Object>>> me() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) {
            // 未登录用 HTTP 401，而不是 200 + 信封 code=401：
            // 后者会让网关监控、CDN 统计与探活把未认证请求算作成功。
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(401, "未登录"));
        }

        User user = userService.findById(userId);
        Map<String, Object> data = buildUserDataFromEntity(user);
        return ResponseEntity.ok(Result.ok(data));
    }

    @PostMapping("/change-password")
    @Operation(summary = "修改密码")
    public Result<String> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        String oldPwd = req.oldPassword();
        String newPwd = req.newPassword();
        if (oldPwd == null || newPwd == null) return Result.error("参数不能为空");
        if (newPwd.length() < 8) return Result.error("新密码至少8位");

        userService.changePassword(userId, oldPwd, newPwd);
        return Result.ok("密码修改成功");
    }

    @PutMapping("/profile")
    @Operation(summary = "更新个人资料")
    public Result<Map<String, Object>> updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        String nickname = req.nickname();
        String gender = req.gender();
        String birthday = req.birthday();

        // 校验
        if (nickname != null && (nickname.trim().isEmpty() || nickname.length() > 30)) {
            return Result.error("昵称长度不能超过30个字符");
        }
        if (gender != null && !List.of("男", "女", "保密").contains(gender)) {
            return Result.error("性别参数无效");
        }
        if (birthday != null && !birthday.isEmpty() && !birthday.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return Result.error("生日格式不正确（应为 YYYY-MM-DD）");
        }

        User user = userService.updateProfile(userId, nickname, gender, birthday);
        return Result.ok(buildUserDataFromEntity(user));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public Result<String> logout(HttpServletRequest request, HttpServletResponse response) {
        // 加入黑名单：先落本地表（Redis 抖动时吊销依旧生效），再尽力同步 Redis
        String accessToken = extractTokenFromRequest(request);
        String refreshToken = extractRefreshTokenFromRequest(request);
        if (accessToken != null) tokenBlacklistService.revoke(accessToken);
        if (refreshToken != null) tokenBlacklistService.revoke(refreshToken);

        // 清除 Cookie
        clearCookie(request, response, "VIBE_TOKEN", "/");
        clearCookie(request, response, "VIBE_REFRESH", "/api/auth");
        // 迁移期清理：2026-11-04 之前写入的 refresh Cookie 路径为 /api/auth/refresh，
        // 同名不同 Path 互不覆盖，不单独下发删除指令会残留到自然过期（最长 7 天）。
        // 存量会话过期后（2026-12 之后）可移除本条。
        clearCookie(request, response, "VIBE_REFRESH", "/api/auth/refresh");
        // 保持幂等：即使吊销只落到本地表（Redis 不可用），客户端体验不变
        return Result.ok("已退出");
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新 access token（使用 refresh token）")
    public Result<Map<String, Object>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractRefreshTokenFromRequest(request);
        if (refreshToken == null) return Result.error(401, "缺少 refresh token");

        if (!jwtUtils.validateToken(refreshToken)) return Result.error(401, "refresh token 无效或已过期");
        if (!jwtUtils.isRefreshToken(refreshToken)) return Result.error(401, "非法的 token 类型");

        // 检查黑名单（本地表 + Redis，Redis 不可用时本地表兜底；不再“跳过检查”）
        if (tokenBlacklistService.isRevoked(refreshToken)) {
            return Result.error(401, "refresh token 已注销");
        }

        // 生成新 token 对
        String userId;
        try {
            userId = jwtUtils.getUserIdFromToken(refreshToken);
        } catch (Exception e) {
            // 解析异常（如恰好过期）→ 401，不冒泡成 500
            log.warn("refresh token 解析失败: {}", e.getMessage());
            return Result.error(401, "无效的 token");
        }
        if (userId == null) return Result.error(401, "无效的 token");
        String newAccessToken = jwtUtils.generateAccessToken(userId);
        String newRefreshToken = jwtUtils.generateRefreshToken(userId);

        // 旧的 refresh token 加入黑名单（防止重复使用）
        tokenBlacklistService.revoke(refreshToken);

        setTokenCookies(request, response, newAccessToken, newRefreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("token", newAccessToken);
        return Result.ok(data);
    }

    // ===== 辅助方法 =====

    private void setTokenCookies(HttpServletRequest request, HttpServletResponse response,
                                  String accessToken, String refreshToken) {
        String secure = isSecureRequest(request) ? "; Secure" : "";
        // Access token: 15min, 全局路径
        response.addHeader("Set-Cookie",
                String.format("VIBE_TOKEN=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Lax%s",
                        accessToken, jwtUtils.getAccessExpiration() / 1000, secure));
        // Refresh token: 7d, 限制在 /api/auth 下——refresh 与 logout 都在该前缀内，
        // 登出时才读得到它并吊销；若收窄到 /api/auth/refresh，logout 请求不携带该 Cookie，
        // 既无法吊销，也无法用同名同路径的删除指令清除。
        response.addHeader("Set-Cookie",
                String.format("VIBE_REFRESH=%s; Path=/api/auth; Max-Age=%d; HttpOnly; SameSite=Lax%s",
                        refreshToken, jwtUtils.getRefreshExpiration() / 1000, secure));
    }

    private void clearCookie(HttpServletRequest request, HttpServletResponse response, String name, String path) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        // 删除指令的属性必须与写入侧对称（2026-11-06 补）。原来只给了 HttpOnly，
        // 而写入侧是 `HttpOnly; SameSite=Lax` + 线上 `Secure`：浏览器按 name/domain/path
        // 匹配删除，属性不一致通常也能删掉，但 Cookie 策略严格的 UA（部分国产浏览器、
        // 未来若加 __Host- 前缀）会直接忽略属性不匹配的过期指令，导致「退出登录后
        // 旧 Cookie 仍在、刷新页面又被当成已登录」。这里与 setTokenCookies 用同一个
        // isSecureRequest 判定，保证同一环境下两侧属性完全一致。
        cookie.setSecure(isSecureRequest(request));
        cookie.setAttribute("SameSite", "Lax");
        // 删除 Cookie 要求 name + domain + path 三者与写入时一致，否则浏览器忽略该指令
        cookie.setPath(path);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    /**
     * 是否为 HTTPS 请求。
     *
     * <p>链路是 Cloudflare(https) → cloudflared → nginx(http, 127.0.0.1:8081) → 后端，
     * 后端收到的连接永远是明文，{@code request.isSecure()} 必然为 false，真实协议只能
     * 从 {@code X-Forwarded-Proto} 读。nginx 侧由 {@code conf.d/forwarded-proto.conf}
     * 的 map 还原（Cloudflare 会写入访客真实协议），因此这里只需认这个头。</p>
     */
    private boolean isSecureRequest(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }

    private String extractTokenFromRequest(HttpServletRequest request) {
        return JwtUtils.extractTokenFromRequest(request, "VIBE_TOKEN");
    }

    private String extractRefreshTokenFromRequest(HttpServletRequest request) {
        // 优先从 body 读取（refresh 端点）
        // 降级从 cookie 读取
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("VIBE_REFRESH".equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }

    private Map<String, Object> buildUserData(CustomUserDetails details) {
        return buildUserData(details.getUserId(), details.getUsername(), details.getNickname(),
                details.getAvatar(), details.getBgImage(), details.getGender(), details.getBirthday());
    }

    private Map<String, Object> buildUserDataFromEntity(User user) {
        return buildUserData(user.getId(), user.getUsername(),
                user.getNickname() != null ? user.getNickname() : user.getUsername(),
                user.getAvatar(), user.getBgImage(), user.getGender(), user.getBirthday());
    }

    /** 统一的 user data 构建（消除 buildUserData / buildUserDataFromEntity 重复） */
    private Map<String, Object> buildUserData(Long userId, String username, String nickname,
                                              String avatar, String bgImage, String gender, String birthday) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("username", username);
        data.put("nickname", nickname);
        data.put("avatar", avatar);
        data.put("bgImage", bgImage);
        data.put("gender", gender);
        data.put("birthday", birthday);
        return data;
    }
}
