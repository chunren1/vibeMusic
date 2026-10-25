package com.vibemusic.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * 歌单排序入参（POST /api/playlists/reorder）。
 * <p>
 * order 每项形如 {@code {"playlistId": 1, "sortOrder": 0}}，由 service 逐项解析；
 * 这里只校验「非 null 且非空」，与 controller 原判空逻辑一致。
 */
public record PlaylistReorderRequest(
        @NotEmpty(message = "缺少 order")
        List<Map<String, Object>> order) {
}
