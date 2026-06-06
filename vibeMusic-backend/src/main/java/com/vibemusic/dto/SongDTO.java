package com.vibemusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 歌曲 DTO —— 搜索/推荐返回给前端，Redis 缓存
 * <p>
 * 与 Song 实体的区别：本对象不关联数据库，纯内存对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SongDTO {

    /** 网易云歌曲原始 ID */
    private String sourceId;

    /** 歌曲名 */
    private String name;

    /** 歌手名 */
    private String artist;

    /** 专辑名 */
    private String album;

    /** 封面图 URL */
    private String coverUrl;

    /** 时长（秒） */
    private Integer duration;

    /** 平台来源: netease / qq */
    private String platform;

    /** 可播放的来源平台列表，如 ["netease", "qq"] */
    private java.util.List<String> availableSources;

    /** 跨平台去重后的最终排名分（越高越靠前） */
    private Double finalScore;
}
