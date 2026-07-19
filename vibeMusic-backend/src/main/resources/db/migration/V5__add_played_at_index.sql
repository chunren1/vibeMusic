-- ==========================================
-- vibeMusic 迁移 V5: played_at 索引补充
-- 覆盖 V3 重命名后的缺失变更（幂等迁移）
-- ==========================================

-- 为 play_history.played_at 加索引（加速定时清理查询）
SET @index_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'play_history'
    AND INDEX_NAME = 'idx_played_at'
);

SET @sql = IF(@index_exists = 0,
  'ALTER TABLE play_history ADD INDEX idx_played_at (played_at)',
  'SELECT "idx_played_at already exists" AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT 'v5__add_played_at_index 执行完成' AS status;
