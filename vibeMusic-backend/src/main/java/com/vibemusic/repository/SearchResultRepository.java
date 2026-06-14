package com.vibemusic.repository;

import com.vibemusic.doc.SearchResultDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

/**
 * 搜索缓存 ES Repository — 持久化跨平台搜索结果
 */
public interface SearchResultRepository extends ElasticsearchRepository<SearchResultDoc, String> {

    /**
     * 按关键词搜索（IK 分词匹配）
     */
    List<SearchResultDoc> findByKeyword(String keyword);
}
