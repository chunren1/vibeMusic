package com.vibemusic.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 导入外部歌单入参（POST /api/playlists/import）。
 * <p>
 * id 用 String 承载：客户端可能传 JSON 字符串或数字（Jackson 会做标量转换），
 * 原实现也是先统一转字符串再查详情。
 */
public record PlaylistImportRequest(
        @NotBlank(message = "缺少 source 或 id 参数")
        String source,
        @NotBlank(message = "缺少 source 或 id 参数")
        String id) {
}
