package com.vibemusic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@TableName("user_search_history")
public class UserSearchHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String keyword;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime searchedAt;
}
