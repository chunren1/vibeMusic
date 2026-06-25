package com.vibemusic.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 实体基类（自动填充创建/更新时间）
 * MyBatis-Plus: 通过 MetaObjectHandler 自动填充
 */
@Getter
@Setter
public abstract class BaseEntity {

    @TableField(fill = FieldFill.INSERT, insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE, insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
