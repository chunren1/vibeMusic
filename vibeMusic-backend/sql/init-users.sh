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

# ── 收紧历史权限（2026-11-01）───────────────────────────────────────────────
# 老版本只 GRANT 不 REVOKE：账号权限一旦被手工放大过（GRANT ALL、WITH GRANT OPTION），
# 重跑本脚本不会收回，等于「最小权限」只是首次建号时的承诺。
# 失败方向是刻意的「宁可收不干净」：GRANT 全在前面先落，这里只撤多余的单项权限，
# 绝不 REVOKE ALL 再重发 —— 后者一旦语句出错，账号会被清空，后端当场 1045。
# 只处理库级权限（vibemusic.*）；全局权限不归这个脚本管。
#
# MySQL 8 库级权限全集：SHOW GRANTS 对 `GRANT ALL ON db.*` 只回一行 "ALL PRIVILEGES"，
# 看不出具体项，所以这里按全集展开再求差集（2026-11-01 在 mysql:8.0.46 实测）。
ALL_DB_PRIVS="SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|REFERENCES|INDEX|ALTER|CREATE TEMPORARY TABLES|LOCK TABLES|EXECUTE|CREATE VIEW|SHOW VIEW|CREATE ROUTINE|ALTER ROUTINE|EVENT|TRIGGER"

db_privs() {  # $1=账号 → 现有库级权限，一行一个
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot -N \
    -e "SHOW GRANTS FOR '$1'@'%'" 2>/dev/null \
    | sed -n 's/^GRANT \(.*\) ON `vibemusic`\.\* TO .*/\1/p' \
    | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | sort -u
}
has_grant_option() {  # $1=账号 → 库级是否带 WITH GRANT OPTION
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot -N \
    -e "SHOW GRANTS FOR '$1'@'%'" 2>/dev/null \
    | grep -q 'ON `vibemusic`\.\* TO .*WITH GRANT OPTION'
}
revoke_extras() {  # $1=账号，其余=应保留的权限；只输出要执行的 REVOKE
  local acct="$1"; shift
  local keep=" $* " extra
  extra=$(db_privs "$acct" | awk -v keep="$keep" 'NF && index(keep, " " $0 " ") == 0' | paste -sd, -)
  case "$extra" in
    *ALL\ PRIVILEGES*)
      # 展开成全集的差集；单条 REVOKE 撤掉具体项，账号仍保有一开始 GRANT 的那套
      extra=$(printf '%s\n' "$ALL_DB_PRIVS" | tr '|' '\n' \
        | awk -v keep="$keep" 'index(keep, " " $0 " ") == 0' | paste -sd, -)
      ;;
  esac
  if [ -n "$extra" ]; then
    echo "-- 收紧 $acct：撤销历史多余的库级权限（$extra）"
    echo "REVOKE $extra ON vibemusic.* FROM '$acct'@'%';"
  fi
  if has_grant_option "$acct"; then
    echo "-- 收紧 $acct：撤销库级 WITH GRANT OPTION（否则它能把自己的权限转授出去）"
    echo "REVOKE GRANT OPTION ON vibemusic.* FROM '$acct'@'%';"
  fi
}

# 差集要在 SQL 块之前算好：此时读到的还是「GRANT 之前」的权限，减掉应保留项就是历史多余项。
REVOKE_SQL=$(
  revoke_extras vibemusic SELECT INSERT UPDATE DELETE
  revoke_extras vibemusic_migrator SELECT INSERT UPDATE DELETE CREATE ALTER DROP INDEX REFERENCES CREATE ROUTINE ALTER ROUTINE EXECUTE
  if [ -n "${BACKUP_PASSWORD:-}" ]; then
    revoke_extras vibemusic_backup SELECT SHOW VIEW TRIGGER EVENT LOCK TABLES
  fi
)

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

  # 收紧历史权限放在最后：这段若因语法/解析问题出错，前面账号与口令已经就绪，
  # 后端不会因为「权限没收干净」而起不来（最坏只是权限仍偏大，属可接受的失败方向）。
  if [ -n "$REVOKE_SQL" ]; then
    echo "-- 收紧历史权限（只撤销多余项，需要的权限已在上面 GRANT 过）"
    printf '%s\n' "$REVOKE_SQL"
  fi

  echo "FLUSH PRIVILEGES;"
} | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot

echo "[02-users] 已建号：vibemusic(DML) / vibemusic_migrator(DML+DDL)${BACKUP_PASSWORD:+ / vibemusic_backup(只读备份)}"
if [ -n "$REVOKE_SQL" ]; then
  echo "[02-users] 已收紧历史权限（明细如下，需要的权限不受影响）：" >&2
  printf '%s\n' "$REVOKE_SQL" | grep '^--' | sed 's/^-- /           /' >&2
fi

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
