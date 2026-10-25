package com.vibemusic.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 收藏/取消收藏入参（POST /api/favorites/toggle）。
 * <p>
 * artist / coverUrl 可空：缺失时沿用 controller 内的默认值（"未知歌手" / ""），不加约束。
 */
public record FavoriteToggleRequest(
        @NotBlank(message = "sourceId 不能为空")
        String sourceId,
        @NotBlank(message = "songName 不能为空")
        String songName,
        String artist,
        String coverUrl) {
}
