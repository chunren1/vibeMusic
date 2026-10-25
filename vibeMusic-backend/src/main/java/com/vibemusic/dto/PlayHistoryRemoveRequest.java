package com.vibemusic.dto;

import java.util.List;

/**
 * 播放历史批量删除入参（POST /api/songs/history/remove）。
 * <p>
 * sourceIds 保持宽松：缺失/空列表按「删除 0 条」返回，不加约束。
 */
public record PlayHistoryRemoveRequest(List<String> sourceIds) {
}
