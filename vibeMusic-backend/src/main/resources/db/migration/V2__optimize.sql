-- ==========================================
-- vibeMusic 数据库索引优化 (Flyway V2)
-- 目标：删除冗余索引 + 补充缺失索引
-- 幂等写法（V5 风格）：每条 DDL 先查 information_schema，
-- 已是目标态则 SELECT 1 跳过，可安全重放
-- ==========================================

-- ==========================================
-- 1. 删除冗余索引（被联合索引前缀覆盖）
-- ==========================================

-- playlist_song: idx_playlist_id 被 uk_pl_song(playlist_id, source_id) 前缀覆盖
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'playlist_song' AND index_name = 'idx_playlist_id');
SET @stmt := IF(@exist > 0, 'ALTER TABLE playlist_song DROP INDEX idx_playlist_id', 'SELECT 1');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- user_favorite: idx_user_id 被 uk_user_song(user_id, source_id) 前缀覆盖
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'user_favorite' AND index_name = 'idx_user_id');
SET @stmt := IF(@exist > 0, 'ALTER TABLE user_favorite DROP INDEX idx_user_id', 'SELECT 1');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- play_history: idx_user_id 被 idx_user_played(user_id, played_at) 前缀覆盖
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'play_history' AND index_name = 'idx_user_id');
SET @stmt := IF(@exist > 0, 'ALTER TABLE play_history DROP INDEX idx_user_id', 'SELECT 1');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ==========================================
-- 2. 补充缺失索引
-- ==========================================

-- song 表：歌曲名前缀索引（随机推荐 / 本地搜索使用）
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'song' AND index_name = 'idx_name');
SET @stmt := IF(@exist = 0, 'ALTER TABLE song ADD INDEX idx_name (name(50))', 'SELECT 1');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- song 表：创建时间索引（排序查询使用）
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'song' AND index_name = 'idx_created_at');
SET @stmt := IF(@exist = 0, 'ALTER TABLE song ADD INDEX idx_created_at (created_at)', 'SELECT 1');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ==========================================
-- 索引优化完成
-- ==========================================
SELECT 'v2__optimize 执行完成：删除 3 个冗余索引，新增 2 个索引' AS status;
