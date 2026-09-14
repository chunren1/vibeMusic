-- ==========================================
-- V6: playlist_song 增加平台字段（幂等迁移）
-- 原生 App 需要 name + platform 播放，Web 端继续用 songName
-- ==========================================

-- MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，
-- 用存储过程检查列是否存在后再执行（同 V3 风格）
DROP PROCEDURE IF EXISTS migrate_v6;
DELIMITER //
CREATE PROCEDURE migrate_v6()
BEGIN
    DECLARE col_count INT;

    -- 检查 platform 列
    SELECT COUNT(*) INTO col_count FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'playlist_song' AND COLUMN_NAME = 'platform';
    IF col_count = 0 THEN
        ALTER TABLE playlist_song ADD COLUMN platform VARCHAR(32) NOT NULL DEFAULT 'netease' COMMENT '歌曲平台(netease/qq) 原生App播放用' AFTER duration;
    END IF;

    -- 存量数据回填为 netease（新列默认值已覆盖，仅兜底历史 NULL/空串）
    UPDATE playlist_song SET platform = 'netease' WHERE platform IS NULL OR platform = '';
END //
DELIMITER ;

CALL migrate_v6();
DROP PROCEDURE IF EXISTS migrate_v6;
