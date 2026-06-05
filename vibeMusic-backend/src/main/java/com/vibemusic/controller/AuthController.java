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

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "注册、登录、注销")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtUtils jwtUtils;

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        // Bug7修复: 正确处理 map key 存在但 value 为 null 的情况
        String nickname = body.containsKey("nickname") && body.get("nickname") != null
                ? body.get("nickname") : username;

        if (username == null || username.trim().isEmpty()) return Result.error("用户名不能为空");
        if (password == null || password.length() < 4) return Result.error("密码至少4位");

        userService.register(username.trim(), password, nickname);

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        CustomUserDetails details = (CustomUserDetails) auth.getPrincipal();
        String token = jwtUtils.generateToken(String.valueOf(details.getUserId()));

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", details.getUserId());
        data.put("username", details.getUsername());
        data.put("nickname", details.getNickname());
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

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", details.getUserId());
        data.put("username", details.getUsername());
        data.put("nickname", details.getNickname());
        return Result.ok(data);
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    public Result<Map<String, Object>> me() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        User user = userService.findById(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("avatar", user.getAvatar());
        return Result.ok(data);
    }
}
