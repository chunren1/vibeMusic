package com.vibemusic.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新歌单信息入参（POST /api/playlists/update）。
 * <p>
 * playlistId 必填；name / description / coverUrl 为 null 表示「不修改」，故不加 @NotNull，
 * name 仅加 DB 同宽的长度上限。
 */
public record PlaylistUpdateRequest(
        @NotNull(message = "缺少 playlistId")
        Long playlistId,
        @Size(max = 200, message = "歌单名称不能超过200个字符")
        String name,
        String description,
        String coverUrl) {
}
