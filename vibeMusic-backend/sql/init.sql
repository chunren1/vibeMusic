-- ==========================================
-- vibeMusic 数据库建表脚本
-- 数据库名: vibemusic (application.yml 中已配置)
-- ==========================================

CREATE DATABASE IF NOT EXISTS vibemusic DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE vibemusic;

-- ==========================================
-- 1. 用户表
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY  COMMENT '用户ID',
    username        VARCHAR(50)     NOT NULL UNIQUE             COMMENT '用户名（登录用）',
    password        VARCHAR(255)    NOT NULL                    COMMENT '密码（BCrypt 加密）',
    nickname        VARCHAR(50)     DEFAULT NULL                COMMENT '昵称（显示用）',
    avatar          VARCHAR(500)    DEFAULT NULL                COMMENT '头像URL',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ==========================================
-- 2. 歌曲表（缓存网易云API获取的歌曲信息）
-- ==========================================
CREATE TABLE IF NOT EXISTS song (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY  COMMENT '歌曲ID（本地自增）',
    source_id       VARCHAR(100)    NOT NULL                    COMMENT '网易云歌曲ID',
    name            VARCHAR(200)    NOT NULL                    COMMENT '歌曲名',
    artist          VARCHAR(200)    NOT NULL                    COMMENT '歌手名',
    album           VARCHAR(200)    DEFAULT NULL                COMMENT '专辑名',
    cover_url       VARCHAR(500)    DEFAULT NULL                COMMENT '封面图URL',
    duration        INT             DEFAULT 0                   COMMENT '时长（秒）',
    url             VARCHAR(1000)   DEFAULT NULL                COMMENT '播放地址',
    lyric           TEXT            DEFAULT NULL                COMMENT '歌词',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    UNIQUE KEY uk_source_id (source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌曲表';

-- ==========================================
-- 3. 歌单表
-- ==========================================
CREATE TABLE IF NOT EXISTS playlist (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY  COMMENT '歌单ID',
    name            VARCHAR(100)    NOT NULL                    COMMENT '歌单名',
    description     VARCHAR(500)    DEFAULT NULL                COMMENT '描述',
    cover_url       VARCHAR(500)    DEFAULT NULL                COMMENT '封面图URL',
    user_id         BIGINT          NOT NULL                    COMMENT '创建者ID',
    is_public       TINYINT(1)      DEFAULT 1                   COMMENT '是否公开 1=公开 0=私有',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌单表';

-- ==========================================
-- 4. 歌单歌曲关联表
-- ==========================================
CREATE TABLE IF NOT EXISTS playlist_song (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY  COMMENT '关联ID',
    playlist_id     BIGINT          NOT NULL                    COMMENT '歌单ID',
    song_id         BIGINT          NOT NULL                    COMMENT '歌曲ID',
    sort_order      INT             DEFAULT 0                   COMMENT '排序',
    added_at        DATETIME        DEFAULT CURRENT_TIMESTAMP   COMMENT '添加时间',
    UNIQUE KEY uk_pl_song (playlist_id, song_id),
    INDEX idx_playlist_id (playlist_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='歌单歌曲关联表';

-- ==========================================
-- 5. 用户喜欢表
-- ==========================================
CREATE TABLE IF NOT EXISTS user_favorite (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY  COMMENT 'ID',
    user_id         BIGINT          NOT NULL                    COMMENT '用户ID',
    song_id         BIGINT          NOT NULL                    COMMENT '歌曲ID',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP   COMMENT '收藏时间',
    UNIQUE KEY uk_user_song (user_id, song_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户喜欢/收藏表';

-- ==========================================
-- 6. 播放历史表
-- ==========================================
CREATE TABLE IF NOT EXISTS play_history (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY  COMMENT 'ID',
    user_id         BIGINT          NOT NULL                    COMMENT '用户ID',
    song_id         BIGINT          NOT NULL                    COMMENT '歌曲ID',
    played_at       DATETIME        DEFAULT CURRENT_TIMESTAMP   COMMENT '播放时间',
    INDEX idx_user_played (user_id, played_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='播放历史表';

-- ==========================================
-- 初始化: 插入一条测试用户 (密码: 123456)
-- ==========================================
-- INSERT INTO sys_user (username, password, nickname) VALUES ('admin', '$2a$10$...', '管理员');
