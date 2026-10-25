package com.vibemusic.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 搜索历史云同步入参（POST /api/search/history/sync）。
 * <p>
 * keywords 必填但允许空列表（service 自行裁剪到 MAX_HISTORY），故用 @NotNull 而非 @NotEmpty/@Size。
 */
public record SearchHistorySyncRequest(
        @NotNull(message = "keywords 不能为空")
        List<String> keywords) {
}
