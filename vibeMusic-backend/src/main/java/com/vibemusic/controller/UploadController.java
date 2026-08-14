package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.entity.User;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 上传控制器 — 头像 / 个人页背景图上传（自 AuthController 外移，端点路径保持不变）。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "上传", description = "头像、背景图上传")
public class UploadController {

    private final UserService userService;

    private static final String UPLOAD_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "avatars";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );
    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024; // 2MB

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

        // 魔数校验：防止 Content-Type 伪造
        if (!isValidImage(file)) {
            return Result.error("文件内容不是有效图片");
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

        // 魔数校验
        if (!isValidImage(file)) {
            return Result.error("文件内容不是有效图片");
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

    /** 通过文件头部魔数校验是否为真实图片 */
    private boolean isValidImage(MultipartFile file) {
        try {
            byte[] header = new byte[8];
            try (var in = file.getInputStream()) {
                int read = in.read(header);
                if (read < 4) return false;
            }
            // JPEG: FF D8 FF
            if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) return true;
            // PNG: 89 50 4E 47
            if (header[0] == (byte)0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') return true;
            // GIF: 47 49 46 38
            if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') return true;
            // WebP: 52 49 46 46 ... 57 45 42 50
            if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header.length >= 12 && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') return true;
            return false;
        } catch (IOException e) {
            log.warn("魔数校验失败: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildUserDataFromEntity(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname() != null ? user.getNickname() : user.getUsername());
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