-- ==========================================
-- V8: user_search_history 表（搜索历史云同步）
-- 幂等写法（V5/V7 风格）：表已存在则跳过，可安全重放；
-- 云端 V1–V7 已由 Flyway 管理，本迁移在其上顺序执行；
-- 每用户上限 50 条由应用层裁剪（SearchHistoryService），此处只建结构
-- V1–V7 保持不动
-- ==========================================

CREATE TABLE IF NOT EXISTS user_search_history (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id     BIGINT        NOT NULL COMMENT '用户ID',
    keyword     VARCHAR(200)  NOT NULL COMMENT '搜索关键词',
    searched_at DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '搜索时间',
    UNIQUE KEY uk_user_keyword (user_id, keyword) COMMENT '同一用户同一关键词只保留一条（upsert 依据）',
    INDEX idx_user_searched (user_id, searched_at DESC) COMMENT '按用户拉取最近搜索'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户搜索历史表';

-- 防御性索引补齐（若表由旧手工脚本建出缺索引时补上，V5 风格幂等）
SET @index_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user_search_history'
    AND INDEX_NAME = 'idx_user_searched'
);

SET @sql = IF(@index_exists = 0,
  'ALTER TABLE user_search_history ADD INDEX idx_user_searched (user_id, searched_at DESC)',
  'SELECT "idx_user_searched already exists" AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT 'v8__user_search_history 执行完成' AS status;
