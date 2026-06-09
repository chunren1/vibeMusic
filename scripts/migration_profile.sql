-- vibeMusic 个人资料重构 - 数据库迁移脚本
-- 运行方式: mysql -u root -p < migration_profile.sql
-- 或连接 MySQL 后: USE vibemusic; source migration_profile.sql;

USE vibemusic;

-- 添加 gender 字段（性别）- 如果已存在会报错，可忽略
ALTER TABLE users ADD COLUMN gender VARCHAR(10) DEFAULT NULL COMMENT '性别: 男/女/保密';

-- 添加 birthday 字段（生日）
ALTER TABLE users ADD COLUMN birthday VARCHAR(10) DEFAULT NULL COMMENT '生日: YYYY-MM-DD';

-- 添加 avatar 字段（头像URL）
ALTER TABLE users ADD COLUMN avatar VARCHAR(500) DEFAULT NULL COMMENT '头像URL';

-- 查看修改后的表结构
DESCRIBE users;
