package com.vibemusic.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "playlist_song", uniqueConstraints =
    @UniqueConstraint(columnNames = {"playlist_id", "source_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PlaylistSong {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;

    @Column(name = "source_id", length = 100, nullable = false)
    private String sourceId;

    @Column(name = "song_name", length = 200)
    private String songName;

    @Column(length = 200)
    private String artist;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    @Column
    private Integer duration;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    @PrePersist
    public void prePersist() {
        if (addedAt == null) addedAt = LocalDateTime.now();
    }
}
