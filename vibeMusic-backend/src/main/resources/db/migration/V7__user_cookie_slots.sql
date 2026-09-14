-- ==========================================
-- V7: users 表增加 per-user cookie 槽位（BYOC Phase 1）
-- 云端 Flyway 未启用，线上需按 docs/byoc-handapply.sql 手工执行
-- 纯 ALTER TABLE 写法，兼容 MySQL 8.0 与 H2 MODE=MySQL
-- V1–V6 保持不动
-- ==========================================

-- 网易云 cookie 密文（AES-GCM 输出 base64），NULL=未绑定
ALTER TABLE users ADD COLUMN netease_cookie_enc TEXT NULL;
-- 网易云 cookie 加密向量/盐，NULL=未绑定
ALTER TABLE users ADD COLUMN netease_cookie_iv VARCHAR(32) NULL;
-- 网易云 cookie 最近更新时间，NULL=从未更新
ALTER TABLE users ADD COLUMN netease_cookie_updated_at DATETIME NULL;
-- 网易云 cookie 有效性：NULL=未知，1=有效，0=失效
ALTER TABLE users ADD COLUMN netease_cookie_valid TINYINT(1) NULL;
-- B 站 SESSDATA 密文槽位（预留，Phase 1 无任何读写）
ALTER TABLE users ADD COLUMN bili_sessdata_enc TEXT NULL;
