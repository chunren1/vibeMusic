-- ============================================================
-- vibeMusic 测试环境 Schema (仅 DDL)
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    avatar VARCHAR(500),
    bg_image VARCHAR(500),
    gender VARCHAR(10),
    birthday VARCHAR(20),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS song (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(500) NOT NULL,
    artist VARCHAR(500),
    album VARCHAR(500),
    cover_url VARCHAR(1000),
    duration BIGINT DEFAULT 0,
    url VARCHAR(2000),
    lyric TEXT,
    platform VARCHAR(20) DEFAULT 'netease',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS playlist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    cover_url VARCHAR(1000),
    description VARCHAR(2000),
    is_public BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS playlist_song (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    playlist_id BIGINT NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    song_name VARCHAR(500),
    artist VARCHAR(500),
    cover_url VARCHAR(1000),
    duration INT DEFAULT 0,
    sort_order INT DEFAULT 0,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (playlist_id, source_id)
);

CREATE TABLE IF NOT EXISTS user_favorite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    song_name VARCHAR(500),
    artist VARCHAR(500),
    cover_url VARCHAR(1000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, source_id)
);

CREATE TABLE IF NOT EXISTS play_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    song_name VARCHAR(500),
    artist VARCHAR(500),
    cover_url VARCHAR(1000),
    played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_played ON play_history(user_id, played_at);
