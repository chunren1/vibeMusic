package com.vibemusic.dto;

import java.util.List;

/**
 * 批量删除歌单入参（POST /api/playlists/delete-batch）。
 * <p>
 * ids 保持宽松：缺失/空列表按「删除 0 个」返回，不加约束。
 */
public record PlaylistDeleteBatchRequest(List<Long> ids) {
}
