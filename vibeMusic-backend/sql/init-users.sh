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
# 边界（2026-10-30 更新）：DDL 凭证只出现在**一次性迁移容器**里（compose 的
# flyway-init 段 + scripts/deploy-backend.sh 的 4/9 步），长期运行的 backend 已置
# SPRING_FLYWAY_ENABLED=false 且不再持有 FLYWAY_*。因此「后端被攻破也改不了表结构」
# 成立的前提是迁移容器退出后不留 env 文件 —— 部署脚本用 600 权限临时 env-file，
# 跑完即删。诚实补一句：migrator 口令本身仍在云端 /opt/vibeMusic/.env 里，
# 能读宿主机的人照样拿得到，这一层防的是应用层 RCE，不是主机失守。
# ==========================================
# 注意：镜像的 docker-entrypoint 对非可执行 .sh 是 `source` 执行（不会 fork），
# 所以这里不能 `set -u` —— 会把 -u 泄漏给 entrypoint 后续步骤。`-e` 是它本来就有的。
set -eo pipefail

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 未传入}"
: "${DB_PASSWORD:?DB_PASSWORD 未传入（运行账号口令）}"
: "${FLYWAY_PASSWORD:?FLYWAY_PASSWORD 未传入（迁移账号口令）}"

# SQL 单引号/反斜杠转义：口令里出现 ' 或 \ 时不能把语句截断或改变字面值
# （MySQL 默认开启反斜杠转义；把 \ 写成 \\ 才能得到原字符）
sql_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g"; }

# 换行/回车会把 SQL 语句截断，直接拒绝（.env 里也用不到多行口令）
# 注意 BACKUP_PASSWORD 是可选项，但一旦提供就同样要过这个闸
case "${DB_PASSWORD}${FLYWAY_PASSWORD}${BACKUP_PASSWORD:-}" in
  *$'\n'*|*$'\r'*) echo "[02-users] 口令不能包含换行/回车" >&2; exit 1 ;;
esac

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
  # CREATE/ALTER ROUTINE + EXECUTE：V3/V6 用 CREATE PROCEDURE 做幂等迁移（先建临时过程、改完 DROP）。
  # 少了这三项，Flyway 在**空库**上跑会死在 V3：`alter routine command denied ... migrate_v3`
  # （2026-10-29 实测；云上没炸只是因为 schema 早已 up to date，属于「迟早炸」）。
  echo "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE ROUTINE, ALTER ROUTINE, EXECUTE ON vibemusic.* TO 'vibemusic_migrator'@'%';"

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

# 只有由 initdb 自动触发时才提示「存量库怎么补」，手工执行时不必再自我指路。
# 判据用 BASH_SOURCE 而不是 $0：若挂载进来的文件丢了可执行位（umask 不对、Windows 侧克隆等），
# mysql 官方 entrypoint 会 `. "$f"` **source** 执行，此时 $0 仍是 /usr/local/bin/docker-entrypoint.sh，
# 老的 `case "$0"` 就漏判了（2026-11-01 修）。仓库里现在是 755，走的是 `"$f"` 执行分支，
# 两种情形 ${BASH_SOURCE:-$0} 都指向本文件，所以行为只会更稳、不会变。
# 写成 ${BASH_SOURCE:-$0}（不写下标）是刻意的：dash 里没有 BASH_SOURCE，加下标会 Bad substitution 直接炸。
case "${BASH_SOURCE:-$0}" in
  /docker-entrypoint-initdb.d/*)
    echo "[02-users] 其它已存在的库（initdb 不会再跑）补建账号："
    echo "           ./scripts/create-db-users.sh        # 在仓库根目录，读 ./.env，docker cp + exec 本脚本"
    ;;
esac
