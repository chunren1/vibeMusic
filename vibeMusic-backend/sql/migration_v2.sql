-- ============================================================
-- vibeMusic v2 数据库补丁 (2026-07-08)
-- 为已有数据库添加缺失索引，幂等（IF NOT EXISTS）
-- 用法: mysql -u root -p vibemusic < migration_v2.sql
-- ============================================================

USE vibemusic;

-- 播放历史复合索引（按用户 + 时间查询）
CREATE INDEX IF NOT EXISTS idx_play_history_user_played
  ON play_history(user_id, played_at DESC);

-- 用户收藏去重唯一索引（防止并发重复插入）
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_favorite
  ON user_favorite(user_id, source_id);

-- 歌曲 source_id 唯一索引（6+ 处查询依赖）
CREATE UNIQUE INDEX IF NOT EXISTS uk_song_source_id
  ON song(source_id);

SELECT 'vibeMusic v2 migration completed!' AS status;
SHOW INDEX FROM play_history WHERE Key_name LIKE 'idx_play_history%';
SHOW INDEX FROM user_favorite WHERE Key_name LIKE 'uk_user%';
SHOW INDEX FROM song WHERE Key_name LIKE 'uk_song%';
