-- ============================================
-- 用户表 & 认证系统初始化
-- ============================================

-- 创建用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    avatar VARCHAR(200),
    enabled BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 插入默认用户（密码: 123456，BCrypt 加密）
-- 生产环境请修改密码
-- 如果已存在错误的旧 admin 用户，请先 DELETE FROM users WHERE username='admin' 再执行
INSERT IGNORE INTO users (username, password, nickname, enabled) VALUES
('admin', '$2b$10$1/FXBiQlDlBnapQ6PosJO.lv3oj59Zf6j.VVrHao0xASJxcewwlDG', '管理员', TRUE);
