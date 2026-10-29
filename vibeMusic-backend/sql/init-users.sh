#!/bin/bash
# ==========================================
# 首次初始化建号脚本（docker-entrypoint-initdb.d/02-users.sh）
# ==========================================
# 由 mysql 官方镜像在**数据目录为空**时自动执行（和 01-init.sql 同一批）。
# 已有数据的库不会执行 —— 存量库请手工跑一遍本脚本（见文件末尾说明）。
#
# 为什么需要它：三份 compose 的后端都用 DB_USERNAME=vibemusic（DML-only），
# Flyway 用 vibemusic_migrator（DML+DDL）。这两个账号以前只在云上手敲过，
# 仓库里没有任何建号脚本 —— 新起的本地栈会因为「账号不存在」连不上库
# （2026-10-29 外部复审 M2）。
#
# 三个账号的分工与边界（诚实版，别夸大收益）：
#   vibemusic           运行账号，只有 SELECT/INSERT/UPDATE/DELETE
#                       → 应用代码手滑或 SQL 注入走到这个连接，改不了表结构
#   vibemusic_migrator  迁移账号，DML + DDL，只给 Flyway 用
#                       → 迁移失败/锁表不会占用运行账号的连接
#   vibemusic_backup    备份账号，只读（mysqldump 所需权限）
#                       → 备份容器不需要 root（以前用 -u root 走 TCP，
#                         而 root@'%' 在生产已被删除，备份会直接失败）
#
# ⚠️ 不是「应用被攻破也改不了表结构」：migrator 口令通过 SPRING_FLYWAY_PASSWORD
#    注入后端**同一个容器**，RCE 后读 process.env 就能拿到，照样能 DDL。
#    真要做到那一点，得把 Flyway 拆成独立的 init job（未实施，见
#    docs/2026-10-26-takeover-review.md §8.4）。
# ==========================================
# 注意：镜像的 docker-entrypoint 对非可执行 .sh 是 `source` 执行（不会 fork），
# 所以这里不能 `set -u` —— 会把 -u 泄漏给 entrypoint 后续步骤。`-e` 是它本来就有的。
set -eo pipefail

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 未传入}"
: "${DB_PASSWORD:?DB_PASSWORD 未传入（运行账号口令）}"
: "${FLYWAY_PASSWORD:?FLYWAY_PASSWORD 未传入（迁移账号口令）}"

# SQL 单引号转义：口令里出现 ' 时不能把语句截断
sql_escape() { printf '%s' "$1" | sed "s/'/''/g"; }

DB_PW=$(sql_escape "$DB_PASSWORD")
FLYWAY_PW=$(sql_escape "$FLYWAY_PASSWORD")

{
  echo "-- 运行账号：只给 DML"
  echo "CREATE USER IF NOT EXISTS 'vibemusic'@'%' IDENTIFIED BY '${DB_PW}';"
  echo "ALTER USER 'vibemusic'@'%' IDENTIFIED BY '${DB_PW}';"
  echo "GRANT SELECT, INSERT, UPDATE, DELETE ON vibemusic.* TO 'vibemusic'@'%';"

  echo "-- 迁移账号：DML + DDL（Flyway 建表/改表用）"
  echo "CREATE USER IF NOT EXISTS 'vibemusic_migrator'@'%' IDENTIFIED BY '${FLYWAY_PW}';"
  echo "ALTER USER 'vibemusic_migrator'@'%' IDENTIFIED BY '${FLYWAY_PW}';"
  echo "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON vibemusic.* TO 'vibemusic_migrator'@'%';"

  # 备份账号可选：只有带 mysql-backup 服务的栈（docker-compose.yml / .full.yml）才传这个变量。
  # 云上 demo 栈不跑备份容器，不传就跳过，避免为一个用不到的账号增加必填项。
  if [ -n "${BACKUP_PASSWORD:-}" ]; then
    BACKUP_PW=$(sql_escape "$BACKUP_PASSWORD")
    echo "-- 备份账号：mysqldump --single-transaction --routines --triggers 所需的最小权限"
    echo "CREATE USER IF NOT EXISTS 'vibemusic_backup'@'%' IDENTIFIED BY '${BACKUP_PW}';"
    echo "ALTER USER 'vibemusic_backup'@'%' IDENTIFIED BY '${BACKUP_PW}';"
    echo "GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON vibemusic.* TO 'vibemusic_backup'@'%';"
  fi

  echo "FLUSH PRIVILEGES;"
} | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot

echo "[02-users] 已建号：vibemusic(DML) / vibemusic_migrator(DML+DDL)${BACKUP_PASSWORD:+ / vibemusic_backup(只读备份)}"

# 只有由 initdb 自动触发时才提示「存量库怎么补」，手工执行时不必再自我指路
case "$0" in
  /docker-entrypoint-initdb.d/*)
    echo "[02-users] 其它已存在的库（initdb 不会再跑）补建账号："
    echo "           ./scripts/create-db-users.sh        # 在仓库根目录，读 ./.env，docker cp + exec 本脚本"
    ;;
esac
