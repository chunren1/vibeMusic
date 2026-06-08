-- ==========================================
-- vibeMusic 数据库初始化脚本
-- 用法: mysql -u root -p < init.sql
-- ==========================================

CREATE DATABASE IF NOT EXISTS vibemusic DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE vibemusic;

-- ==========================================
-- 1. 用户表
-- ==========================================
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    nickname    VARCHAR(50),
    avatar      VARCHAR(200),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 默认管理员 (密码: 123456)
INSERT IGNORE INTO users (username, password, nickname, enabled) VALUES
('admin', '$2b$10$1/FXBiQlDlBnapQ6PosJO.lv3oj59Zf6j.VVrHao0xASJxcewwlDG', '管理员', TRUE);

-- ==========================================
-- 2. 歌曲缓存表
-- ==========================================
CREATE TABLE IF NOT EXISTS song (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    source_id   VARCHAR(100)  NOT NULL UNIQUE,
    name        VARCHAR(200)  NOT NULL,
    artist      VARCHAR(200)  NOT NULL,
    album       VARCHAR(200),
    cover_url   VARCHAR(500),
    duration    INT           DEFAULT 0,
    url         VARCHAR(1000),
    lyric       TEXT,
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==========================================
-- 3. 歌单表
-- ==========================================
CREATE TABLE IF NOT EXISTS playlist (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(500),
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==========================================
-- 4. 歌单歌曲关联表
-- ==========================================
CREATE TABLE IF NOT EXISTS playlist_song (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    playlist_id BIGINT        NOT NULL,
    source_id   VARCHAR(100)  NOT NULL,
    song_name   VARCHAR(200),
    artist      VARCHAR(200),
    cover_url   VARCHAR(500),
    duration    INT           DEFAULT 0,
    added_at    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pl_song (playlist_id, source_id),
    INDEX idx_playlist_id (playlist_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==========================================
-- 5. 用户收藏表
-- ==========================================
CREATE TABLE IF NOT EXISTS user_favorite (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    source_id   VARCHAR(100)  NOT NULL,
    song_name   VARCHAR(200),
    artist      VARCHAR(200),
    cover_url   VARCHAR(500),
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_song (user_id, source_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ==========================================
-- 6. 播放历史表
-- ==========================================
CREATE TABLE IF NOT EXISTS play_history (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    source_id   VARCHAR(100)  NOT NULL,
    song_name   VARCHAR(200),
    cover_url   VARCHAR(500),
    artist      VARCHAR(200),
    played_at   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_played_at (played_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
