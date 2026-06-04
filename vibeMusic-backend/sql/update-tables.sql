-- MySQL table migration for Redis cache refactor

DROP TABLE IF EXISTS play_history;

CREATE TABLE play_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    song_name VARCHAR(200),
    artist VARCHAR(200),
    played_at DATETIME,
    INDEX idx_user_id (user_id),
    INDEX idx_played_at (played_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

TRUNCATE TABLE song;
