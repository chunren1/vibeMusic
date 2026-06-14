package com.vibemusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 搜索增强响应 — 包含高亮片段 + 平台聚合统计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {

    /** 歌曲列表 */
    private List<SongDTO> songs;

    /** 高亮片段: sourceId → 高亮文本列表 */
    private Map<String, List<String>> highlights;

    /** 平台聚合: "netease" → 数量, "qq" → 数量 */
    private Map<String, Long> platformCounts;
}
