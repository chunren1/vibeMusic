-- 修复 play_history.played_at 的历史时区偏移（一次性数据修复）
--
-- 背景（2026-10-26 线上事故）：
--   play_history.played_at 由 Java 的 LocalDateTime.now() 填充（PlayHistoryService + MetaObjectHandler），
--   而 backend 容器当时没有声明 TZ（JVM 走 UTC），mysql 容器却是 CST(+08:00)。
--   结果是全部历史行的 played_at 都比真实时间早 8 小时，App「最近播放」原样打印该字符串 → 时间全错。
--   同一行的 created_at 走 DB 的 DEFAULT CURRENT_TIMESTAMP（CST）反而是对的。
--
-- 修复分两层：
--   1) 部署层：Dockerfile ENV TZ=Asia/Shanghai + 三份 compose 显式 TZ（CI 有守卫，缺了直接红）
--      —— 从此新写入的 played_at 就是 CST；
--   2) 数据层：本迁移把事故期间写入的历史行整体 +8 小时（Flyway 只执行一次，天然幂等）。
--
-- 为什么全表平移而不是按条件筛选：
--   played_at 的写入路径只有 Java 一条（实体上是 fill = INSERT_UPDATE，没有 FieldStrategy.NEVER，
--   即每次 INSERT 都显式带值），所以事故期间所有行都是 UTC，不存在"部分是 CST"的混合情况。
--   首次发版前已用 mysqldump 备份整表。
--
-- 注意：updated_at 仍是混合时区（INSERT 走 DB 默认 CST、UPDATE 由 Java 填 UTC），无法可靠区分，
--       本次不处理；它不参与任何用户可见的时间展示。

UPDATE play_history
SET played_at = played_at + INTERVAL 8 HOUR;
