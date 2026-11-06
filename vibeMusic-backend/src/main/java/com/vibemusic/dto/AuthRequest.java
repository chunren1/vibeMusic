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
            String nickname,
            /**
             * 邀请码（2026-11-06）：仅在服务端配置 REGISTER_INVITE_CODE 时才校验，
             * 未配置时此字段被忽略——这样后端可以先上线，等带输入框的客户端铺开再启用。
             */
            @Size(max = 64, message = "邀请码格式不正确")
            String inviteCode) {
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
