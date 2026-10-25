package com.vibemusic.dto;

import java.util.List;

/**
 * 批量取消收藏入参（POST /api/favorites/remove-batch）。
 * <p>
 * sourceIds 保持宽松：缺失/空列表按「移除 0 首」返回，不加约束。
 */
public record FavoriteRemoveBatchRequest(List<String> sourceIds) {
}
