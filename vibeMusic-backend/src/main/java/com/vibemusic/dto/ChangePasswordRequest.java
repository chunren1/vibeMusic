package com.vibemusic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码入参（POST /api/auth/change-password）。
 * <p>
 * 长度下限与 controller 内既有校验一致（至少 8 位）；不设上限，避免收紧现有可接受输入。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "参数不能为空")
        String oldPassword,
        @NotBlank(message = "参数不能为空")
        @Size(min = 8, message = "新密码至少8位")
        String newPassword) {
}
