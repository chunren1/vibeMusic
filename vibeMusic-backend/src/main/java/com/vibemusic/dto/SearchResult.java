package com.vibemusic.dto;

import java.util.List;

/**
 * 标准化搜索分页响应结构
 */
public class SearchResult {
    private final List<SongDTO> list;
    private final long total;
    private final int page;
    private final int size;
    private final boolean hasMore;
    private final String source; // "redis" | "es" | "api"

    private SearchResult(List<SongDTO> list, long total, int page, int size, boolean hasMore, String source) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasMore = hasMore;
        this.source = source;
    }

    public static SearchResult of(List<SongDTO> list, long total, int page, int size, String source) {
        return new SearchResult(list, total, page, size, page * size < total, source);
    }

    // Getters for Jackson serialization
    public List<SongDTO> getList() { return list; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public boolean isHasMore() { return hasMore; }
    public String getSource() { return source; }
}
