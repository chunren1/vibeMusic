-- ==========================================
-- V2: 歌单增加封面、排序字段 + 播放历史导出支持
-- ==========================================

-- 歌单表增加字段
ALTER TABLE playlist
    ADD COLUMN cover_url VARCHAR(500) DEFAULT '' COMMENT '封面图URL' AFTER description,
    ADD COLUMN sort_order INT DEFAULT 0 COMMENT '排序序号(越小越前)' AFTER cover_url,
    ADD INDEX idx_user_sort (user_id, sort_order, created_at);
