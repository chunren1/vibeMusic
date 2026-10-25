package com.vibemusic.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新个人资料入参（PUT /api/auth/profile）。
 * <p>
 * 三个字段全部可空（null = 不修改），因此不加 @NotNull/@NotBlank；
 * 只复刻 controller 内已有的长度/白名单/格式校验。
 */
public record UpdateProfileRequest(
        @Size(max = 30, message = "昵称长度不能超过30个字符")
        String nickname,
        @Pattern(regexp = "^(男|女|保密)$", message = "性别参数无效")
        String gender,
        @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "生日格式不正确（应为 YYYY-MM-DD）")
        String birthday) {
}
