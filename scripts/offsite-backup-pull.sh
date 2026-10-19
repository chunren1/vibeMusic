#!/usr/bin/env bash
# offsite-backup-pull.sh — 把云上的每日 MySQL 备份**拉到本机留存**（异地副本）。
#
# 为什么需要它（改动前先读 docs/OFFSITE-BACKUP.md）：
#   scripts/backup-mysql.sh 每天 03:30 在云上产出 vibemusic-YYYYmmdd-HHMM.sql.gz，
#   但**云端是唯一副本**：机器挂了 / 盘坏了 / 误删目录 = 备份与生产同时没了。
#   本脚本用「拉取式」把 dump 复制一份到本机（本机有独立故障域），
#   让 `scripts/restore-mysql.sh` 在任何时候都有可用的输入。
#
# ============================================================
# 只读不变量（铁律 —— 改脚本前先读，任何新增远端命令都要先对着这条检查）
#   本脚本对生产服务器**只读**，远端命令白名单只有三类：
#       ls  /  sha256sum  /  cat
#   严禁在远端创建、修改、删除、移动、重命名任何文件或目录；
#   严禁在远端执行 docker / mysql / systemctl / cron 等任何写操作或进程控制命令。
#   下载动作 = 远端 `cat <dump>` 把字节流重定向到本地文件（等价于 scp，纯读）。
# ============================================================
#
# 退出码：0 成功（含 SKIP）/ 1 运行期失败 / 2 校验不一致（本地副本已删除）/ 3 参数或前置条件错误 / 4 连不上云
set -euo pipefail
export LC_ALL=C

PROG=$(basename "$0")
REMOTE_DIR="/opt/vibeMusic/docker-data/backups/mysql"
SSH_HOST="101.132.167.178"
SSH_USER="root"
DEST="$HOME/.dsh/backups/vibemusic"
KEEP=14
FILE=""
DRY_RUN=0
SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=15 -o LogLevel=ERROR)
TMP=""
PATTERN_RE='^vibemusic-[0-9]{8}-[0-9]{4}\.sql\.gz$'
GLOB="vibemusic-*.sql.gz"

log()  { printf '[offsite] %s\n' "$*"; }
warn() { printf '[offsite][warn] %s\n' "$*" >&2; }
die()  { printf '[offsite][fatal] %s\n' "$*" >&2; exit "${2:-1}"; }

usage() {
  cat <<EOF
$PROG — 把云上每日 MySQL 备份拉到本机留存（对生产只读）

用法: $PROG [options]

  --file NAME            指定远端 dump 文件名（默认取最新一份）。
                         只接受形如 vibemusic-YYYYmmdd-HHMM.sql.gz 的文件名，不接受路径。
  --dest DIR             本地留存目录（默认 $DEST）
  --keep N               本地滚动保留份数（默认 $KEEP，只清理 --dest 下的 $GLOB）
  --dry-run              只列出「将下载什么 / 将删什么」，不落盘、不删除
  -h, --help             本帮助

凭据（唯一来源，绝不落盘/绝不打印）:
  export SSHPASS=...     密码只从环境变量读，sshpass -e 传递

示例:
  export SSHPASS="\$(python3 -c "import yaml,os;print(yaml.safe_load(open(os.path.expanduser('~/.dsh/.credentials.yaml')))['refs']['VIBEMUSIC_SERVER_PASSWORD'])")"
  $PROG                          # 拉最新一份 + 滚动保留 14 份
  $PROG --file vibemusic-20261019-0330.sql.gz
  $PROG --keep 2 --dry-run
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --file)    [ $# -ge 2 ] || die "--file 缺少值" 3; FILE="$2"; shift 2 ;;
    --dest)    [ $# -ge 2 ] || die "--dest 缺少值" 3; DEST="$2"; shift 2 ;;
    --keep)    [ $# -ge 2 ] || die "--keep 缺少值" 3; KEEP="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数: $1（用 --help 看用法）" 3 ;;
  esac
done

case "$KEEP" in ''|*[!0-9]*) die "--keep 必须是非负整数（收到 '$KEEP'）" 3 ;; esac
[ "$KEEP" -ge 1 ] || die "--keep 至少为 1（收到 $KEEP）；要清空请手动 rm" 3
[ -n "$DEST" ] || die "--dest 不能为空" 3
case "$DEST" in /) die "--dest 不能是根目录" 3 ;; esac
case "$DEST" in */*) ;; *) die "--dest 必须是目录路径（收到 '$DEST'）" 3 ;; esac

# ---------- 凭据守卫：只认环境变量 SSHPASS ----------
if [ -z "${SSHPASS:-}" ]; then
  warn "缺少环境变量 SSHPASS。本脚本只用 sshpass -e（密码经环境变量传递），不接受明文参数、不读配置文件。"
  warn "请先 export SSHPASS，例如（取值自 ~/.dsh/.credentials.yaml 的 refs.VIBEMUSIC_SERVER_PASSWORD，只进环境变量）："
  warn "  export SSHPASS=\"\$(python3 -c \"import yaml,os;print(yaml.safe_load(open(os.path.expanduser('~/.dsh/.credentials.yaml')))['refs']['VIBEMUSIC_SERVER_PASSWORD'])\")\""
  exit 3
fi
command -v sshpass >/dev/null 2>&1 || die "未安装 sshpass（apt install sshpass）" 3

remote() { sshpass -e ssh "${SSH_OPTS[@]}" "$SSH_USER@$SSH_HOST" "$@"; }

cleanup() { [ -n "$TMP" ] && rm -f -- "$TMP"; return 0; }
trap cleanup EXIT

# ---------- 远端只读查询（白名单：ls / sha256sum / cat）----------
remote_list() { remote "ls -1t -- $REMOTE_DIR"; }
remote_sha()  { remote "sha256sum -- $REMOTE_DIR/$1" | awk '{print $1}'; }
remote_size() { remote "ls -l -- $REMOTE_DIR/$1" | awk '{print $5}' | head -1; }
fetch()       { remote "cat -- $REMOTE_DIR/$1"; }

# ---------- 1) 选定要拉的 dump ----------
if [ -n "$FILE" ]; then
  case "$FILE" in
    */*) die "--file 只接受文件名，不接受路径（收到 '$FILE'）" 3 ;;
  esac
  printf '%s\n' "$FILE" | grep -qE "$PATTERN_RE" || die "--file 名不符合 vibemusic-YYYYmmdd-HHMM.sql.gz：'$FILE'" 3
  TARGET="$FILE"
else
  TARGET="$(remote_list 2>/dev/null | grep -E "$PATTERN_RE" | head -1 || true)"
  [ -n "$TARGET" ] || die "连不上云，或 $REMOTE_DIR 下没有匹配 $PATTERN_RE 的备份" 4
fi

R_SHA="$(remote_sha "$TARGET" | head -1 || true)"
R_SIZE="$(remote_size "$TARGET" || true)"
[ -n "$R_SHA" ] && [ -n "$R_SIZE" ] || die "读取远端 $TARGET 的 sha256/大小失败" 4
[ "$R_SIZE" -gt 0 ] || die "远端 $TARGET 是 0 字节，拒绝拉取" 4

log "远端最新 : $TARGET ($R_SIZE bytes, sha256=$R_SHA)"
log "本地目录 : $DEST"

# ---------- 2) 幂等判定：同名且 sha256 一致 -> SKIP ----------
mkdir -p "$DEST"
LOCAL="$DEST/$TARGET"
L_SHA=""
[ -f "$LOCAL" ] && L_SHA="$(sha256sum "$LOCAL" | awk '{print $1}')"

if [ -n "$L_SHA" ] && [ "$L_SHA" = "$R_SHA" ]; then
  log "SKIP：本地已存在同名且 sha256 一致，无需下载（$LOCAL）"
  SKIP=1
else
  SKIP=0
  [ -n "$L_SHA" ] && warn "本地同名文件 sha256 不一致（本地 $L_SHA），将重新下载覆盖"
fi

if [ "$DRY_RUN" -eq 1 ]; then
  printf '===== dry-run（未落盘、未删除任何文件）=====\n'
  printf '将下载   : %s -> %s (%s bytes)\n' "$TARGET" "$LOCAL" "$R_SIZE"
  printf '动作     : %s\n' "$([ "$SKIP" -eq 1 ] && echo 'SKIP（已是最新，无需下载）' || echo 'DOWNLOAD')"
else
  if [ "$SKIP" -eq 1 ]; then
    :
  else
    TMP=$(mktemp "$DEST/.offsite-partial.XXXXXX")
    log "下载中   : 远端 cat -> $TMP"
    if ! fetch "$TARGET" > "$TMP"; then
      rm -f -- "$TMP"; TMP=""
      die "下载失败（远端 cat $TARGET）" 4
    fi
    B_SIZE=$(wc -c < "$TMP" | tr -d ' ')

    # ---------- 3) 三重校验：gzip -t / 大小 / sha256 ----------
    if [ "$B_SIZE" -eq 0 ]; then
      rm -f -- "$TMP"; TMP=""
      die "校验失败：下载得到的文件是 0 字节，已丢弃" 2
    fi
    if [ "$B_SIZE" != "$R_SIZE" ]; then
      rm -f -- "$TMP"; TMP=""
      die "校验失败：大小不一致（本地 $B_SIZE != 远端 $R_SIZE），已丢弃本地副本" 2
    fi
    if ! gzip -t "$TMP" 2>/dev/null; then
      rm -f -- "$TMP"; TMP=""
      die "校验失败：gzip -t 不通过（文件损坏），已丢弃本地副本" 2
    fi
    B_SHA=$(sha256sum "$TMP" | awk '{print $1}')
    if [ "$B_SHA" != "$R_SHA" ]; then
      rm -f -- "$TMP"; TMP=""
      die "校验失败：sha256 不一致（本地 $B_SHA != 远端 $R_SHA），已丢弃本地副本" 2
    fi
    mv -f -- "$TMP" "$LOCAL"; TMP=""
    log "校验通过 : 大小 $B_SIZE bytes + gzip -t OK + sha256 与远端一致"
    log "已保存   : $LOCAL"
  fi
fi

# ---------- 4) 滚动清理：只删 --dest 下匹配 vibemusic-*.sql.gz 的文件 ----------
list_local() {
  [ -d "$DEST" ] || return 0
  ( cd "$DEST" 2>/dev/null || exit 0
    for f in $GLOB; do [ -e "$f" ] || continue; printf '%s\n' "$f"; done ) | sort -r
}

mapfile -t LOCAL_FILES < <(list_local)
PRUNE=()
if [ "${#LOCAL_FILES[@]}" -gt "$KEEP" ]; then
  PRUNE=("${LOCAL_FILES[@]:$KEEP}")
fi

if [ "${#PRUNE[@]}" -gt 0 ]; then
  log "滚动清理 : 保留最新 $KEEP 份，将删除 ${#PRUNE[@]} 份（仅 $DEST 下匹配 $GLOB 的文件）"
  for f in "${PRUNE[@]}"; do
    printf '  将删除: %s (%s bytes, %s)\n' "$f" "$(stat -c '%s' "$DEST/$f" 2>/dev/null || echo '?')" "$(stat -c '%y' "$DEST/$f" 2>/dev/null | cut -d. -f1 || echo '?')"
  done
  if [ "$DRY_RUN" -eq 0 ]; then
    for f in "${PRUNE[@]}"; do rm -f -- "$DEST/$f"; done
    log "已删除 ${#PRUNE[@]} 份旧副本"
  else
    log "dry-run：以上文件未删除"
  fi
else
  log "滚动清理 : 本地 ${#LOCAL_FILES[@]} 份，未超过 --keep $KEEP，无需删除"
fi

# ---------- 5) 本地留存清单（人肉核对用）----------
printf '\n===== 本地留存清单（%s）=====\n' "$DEST"
mapfile -t NOW_FILES < <(list_local)
if [ "${#NOW_FILES[@]}" -eq 0 ]; then
  printf '  (空)\n'
else
  printf '  %-34s %10s  %s\n' 'FILE' 'BYTES' 'MTIME'
  for f in "${NOW_FILES[@]}"; do
    printf '  %-34s %10s  %s\n' "$f" "$(stat -c '%s' "$DEST/$f")" "$(stat -c '%y' "$DEST/$f" | cut -d. -f1)"
  done
  printf '  合计 %s 份，%s bytes\n' "${#NOW_FILES[@]}" "$(du -cb "${NOW_FILES[@]/#/$DEST/}" 2>/dev/null | tail -1 | awk '{print $1}')"
fi
printf '  远端 sha256: %s\n' "$R_SHA"
exit 0
