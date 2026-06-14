package com.vibemusic.doc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.util.Date;

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
    private String keyword;

    @Field(type = FieldType.Keyword)
    private String songId;

    @Field(type = FieldType.Text, index = false)
    private String name;

    @Field(type = FieldType.Text, index = false)
    private String artist;

    @Field(type = FieldType.Text, index = false)
    private String album;

    @Field(type = FieldType.Keyword, index = false)
    private String coverUrl;

    @Field(type = FieldType.Integer)
    private Integer duration;

    @Field(type = FieldType.Keyword)
    private String source;

    @Field(type = FieldType.Double)
    private Double finalScore;

    @Field(type = FieldType.Date)
    private Date createTime;
}
