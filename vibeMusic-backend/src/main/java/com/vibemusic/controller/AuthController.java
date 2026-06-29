package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
    private final StringRedisTemplate stringRedisTemplate;

    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    private static final String UPLOAD_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "avatars";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );
    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024; // 2MB

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body,
                                                 HttpServletRequest request,
                                                 HttpServletResponse response) {
        String username = body.get("username");
        String password = body.get("password");
        String nickname = body.containsKey("nickname") && body.get("nickname") != null
                ? body.get("nickname") : username;

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
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        log.info("[/api/auth/login] 收到请求 - origin={}, contentType={}, bodyKeys={}, cookies={}",
                request.getHeader("Origin"),
                request.getContentType(),
                body != null ? body.keySet() : "null",
                request.getCookies() != null ? request.getCookies().length : 0);

        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            log.warn("[/api/auth/login] 缺少参数 username={} password={}", username != null, password != null);
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
    public Result<String> changePassword(@RequestBody Map<String, String> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        String oldPwd = body.get("oldPassword");
        String newPwd = body.get("newPassword");
        if (oldPwd == null || newPwd == null) return Result.error("参数不能为空");
        if (newPwd.length() < 4) return Result.error("新密码至少4位");

        userService.changePassword(userId, oldPwd, newPwd);
        return Result.ok("密码修改成功");
    }

    @PutMapping("/profile")
    @Operation(summary = "更新个人资料")
    public Result<Map<String, Object>> updateProfile(@RequestBody Map<String, String> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        String nickname = body.get("nickname");
        String gender = body.get("gender");
        String birthday = body.get("birthday");

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

    @PostMapping("/avatar")
    @Operation(summary = "上传头像")
    public Result<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        if (file.isEmpty()) return Result.error("请选择文件");

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return Result.error("不支持的文件类型，仅支持 JPG/PNG/GIF/WebP");
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            return Result.error("头像文件不能超过 2MB");
        }

        try {
            // 确保上传目录存在
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 生成唯一文件名
            String ext = getExtension(Objects.requireNonNull(file.getOriginalFilename()));
            String fileName = "avatar_" + userId + "_" + System.currentTimeMillis() + "." + ext;
            Path filePath = uploadPath.resolve(fileName);
            file.transferTo(filePath.toFile());

            // 构建头像 URL
            String avatarUrl = "/uploads/avatars/" + fileName;

            // 更新用户头像
            User user = userService.updateAvatar(userId, avatarUrl);

            Map<String, Object> data = buildUserDataFromEntity(user);
            data.put("avatarUrl", avatarUrl);
            return Result.ok(data);
        } catch (IOException e) {
            return Result.error("头像上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/bg-image")
    @Operation(summary = "上传个人页背景图")
    public Result<Map<String, Object>> uploadBgImage(@RequestParam("file") MultipartFile file) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        if (file.isEmpty()) return Result.error("请选择文件");

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return Result.error("不支持的文件类型，仅支持 JPG/PNG/GIF/WebP");
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            return Result.error("背景图文件不能超过 2MB");
        }

        try {
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String ext = getExtension(Objects.requireNonNull(file.getOriginalFilename()));
            String fileName = "bg_" + userId + "_" + System.currentTimeMillis() + "." + ext;
            Path filePath = uploadPath.resolve(fileName);
            file.transferTo(filePath.toFile());

            String bgUrl = "/uploads/avatars/" + fileName;
            User user = userService.updateBgImage(userId, bgUrl);

            Map<String, Object> data = buildUserDataFromEntity(user);
            data.put("bgImageUrl", bgUrl);
            return Result.ok(data);
        } catch (IOException e) {
            return Result.error("背景图上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public Result<String> logout(HttpServletRequest request, HttpServletResponse response) {
        // 加入黑名单
        String accessToken = extractTokenFromRequest(request);
        String refreshToken = extractRefreshTokenFromRequest(request);
        if (accessToken != null) blacklistToken(accessToken);
        if (refreshToken != null) blacklistToken(refreshToken);

        // 清除 Cookie
        clearCookie(response, "VIBE_TOKEN");
        clearCookie(response, "VIBE_REFRESH");
        return Result.ok("已退出");
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新 access token（使用 refresh token）")
    public Result<Map<String, Object>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractRefreshTokenFromRequest(request);
        if (refreshToken == null) return Result.error(401, "缺少 refresh token");

        if (!jwtUtils.validateToken(refreshToken)) return Result.error(401, "refresh token 无效或已过期");
        if (!jwtUtils.isRefreshToken(refreshToken)) return Result.error(401, "非法的 token 类型");

        // 检查黑名单
        try {
            String key = TOKEN_BLACKLIST_PREFIX + refreshToken.hashCode();
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                return Result.error(401, "refresh token 已注销");
            }
        } catch (Exception ignored) { /* Redis 不可用时放行 */ }

        // 生成新 token 对
        String userId = jwtUtils.getUserIdFromToken(refreshToken);
        String newAccessToken = jwtUtils.generateAccessToken(userId);
        String newRefreshToken = jwtUtils.generateRefreshToken(userId);

        // 旧的 refresh token 加入黑名单（防止重复使用）
        blacklistToken(refreshToken);

        setTokenCookies(request, response, newAccessToken, newRefreshToken);

        Map<String, Object> data = new HashMap<>();
        data.put("token", newAccessToken);
        return Result.ok(data);
    }

    // ===== token 黑名单 =====

    private void blacklistToken(String token) {
        try {
            long remainMs = jwtUtils.getExpirationFromToken(token) - System.currentTimeMillis();
            if (remainMs > 0) {
                String key = TOKEN_BLACKLIST_PREFIX + token.hashCode();
                stringRedisTemplate.opsForValue().set(key, "1", Duration.ofMillis(remainMs));
            }
        } catch (Exception e) {
            log.warn("token 黑名单写入失败: {}", e.getMessage());
        }
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
        // 优先从 Authorization header 读取
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7);
        // 降级从 cookie 读取
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("VIBE_TOKEN".equals(c.getName())) return c.getValue();
            }
        }
        return null;
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

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "jpg";
    }
}
