package com.vibemusic.doc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.Instant;

/**
 * ES 搜索缓存文档 — 跨平台搜索结果聚合并持久化
 * ID = MD5(keyword + songId)，同一搜索词的同一首歌只存一条
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "search_cache")
@Setting(settingPath = "es/search-cache-setting.json")
public class SearchResultDoc {

    @Id
    private String id;              // MD5(keyword + songId)

    @Field(type = FieldType.Text, analyzer = "ik_max_analyzer", searchAnalyzer = "ik_smart_analyzer")
    private String keyword;         // 搜索关键词（IK 分词）

    @Field(type = FieldType.Keyword)
    private String songId;          // 歌曲源ID

    @Field(type = FieldType.Text, index = false)
    private String name;            // 歌曲名

    @Field(type = FieldType.Text, index = false)
    private String artist;          // 歌手

    @Field(type = FieldType.Text, index = false)
    private String album;           // 专辑

    @Field(type = FieldType.Keyword, index = false)
    private String coverUrl;        // 封面URL

    @Field(type = FieldType.Integer)
    private Integer duration;       // 时长（秒）

    @Field(type = FieldType.Keyword)
    private String source;          // netease / qq

    @Field(type = FieldType.Double)
    private Double finalScore;      // 排名分

    @Field(type = FieldType.Date)
    private Instant createTime;     // 创建时间
}
