package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.JwtUtils;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
import java.util.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "注册、登录、注销")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtUtils jwtUtils;

    private static final String UPLOAD_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "avatars";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );
    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024; // 2MB

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String nickname = body.containsKey("nickname") && body.get("nickname") != null
                ? body.get("nickname") : username;

        if (username == null || username.trim().isEmpty()) return Result.error("用户名不能为空");
        if (password == null || password.length() < 4) return Result.error("密码至少4位");

        userService.register(username.trim(), password, nickname);

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        CustomUserDetails details = (CustomUserDetails) auth.getPrincipal();
        String token = jwtUtils.generateToken(String.valueOf(details.getUserId()));

        Map<String, Object> data = buildUserData(details);
        data.put("token", token);
        return Result.ok(data);
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) return Result.error("用户名和密码不能为空");

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        CustomUserDetails details = (CustomUserDetails) auth.getPrincipal();
        String token = jwtUtils.generateToken(String.valueOf(details.getUserId()));

        Map<String, Object> data = buildUserData(details);
        data.put("token", token);
        return Result.ok(data);
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

    // ===== 辅助方法 =====

    private Map<String, Object> buildUserData(CustomUserDetails details) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", details.getUserId());
        data.put("username", details.getUsername());
        data.put("nickname", details.getNickname());
        data.put("avatar", details.getAvatar());
        data.put("bgImage", details.getBgImage());
        data.put("gender", details.getGender());
        data.put("birthday", details.getBirthday());
        return data;
    }

    private Map<String, Object> buildUserDataFromEntity(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("avatar", user.getAvatar());
        data.put("bgImage", user.getBgImage());
        data.put("gender", user.getGender());
        data.put("birthday", user.getBirthday());
        return data;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "jpg";
    }
}
