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
    /**
     * 空结果回退建议关键词（拼音别名/拼写纠错/归一化修正），无建议时为 null。
     * 加性字段：老客户端 JSON 反序列化多余字段直接忽略，契约不破。
     */
    private final String suggestedKeyword;

    private SearchResult(List<SongDTO> list, long total, int page, int size, boolean hasMore, String source,
                         String suggestedKeyword) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasMore = hasMore;
        this.source = source;
        this.suggestedKeyword = suggestedKeyword;
    }

    public static SearchResult of(List<SongDTO> list, long total, int page, int size, String source) {
        return of(list, total, page, size, source, null);
    }

    public static SearchResult of(List<SongDTO> list, long total, int page, int size, String source,
                                  String suggestedKeyword) {
        return new SearchResult(list, total, page, size, page * size < total, source, suggestedKeyword);
    }

    // Getters for Jackson serialization
    public List<SongDTO> getList() { return list; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public boolean isHasMore() { return hasMore; }
    public String getSource() { return source; }
    public String getSuggestedKeyword() { return suggestedKeyword; }
}
