DROP TABLE IF EXISTS user_favorite;

CREATE TABLE user_favorite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_id VARCHAR(100) NOT NULL COMMENT '网易云歌曲ID',
    song_name VARCHAR(200) COMMENT '歌曲名',
    artist VARCHAR(200) COMMENT '歌手名',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_source (user_id, source_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏表';
