package com.vibemusic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建歌单入参（POST /api/playlists/create）。
 * <p>
 * name 必填（DB playlist.name VARCHAR(200)）；description / coverUrl 可空，沿用 "" 默认值。
 */
public record PlaylistCreateRequest(
        @NotBlank(message = "歌单名称不能为空")
        @Size(max = 200, message = "歌单名称不能超过200个字符")
        String name,
        String description,
        String coverUrl) {
}
