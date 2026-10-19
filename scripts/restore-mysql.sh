#!/usr/bin/env bash
# restore-mysql.sh — 把 mysqldump 备份**安全地恢复到临时库**，用于演练 / 核对备份可用性。
#
# 为什么需要它（改动前先读 docs/RESTORE-DRILL.md）：
#   backup-mysql.sh 用 `--databases vibemusic` 导出，dump 里因此含
#   `CREATE DATABASE IF NOT EXISTS vibemusic` + `USE vibemusic`；mysqldump 默认还带
#   `--add-drop-table`，每张表前都有 `DROP TABLE IF EXISTS`。
#   把这种 dump 直接灌回一个已有数据的实例 = 对每张表「删表→重建→回灌」，
#   备份之后产生的数据（注册/收藏/播放历史）和 flyway_schema_history 会**不可逆丢失**。
#   本脚本用流式管道剥离这三类语句，并把库名统一改写为目标临时库。
#
# 红线：
#   1) 目标库名 == vibemusic -> 直接拒绝退出（exit 2）。
#   2) 剥离后若仍能扫到 DROP TABLE/VIEW/DATABASE 或 USE/CREATE DATABASE -> 拒绝（exit 2）。
#   3) 默认只恢复进一次性容器（--mode ephemeral）或本机 loopback；连远程必须显式 --allow-remote。
# 退出码：0 成功 / 1 运行期失败 / 2 守卫拒绝 / 3 参数或前置条件错误 / 4 灌库失败 / 5 恢复后无表
set -euo pipefail

PROG=$(basename "$0")
PROD_DB="vibemusic"
DEFAULT_TARGET_DB="vibemusic_restore_drill"
TARGET_DB="$DEFAULT_TARGET_DB"
MODE="ephemeral"
DUMP=""
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
PASS_ENV=""
MYSQL_IMAGE="mysql:8.0"
ALLOW_REMOTE=0
ALLOW_NONEMPTY=0
NO_SANITIZE=0
DRY_RUN=0
KEEP=0
CONTAINER=""
TMP_RUN=""

log()  { printf '[restore] %s\n' "$*"; }
warn() { printf '[restore][warn] %s\n' "$*" >&2; }
die()  { printf '[restore][fatal] %s\n' "$*" >&2; exit "${2:-1}"; }

usage() {
  cat <<EOF
$PROG — 安全恢复 mysqldump 备份到临时库（绝不碰生产库）

用法: $PROG --dump <备份文件.sql|.sql.gz> [options]

  --dump FILE            必填：备份文件（.sql / .sql.gz，自动识别，先验完整性）
  --target-db NAME       目标库名（默认 $DEFAULT_TARGET_DB；禁止 $PROD_DB 与系统库）
  --mode ephemeral|existing
                         ephemeral（默认）= 起一次性 mysql:8.0 容器灌进去，跑完删掉，零污染
                         existing        = 灌进已存在的库（须 --host/--port/--user；库非空则拒绝）
  --host H --port P --user U      仅 existing 模式（默认 127.0.0.1:3306 root）
  --password-env VAR     ONLY existing：从环境变量 VAR 取密码（不落命令行、不进日志）
  --mysql-image IMG      ephemeral 模式镜像（默认 $MYSQL_IMAGE）
  --allow-nonempty       existing 模式目标库非空时也继续（危险，仅演练用）
  --allow-remote         允许 existing 模式连非 loopback 主机（默认拒绝）
  --no-sanitize          调试/演示用：关闭清洗（会被残留扫描挡下，用于验证守卫）
  --dry-run              只做清洗 + 残留扫描 + 打印统计，不创建/不连接任何数据库
  --keep                 ephemeral 模式保留容器便于人工核对，结尾打印清理命令
  -h, --help             本帮助

示例:
  $PROG --dump /tmp/vibemusic-20261019-0300.sql.gz --dry-run
  $PROG --dump /tmp/vibemusic-20261019-0300.sql.gz --keep
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dump)          [ $# -ge 2 ] || die "--dump 缺少值" 3; DUMP="$2"; shift 2 ;;
    --target-db)     [ $# -ge 2 ] || die "--target-db 缺少值" 3; TARGET_DB="$2"; shift 2 ;;
    --mode)          [ $# -ge 2 ] || die "--mode 缺少值" 3; MODE="$2"; shift 2 ;;
    --host)          [ $# -ge 2 ] || die "--host 缺少值" 3; DB_HOST="$2"; shift 2 ;;
    --port)          [ $# -ge 2 ] || die "--port 缺少值" 3; DB_PORT="$2"; shift 2 ;;
    --user)          [ $# -ge 2 ] || die "--user 缺少值" 3; DB_USER="$2"; shift 2 ;;
    --password-env)  [ $# -ge 2 ] || die "--password-env 缺少值" 3; PASS_ENV="$2"; shift 2 ;;
    --mysql-image)   [ $# -ge 2 ] || die "--mysql-image 缺少值" 3; MYSQL_IMAGE="$2"; shift 2 ;;
    --allow-remote)  ALLOW_REMOTE=1; shift ;;
    --allow-nonempty) ALLOW_NONEMPTY=1; shift ;;
    --no-sanitize)   NO_SANITIZE=1; shift ;;
    --dry-run)       DRY_RUN=1; shift ;;
    --keep)          KEEP=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) die "未知参数: $1（用 --help 看用法）" 3 ;;
  esac
done

cleanup() {
  if [ -n "$CONTAINER" ] && [ "$KEEP" -eq 0 ]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
  [ -n "$TMP_RUN" ] && rm -rf "$TMP_RUN"
  return 0
}
trap cleanup EXIT

# ---------- 守卫 1：目标库名 ----------
case "$TARGET_DB" in ''|*[!A-Za-z0-9_]*) die "目标库名非法：'$TARGET_DB'（只允许字母/数字/下划线）" 2 ;; esac
case "$(printf '%s' "$TARGET_DB" | tr '[:upper:]' '[:lower:]')" in
  "$PROD_DB"|mysql|sys|information_schema|performance_schema)
    die "拒绝执行：目标库名 '$TARGET_DB' 是生产库或系统库。恢复只能进临时库（默认 $DEFAULT_TARGET_DB）。" 2 ;;
esac

# ---------- 前置检查 ----------
[ -n "$DUMP" ] || { usage >&2; die "必须用 --dump 指定备份文件" 3; }
[ -f "$DUMP" ] || die "备份文件不存在：$DUMP" 3
[ "$MODE" = ephemeral ] || [ "$MODE" = existing ] || die "--mode 只能是 ephemeral 或 existing" 3
if [ ! -s "$DUMP" ]; then die "备份文件为空（0 字节）：$DUMP" 3; fi

DB_PASS=""
if [ "$MODE" = existing ]; then
  case "$DB_HOST" in
    127.0.0.1|localhost|::1|0.0.0.0) ;;
    *) [ "$ALLOW_REMOTE" -eq 1 ] || die "目标主机 '$DB_HOST' 不是 loopback。本脚本默认只允许本机/容器恢复；确需连远程请显式加 --allow-remote。" 2 ;;
  esac
  command -v mysql >/dev/null || die "existing 模式需要本机 mysql 客户端" 3
  if [ -n "$PASS_ENV" ]; then
    DB_PASS=$(printenv "$PASS_ENV" 2>/dev/null || true)
    [ -n "$DB_PASS" ] || die "环境变量 $PASS_ENV 未设置或为空，取不到密码" 3
  fi
else
  command -v docker >/dev/null || die "ephemeral 模式需要 docker" 3
fi

TMP_RUN=$(mktemp -d "${TMPDIR:-/tmp}/vibemusic-restore.XXXXXX")
BODY="$TMP_RUN/body.sql"; LOAD="$TMP_RUN/load.sql"; STATS="$TMP_RUN/sanitize.stats"; ERRF="$TMP_RUN/load.err"
: > "$ERRF"

decompress() {
  case "$DUMP" in
    *.gz|*.gzip) gzip -dc "$DUMP" ;;
    *.sql) cat "$DUMP" ;;
    *) if gzip -t "$DUMP" 2>/dev/null; then gzip -dc "$DUMP"; else cat "$DUMP"; fi ;;
  esac
}

# ---------- 清洗管道（流式，不改用户原文件）----------
cat > "$TMP_RUN/sanitize.awk" <<'AWK'
{
  probe = $0
  # 解开 mysqldump 的版本注释包装 /*!NNNNN stmt */; 再判断，避免漏掉包装形式
  if (probe ~ /^[ \t]*\/\*![0-9]+[ \t]+/) {
    sub(/^[ \t]*\/\*![0-9]+[ \t]+/, "", probe)
    sub(/[ \t]*\*\/[ \t]*;[ \t]*$/, "", probe)
    sub(/[ \t]*\*\/[ \t]*$/, "", probe)
  }
  if (probe ~ /^[ \t]*DROP[ \t]+TABLE/)       { drop_tbl++;  next }
  if (probe ~ /^[ \t]*DROP[ \t]+VIEW/)        { drop_view++; next }
  if (probe ~ /^[ \t]*DROP[ \t]+DATABASE/)    { drop_db++;   next }
  if (probe ~ /^[ \t]*CREATE[ \t]+DATABASE/)  { create_db++; next }
  if (probe ~ /^[ \t]*USE[ \t]/)              { use_st++;    next }
  print
}
END {
  printf "create_db=%d\ndrop_db=%d\ndrop_table=%d\ndrop_view=%d\nuse=%d\n",
         create_db+0, drop_db+0, drop_tbl+0, drop_view+0, use_st+0 > stat
}
AWK

if [ "$NO_SANITIZE" -eq 1 ]; then
  warn "--no-sanitize 已开启：不做任何清洗（仅供演示/验证守卫，随后会被残留扫描拦下）"
  decompress > "$BODY"
  : > "$STATS"
else
  decompress | awk -v stat="$STATS" -f "$TMP_RUN/sanitize.awk" > "$BODY"
fi

stat_of() { [ -f "$STATS" ] && sed -n "s/^$1=//p" "$STATS" | head -1 || echo 0; }
C_CREATE_DB=$(stat_of create_db); C_DROP_DB=$(stat_of drop_db)
C_DROP_TBL=$(stat_of drop_table); C_DROP_VIEW=$(stat_of drop_view); C_USE=$(stat_of use)
: "${C_CREATE_DB:=0}"; : "${C_DROP_DB:=0}"; : "${C_DROP_TBL:=0}"; : "${C_DROP_VIEW:=0}"; : "${C_USE:=0}"

# ---------- 守卫 2：残留扫描（清洗后仍是危险语句 -> 拒绝，一个字节都不灌）----------
RESID=$(grep -nE '^[[:space:]]*(/\*![0-9]+[[:space:]]+)?(DROP[[:space:]]+(TABLE|VIEW|DATABASE)|USE[[:space:]]+|CREATE[[:space:]]+DATABASE)' "$BODY" || true)
if [ -n "$RESID" ]; then
  warn "残留危险语句（前 5 行）："
  printf '%s\n' "$RESID" | head -5 >&2
  die "拒绝执行：清洗后仍检测到 DROP TABLE/VIEW/DATABASE 或 USE/CREATE DATABASE。为避免毁数据，已中止（未连接任何数据库）。" 2
fi

log "清洗完成：剥离 CREATE DATABASE=${C_CREATE_DB} / DROP DATABASE=${C_DROP_DB} / DROP TABLE=${C_DROP_TBL} / DROP VIEW=${C_DROP_VIEW} / USE=${C_USE}"

if [ "$DRY_RUN" -eq 1 ]; then
  printf '===== dry-run 报告（未创建、未连接任何数据库）=====\n'
  printf 'dump        : %s (%s bytes)\n' "$DUMP" "$(wc -c < "$DUMP" | tr -d ' ')"
  printf 'target-db   : %s\n' "$TARGET_DB"
  printf '清洗后行数  : %s\n' "$(wc -l < "$BODY" | tr -d ' ')"
  printf '残留扫描    : 通过（无 DROP / USE / CREATE DATABASE）\n'
  printf '结论        : 可以安全灌入临时库；生产库 %s 未被触碰\n' "$PROD_DB"
  exit 0
fi

# ---------- 指定恢复目标 ----------
rand_pass() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

case "$MODE" in
  ephemeral)
    CONTAINER="vibemusic-restore-$RANDOM$$"; DB_PASS=$(rand_pass)
    log "启动一次性容器 $CONTAINER（$MYSQL_IMAGE，随机端口，--rm）"
    docker run -d --rm --name "$CONTAINER" -e MYSQL_ROOT_PASSWORD="$DB_PASS" \
      -e MYSQL_DATABASE="$TARGET_DB" -p 127.0.0.1::3306 \
      "$MYSQL_IMAGE" --log-bin-trust-function-creators=1 >/dev/null
    probe() { docker exec -e MYSQL_PWD="$DB_PASS" "$CONTAINER" mysql -h 127.0.0.1 -uroot -N -B -e 'SELECT 1' >/dev/null 2>&1; }
    for i in $(seq 1 90); do
      if probe; then sleep 2; probe && break; fi
      [ "$i" -eq 90 ] && { docker logs --tail 30 "$CONTAINER" >&2 || true; die "等待 MySQL 就绪超时" 1; }
      sleep 2
    done
    DBPORT=$(docker port "$CONTAINER" 3306/tcp | head -1 | sed 's/.*://'); DEST="容器 $CONTAINER (127.0.0.1:$DBPORT), 库 $TARGET_DB"
    ;;
  existing)
    DEST="$DB_HOST:$DB_PORT (user $DB_USER), 库 $TARGET_DB"
    ;;
esac

mysql_raw() {
  if [ "$MODE" = ephemeral ]; then
    docker exec -i -e MYSQL_PWD="$DB_PASS" "$CONTAINER" mysql -uroot --default-character-set=utf8mb4 "$@"
  else
    MYSQL_PWD="$DB_PASS" mysql --protocol=TCP -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" --default-character-set=utf8mb4 "$@"
  fi
}
q()  { mysql_raw -N -B -e "$1"; }
qdb() { mysql_raw -N -B -D "$TARGET_DB" -e "$1"; }

if [ "$MODE" = existing ]; then
  q 'SELECT 1' >/dev/null 2>&1 || die "无法连接 $DB_HOST:$DB_PORT（user $DB_USER）" 1
  q "CREATE DATABASE IF NOT EXISTS \`$TARGET_DB\` DEFAULT CHARACTER SET utf8mb4" >/dev/null 2>&1
  EXIST_TBL=$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET_DB' AND table_type='BASE TABLE'")
  if [ "${EXIST_TBL:-0}" -gt 0 ] && [ "$ALLOW_NONEMPTY" -eq 0 ]; then
    die "拒绝执行：目标库 $TARGET_DB 已存在 $EXIST_TBL 张表。恢复只能进空库（本脚本不执行 DROP TABLE，灌进去会报表已存在）。清空该库或换库名，确需演练观察请显式加 --allow-nonempty。" 2
  fi
fi

{
  printf 'SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n'
  printf 'CREATE DATABASE IF NOT EXISTS `%s` DEFAULT CHARACTER SET utf8mb4;\nUSE `%s`;\n' "$TARGET_DB" "$TARGET_DB"
  cat "$BODY"
  printf '\nSET FOREIGN_KEY_CHECKS=1;\n'
} > "$LOAD"

log "灌库中：$DUMP -> $DEST"
LOAD_RC=0
mysql_raw < "$LOAD" 2> "$ERRF" || LOAD_RC=$?

# ---------- 校验报告 ----------
TBL_CNT=$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET_DB' AND table_type='BASE TABLE'" || echo 0)
TRG_CNT=$(q "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='$TARGET_DB'" || echo 0)
RTN_CNT=$(q "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='$TARGET_DB'" || echo 0)
printf '\n===== 恢复校验报告 =====\n'
printf 'dump        : %s\n恢复目标    : %s\n' "$DUMP" "$DEST"
printf '清洗统计    : CREATE DATABASE=%s  DROP DATABASE=%s  DROP TABLE=%s  DROP VIEW=%s  USE=%s\n' \
  "$C_CREATE_DB" "$C_DROP_DB" "$C_DROP_TBL" "$C_DROP_VIEW" "$C_USE"
printf '安全声明    : 已剥离全部 DROP TABLE/VIEW/DATABASE，未对名为 %s 的库做任何写入\n' "$PROD_DB"
printf '表数量      : %s\n触发器/过程 : trigger=%s  routine=%s\n' "$TBL_CNT" "$TRG_CNT" "$RTN_CNT"

if [ "$LOAD_RC" -ne 0 ]; then
  printf '\n[!] 灌库返回码 %s，错误前 10 行：\n' "$LOAD_RC"
  head -10 "$ERRF" | sed 's/^/    /'
fi

if [ "${TBL_CNT:-0}" -gt 0 ]; then
  printf '\n--- 每张表行数 ---\n'
  QROWS=$(q "SELECT GROUP_CONCAT(CONCAT('SELECT ''',table_name,''' AS tbl, COUNT(*) AS rows_cnt FROM \`',table_name,'\`') SEPARATOR ' UNION ALL ') FROM information_schema.tables WHERE table_schema='$TARGET_DB' AND table_type='BASE TABLE'")
  # 行数统计只影响报告，出错时告警但不改变恢复结论
  if [ -n "$QROWS" ] && qdb "$QROWS" 2>/dev/null | sort | awk -F'\t' '{printf "  %-32s %s\n", $1, $2}'; then
    :
  else
    warn "行数统计失败（不影响恢复本身），请人工核对"
  fi

  printf '\n--- 关键表抽样（自动按真实表名选取；已排除 password/token/cookie/secret 类敏感列）---\n'
  KEY=$(q "SELECT table_name FROM information_schema.tables WHERE table_schema='$TARGET_DB' AND table_type='BASE TABLE' AND table_name REGEXP 'users|user_favorite|play_history|playlist|song' ORDER BY table_name LIMIT 6") || KEY=""
  [ -n "$KEY" ] || KEY=$(q "SELECT table_name FROM information_schema.tables WHERE table_schema='$TARGET_DB' AND table_type='BASE TABLE' ORDER BY table_name LIMIT 3") || KEY=""
  while IFS= read -r t; do
    [ -n "$t" ] || continue
    COLS=$(q "SELECT GROUP_CONCAT(CONCAT('\`',column_name,'\`') ORDER BY ordinal_position SEPARATOR ',') FROM information_schema.columns WHERE table_schema='$TARGET_DB' AND table_name='$t' AND column_name NOT REGEXP 'password|passwd|salt|token|cookie|secret|credential|private'") || COLS=""
    printf '  --- %s (前 3 行) ---\n' "$t"
    if [ -n "$COLS" ]; then
      mysql_raw -t -D "$TARGET_DB" -e "SELECT $COLS FROM \`$t\` LIMIT 3" | sed 's/^/    /' || warn "抽样 $t 失败"
    else
      printf '    (该表全部列均为敏感列，跳过抽样)\n'
    fi
  done <<EOF
$KEY
EOF
else
  printf '\n[!] 目标库里没有任何表，恢复很可能失败。\n'
fi

printf '\n'
if [ "$LOAD_RC" -ne 0 ]; then
  warn "灌库过程有错误（返回码 $LOAD_RC）。注意：脚本不执行 DROP TABLE，所以是「报表已存在」这类失败，不会删掉已有数据。"
  exit 4
fi
[ "${TBL_CNT:-0}" -gt 0 ] || exit 5

if [ "$MODE" = ephemeral ] && [ "$KEEP" -eq 1 ]; then
  log "--keep：容器 $CONTAINER 已保留（密码不落日志，用 inspect 取）"
  printf '  取密码: docker inspect %s --format "{{range .Config.Env}}{{println .}}{{end}}" | sed -n "s/^MYSQL_ROOT_PASSWORD=//p"\n  进库:   docker exec -it %s mysql -uroot -p"<上一步密码>" -D %s   清理: docker rm -f %s\n' "$CONTAINER" "$CONTAINER" "$TARGET_DB" "$CONTAINER"
else
  log "校验通过，恢复完成（临时环境将在退出时清理）"
fi
exit 0
