package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.dto.AuthRequest;
import com.vibemusic.dto.ChangePasswordRequest;
import com.vibemusic.dto.UpdateProfileRequest;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.security.TokenBlacklistService;
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

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Map<String, Object>> register(@Valid @RequestBody AuthRequest.RegisterRequest req,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        String username = req.username();
        String password = req.password();
        String nickname = req.nickname() != null ? req.nickname() : username;

        if (username == null || username.trim().isEmpty()) return Result.error("用户名不能为空");
        if (password == null || password.length() < 8) return Result.error("密码至少8位");

        userService.register(username.trim(), password, nickname);

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

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    public Result<Map<String, Object>> me() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        User user = userService.findById(userId);
        Map<String, Object> data = buildUserDataFromEntity(user);
        return Result.ok(data);
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
        clearCookie(response, "VIBE_TOKEN");
        clearCookie(response, "VIBE_REFRESH");
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
        String secure = (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")))
                ? "; Secure" : "";
        // Access token: 15min, 全局路径
        response.addHeader("Set-Cookie",
                String.format("VIBE_TOKEN=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Lax%s",
                        accessToken, jwtUtils.getAccessExpiration() / 1000, secure));
        // Refresh token: 7d, 仅 /api/auth/refresh 路径可读取
        response.addHeader("Set-Cookie",
                String.format("VIBE_REFRESH=%s; Path=/api/auth/refresh; Max-Age=%d; HttpOnly; SameSite=Lax%s",
                        refreshToken, jwtUtils.getRefreshExpiration() / 1000, secure));
    }

    private void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
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
