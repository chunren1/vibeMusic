-- ==========================================
-- V3: 歌单增加封面、排序字段（幂等迁移）
-- ==========================================

-- MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，
-- 用存储过程检查列是否存在后再执行
DROP PROCEDURE IF EXISTS migrate_v3;
DELIMITER //
CREATE PROCEDURE migrate_v3()
BEGIN
    DECLARE col_count INT;

    -- 检查 cover_url 列
    SELECT COUNT(*) INTO col_count FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'playlist' AND COLUMN_NAME = 'cover_url';
    IF col_count = 0 THEN
        ALTER TABLE playlist ADD COLUMN cover_url VARCHAR(500) DEFAULT '' COMMENT '封面图URL' AFTER description;
    END IF;

    -- 检查 sort_order 列
    SELECT COUNT(*) INTO col_count FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'playlist' AND COLUMN_NAME = 'sort_order';
    IF col_count = 0 THEN
        ALTER TABLE playlist ADD COLUMN sort_order INT DEFAULT 0 COMMENT '排序序号(越小越前)' AFTER cover_url;
    END IF;

    -- 检查 idx_user_sort 索引
    SELECT COUNT(*) INTO col_count FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'playlist' AND INDEX_NAME = 'idx_user_sort';
    IF col_count = 0 THEN
        ALTER TABLE playlist ADD INDEX idx_user_sort (user_id, sort_order, created_at);
    END IF;
END //
DELIMITER ;

CALL migrate_v3();
DROP PROCEDURE IF EXISTS migrate_v3;
