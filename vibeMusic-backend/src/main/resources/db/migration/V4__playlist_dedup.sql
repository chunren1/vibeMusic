-- ==========================================
-- V4: 歌单去重 — 同一用户不允许重复歌单名
-- ==========================================

-- 1. 清理重复歌单（保留最早创建的，删掉后来的重复）
DELETE p
FROM playlist p
JOIN (
    SELECT user_id, name, MIN(id) AS keep_id
    FROM playlist
    GROUP BY user_id, name
    HAVING COUNT(*) > 1
) AS dup ON p.user_id = dup.user_id AND p.name = dup.name AND p.id != dup.keep_id;

-- 2. 添加唯一索引（防未来重复；幂等：存在则跳过，DELETE 本身无重复时是空操作）
SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'playlist' AND index_name = 'uk_user_playlist');
SET @stmt := IF(@exist = 0, 'ALTER TABLE playlist ADD UNIQUE KEY uk_user_playlist (user_id, name)', 'SELECT 1');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
