-- ==========================================
-- vibeMusic 数据库索引优化 (Flyway V2)
-- 目标：删除冗余索引 + 补充缺失索引
-- ==========================================

-- ==========================================
-- 1. 删除冗余索引（被联合索引前缀覆盖）
-- ==========================================

-- playlist_song: idx_playlist_id 被 uk_pl_song(playlist_id, source_id) 前缀覆盖
DROP INDEX idx_playlist_id ON playlist_song;

-- user_favorite: idx_user_id 被 uk_user_song(user_id, source_id) 前缀覆盖
DROP INDEX idx_user_id ON user_favorite;

-- play_history: idx_user_id 被 idx_user_played(user_id, played_at) 前缀覆盖
DROP INDEX idx_user_id ON play_history;

-- ==========================================
-- 2. 补充缺失索引
-- ==========================================

-- song 表：歌曲名前缀索引（随机推荐 / 本地搜索使用）
ALTER TABLE song ADD INDEX idx_name (name(50));

-- song 表：创建时间索引（排序查询使用）
ALTER TABLE song ADD INDEX idx_created_at (created_at);

-- ==========================================
-- 索引优化完成
-- ==========================================
SELECT 'v2__optimize 执行完成：删除 3 个冗余索引，新增 2 个索引' AS status;
