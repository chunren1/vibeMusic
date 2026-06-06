package com.vibemusic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_favorite")
public class UserFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String sourceId;

    private String songName;

    private String coverUrl;

    private String artist;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
