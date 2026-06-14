package com.vibemusic.repository;

import com.vibemusic.doc.SearchResultDoc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.Date;
import java.util.List;

public interface SearchResultRepository extends ElasticsearchRepository<SearchResultDoc, String> {

    /**
     * 按搜索词分页查询（IK 分词匹配）
     */
    List<SearchResultDoc> findByKeyword(String keyword);
    Page<SearchResultDoc> findByKeyword(String keyword, Pageable pageable);

    /**
     * 清理过期数据（定时任务用）
     */
    void deleteByCreateTimeBefore(Date expireTime);
}
