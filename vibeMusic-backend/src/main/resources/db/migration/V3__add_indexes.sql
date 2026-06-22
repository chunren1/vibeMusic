-- ============================================================
-- V3: 按艺术家搜索索引 + 删除冗余 SHOW TABLES + url 字段扩容
-- ============================================================

-- 1. 艺术家索引（按歌手搜索是高频操作）
ALTER TABLE song ADD INDEX idx_artist (artist(100));

-- 2. song.url 扩容到 2048（含签名参数的 CDN URL 常见 1500+ 字符）
ALTER TABLE song MODIFY COLUMN url VARCHAR(2048) DEFAULT NULL;

-- 3. play_history 冗余索引清理（不存在按全局 played_at 排序的需求）
-- 先检查索引是否存在再删除（防止重复执行报错）
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'play_history' AND index_name = 'idx_played_at');
SET @stmt := IF(@exist > 0, 'ALTER TABLE play_history DROP INDEX idx_played_at', 'SELECT 1');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
