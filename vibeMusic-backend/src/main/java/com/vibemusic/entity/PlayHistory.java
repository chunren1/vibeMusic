package com.vibemusic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@TableName("play_history")
public class PlayHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String sourceId;
    private String songName;
    private String coverUrl;
    private String artist;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime playedAt;
}
