package com.vibemusic.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 歌曲实体（缓存网易云API数据）
 */
@Entity
@Table(name = "song")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Song extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 网易云歌曲原始ID */
    @Column(name = "source_id", length = 100, nullable = false, unique = true)
    private String sourceId;

    /** 歌曲名 */
    @Column(length = 200, nullable = false)
    private String name;

    /** 歌手名 */
    @Column(length = 200, nullable = false)
    private String artist;

    /** 专辑名 */
    @Column(length = 200)
    private String album;

    /** 封面图URL */
    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    /** 时长（秒） */
    private Integer duration;

    /** 播放地址 */
    @Column(length = 1000)
    private String url;

    /** 歌词 */
    @Column(columnDefinition = "TEXT")
    private String lyric;
}
