package com.vibemusic.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 播放历史 —— 不再依赖 DB song 表的 id，直接用网易云 sourceId
 */
@Entity
@Table(name = "play_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 网易云歌曲 ID（不依赖 song 表） */
    @Column(name = "source_id", length = 100, nullable = false)
    private String sourceId;

    /** 歌曲名（冗余存一份，即使 Redis 过期也能展示） */
    @Column(name = "song_name", length = 200)
    private String songName;

    @Column(name = "cover_url", length = 500)


    private String coverUrl;



    /** 歌手名 */


    @Column(name = "artist", length = 200)


    private String artist;

    @Column(name = "played_at")
    private LocalDateTime playedAt;

    @PrePersist
    public void prePersist() {
        if (playedAt == null) {
            playedAt = LocalDateTime.now();
        }
    }
}
