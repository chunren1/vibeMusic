package com.vibemusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendResult {

    /** 推荐歌曲列表 */
    private List<SongDTO> songs;

    /** 动态欢迎语 */
    private String greeting;

    /** 推荐理由（如："因为你最近喜欢周杰伦的《晴天》"） */
    private String reason;

    /** 推荐类型：personalized / random */
    private String type;
}
