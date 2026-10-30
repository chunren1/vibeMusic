#!/usr/bin/env bash
# ==========================================
# 存量库补建账号：给「数据目录已存在」的栈补 vibemusic / vibemusic_migrator / vibemusic_backup
# ==========================================
# 背景：docker-entrypoint-initdb.d 只在数据目录为空时执行。本地 docker-data/mysql/data
# 早已存在（存量卷），所以改 compose 之后账号不会自动出现 —— 直接 up 会 1045。
#
# 用法（在仓库根目录）：
#   ./scripts/create-db-users.sh                  # 默认容器 vibemusic-mysql，读 ./.env
#   ./scripts/create-db-users.sh -c 别的容器名
#   ./scripts/create-db-users.sh -f /opt/vibeMusic/.env
#
# 幂等：内部用 CREATE USER IF NOT EXISTS + ALTER USER，可反复执行（同时会把口令同步成 .env 里的值）。
# ==========================================
set -euo pipefail

CONTAINER="vibemusic-mysql"
ENV_FILE=".env"
SCRIPT="vibeMusic-backend/sql/init-users.sh"

while getopts "c:f:" opt; do
  case "$opt" in
    c) CONTAINER="$OPTARG" ;;
    f) ENV_FILE="$OPTARG" ;;
    *) echo "用法: $0 [-c 容器名] [-f env文件]" >&2; exit 2 ;;
  esac
done

[ -f "$ENV_FILE" ] || { echo "[错误] 找不到 $ENV_FILE（用 -f 指定）" >&2; exit 1; }
[ -f "$SCRIPT" ]   || { echo "[错误] 找不到 $SCRIPT（请在仓库根目录执行）" >&2; exit 1; }

# 容器没起就自己起（存量卷场景：不建号直接 up 会在 flyway-init 处 1045，被 depends_on 连带拦住）
if ! docker inspect "$CONTAINER" >/dev/null 2>&1 \
   || [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != "true" ]]; then
  echo "==> 容器 $CONTAINER 未运行，先起 mysql（等 healthy，上限 120s）"
  docker compose up -d mysql
  for _ in $(seq 1 60); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" == "healthy" ]] && break
    sleep 2
  done
  [[ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" == "healthy" ]] \
    || { echo "[错误] $CONTAINER 120s 内未 healthy，先看 docker logs $CONTAINER" >&2; exit 1; }
fi

# 只取需要的键，不 source 整个 .env（避免执行里面的任何内容）
get() { sed -n "s/^$1=//p" "$ENV_FILE" | head -1; }

MYSQL_ROOT_PASSWORD=$(get MYSQL_ROOT_PASSWORD)
DB_PASSWORD=$(get DB_PASSWORD)
FLYWAY_PASSWORD=$(get FLYWAY_PASSWORD)
BACKUP_PASSWORD=$(get BACKUP_PASSWORD || true)

for v in MYSQL_ROOT_PASSWORD DB_PASSWORD FLYWAY_PASSWORD; do
  [ -n "${!v}" ] || { echo "[错误] $ENV_FILE 里缺少 $v" >&2; exit 1; }
done

echo "==> 把建号脚本送进容器 $CONTAINER"
docker cp "$SCRIPT" "$CONTAINER:/tmp/02-users.sh"

echo "==> 执行（幂等）"
docker exec \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e FLYWAY_PASSWORD="$FLYWAY_PASSWORD" \
  -e BACKUP_PASSWORD="$BACKUP_PASSWORD" \
  "$CONTAINER" bash /tmp/02-users.sh

echo "==> 复核权限"
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" mysql -uroot -N -e "
  SELECT user, host FROM mysql.user WHERE user LIKE 'vibemusic%' ORDER BY user;
  SHOW GRANTS FOR 'vibemusic'@'%';
  SHOW GRANTS FOR 'vibemusic_migrator'@'%';"

echo "==> 完成。可以重新起后端：docker compose up -d backend（云上勿直接 up，走蓝绿脚本）"
