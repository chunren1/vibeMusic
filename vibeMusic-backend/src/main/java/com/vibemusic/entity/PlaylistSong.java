package com.vibemusic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@TableName("playlist_song")
public class PlaylistSong {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long playlistId;

    private String sourceId;

    private String songName;

    private String artist;

    private String coverUrl;

    private Integer duration;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime addedAt;
}
