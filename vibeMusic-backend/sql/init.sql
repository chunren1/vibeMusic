-- ==========================================
-- vibeMusic 数据库完整初始化脚本
-- 用法: mysql -u root -p < init.sql
-- 兼容: MySQL 5.7+ / 8.0+
-- ==========================================

CREATE DATABASE IF NOT EXISTS vibemusic
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE vibemusic;

-- ==========================================
-- 1. 用户表 (users)
-- ==========================================
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    username    VARCHAR(50)  NOT NULL COMMENT '用户名',
    password    VARCHAR(255) NOT NULL COMMENT 'BCrypt加密密码',
    nickname    VARCHAR(50)  COMMENT '昵称',
    avatar      VARCHAR(500) COMMENT '头像URL',
    bg_image    VARCHAR(500) COMMENT '个人页背景图URL',
    gender      VARCHAR(10)  COMMENT '性别: 男/女/保密',
    birthday    VARCHAR(10)  COMMENT '生日: YYYY-MM-DD',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否启用',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 默认管理员 (密码: 123456)
INSERT IGNORE INTO users (username, password, nickname, enabled)
VALUES ('admin', '$2b$10$1/FXBiQlDlBnapQ6PosJO.lv3oj59Zf6j.VVrHao0xASJxcewwlDG', '管理员', TRUE);

-- ==========================================
-- 2. 歌曲缓存表 (song)
-- ==========================================
CREATE TABLE IF NOT EXISTS song (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    source_id   VARCHAR(100)  NOT NULL COMMENT '歌曲源ID(网易云/QQ)',
    name        VARCHAR(200)  NOT NULL COMMENT '歌曲名',
    artist      VARCHAR(200)  NOT NULL COMMENT '歌手名',
    album       VARCHAR(200)  COMMENT '专辑名',
    cover_url   VARCHAR(500)  COMMENT '封面图URL',
    duration    INT           DEFAULT 0 COMMENT '时长(秒)',
    url         VARCHAR(1000) COMMENT '播放地址',
    lyric       TEXT          COMMENT '歌词',
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_source_id (source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='歌曲缓存表';

-- ==========================================
-- 3. 歌单表 (playlist)
-- ==========================================
CREATE TABLE IF NOT EXISTS playlist (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id     BIGINT        NOT NULL COMMENT '用户ID',
    name        VARCHAR(200)  NOT NULL COMMENT '歌单名',
    description VARCHAR(500)  COMMENT '歌单描述',
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='歌单表';

-- ==========================================
-- 4. 歌单歌曲关联表 (playlist_song)
-- ==========================================
CREATE TABLE IF NOT EXISTS playlist_song (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    playlist_id BIGINT        NOT NULL COMMENT '歌单ID',
    source_id   VARCHAR(100)  NOT NULL COMMENT '歌曲源ID',
    song_name   VARCHAR(200)  COMMENT '歌曲名(冗余)',
    artist      VARCHAR(200)  COMMENT '歌手名(冗余)',
    cover_url   VARCHAR(500)  COMMENT '封面图URL(冗余)',
    duration    INT           DEFAULT 0 COMMENT '时长(秒)',
    added_at    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
    UNIQUE KEY uk_pl_song (playlist_id, source_id) COMMENT '同一歌单不重复添加同一首歌',
    INDEX idx_playlist_id (playlist_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='歌单歌曲关联表';

-- ==========================================
-- 5. 用户收藏表 (user_favorite)
-- ==========================================
CREATE TABLE IF NOT EXISTS user_favorite (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id     BIGINT        NOT NULL COMMENT '用户ID',
    source_id   VARCHAR(100)  NOT NULL COMMENT '歌曲源ID',
    song_name   VARCHAR(200)  COMMENT '歌曲名(冗余)',
    artist      VARCHAR(200)  COMMENT '歌手名(冗余)',
    cover_url   VARCHAR(500)  COMMENT '封面图URL(冗余)',
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    UNIQUE KEY uk_user_song (user_id, source_id) COMMENT '同一用户不重复收藏同一首歌',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户收藏表';

-- ==========================================
-- 6. 播放历史表 (play_history)
-- ==========================================
CREATE TABLE IF NOT EXISTS play_history (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id     BIGINT        NOT NULL COMMENT '用户ID',
    source_id   VARCHAR(100)  NOT NULL COMMENT '歌曲源ID',
    song_name   VARCHAR(200)  COMMENT '歌曲名(冗余)',
    artist      VARCHAR(200)  COMMENT '歌手名(冗余)',
    cover_url   VARCHAR(500)  COMMENT '封面图URL(冗余)',
    played_at   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '播放时间',
    INDEX idx_user_id (user_id),
    INDEX idx_user_played (user_id, played_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='播放历史表';

-- ==========================================
-- 初始化完成
-- ==========================================
SELECT 'vibeMusic 数据库初始化完成!' AS status;
SHOW TABLES;
