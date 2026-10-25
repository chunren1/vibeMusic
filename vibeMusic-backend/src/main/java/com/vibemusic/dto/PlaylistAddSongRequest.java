package com.vibemusic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 添加歌曲到歌单入参（POST /api/playlists/add-song）。
 * <p>
 * playlistId / sourceId / songName 必填；artist、coverUrl、platform、duration
 * 可空并沿用原默认值（"" / "netease" / 0），不加约束。
 */
public record PlaylistAddSongRequest(
        @NotNull(message = "缺少 playlistId")
        Long playlistId,
        @NotBlank(message = "缺少 sourceId")
        String sourceId,
        @NotBlank(message = "缺少 songName")
        String songName,
        String artist,
        String coverUrl,
        String platform,
        Integer duration) {
}
