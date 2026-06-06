package com.vibemusic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/**
 * 歌曲实体（缓存网易云API数据）
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("song")
public class Song extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 网易云歌曲原始ID */
    private String sourceId;

    /** 歌曲名 */
    private String name;

    /** 歌手名 */
    private String artist;

    /** 专辑名 */
    private String album;

    /** 封面图URL */
    private String coverUrl;

    /** 时长（秒） */
    private Integer duration;

    /** 播放地址 */
    private String url;

    /** 歌词 */
    private String lyric;
}
