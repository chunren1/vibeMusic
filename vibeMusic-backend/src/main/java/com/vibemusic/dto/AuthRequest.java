package com.vibemusic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 认证入参（含 Bean Validation 约束，被 {@code @Valid} 触发后由
 * GlobalExceptionHandler.handleValidationException 统一转为 400 信封）。
 */
public final class AuthRequest {

    private AuthRequest() {
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(max = 30, message = "用户名格式不正确")
            String username,
            @NotBlank(message = "密码至少8位")
            @Size(min = 8, max = 100, message = "密码至少8位")
            String password,
            @Size(max = 30, message = "昵称长度不能超过30个字符")
            String nickname) {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名和密码不能为空")
            @Size(max = 30, message = "用户名格式不正确")
            String username,
            @NotBlank(message = "用户名和密码不能为空")
            @Size(max = 100, message = "用户名和密码不能为空")
            String password) {
    }
}
