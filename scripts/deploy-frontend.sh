#!/usr/bin/env bash
# 前端 dist 一条命令发版：本地构建 → 清单+sha256 → 打包 → scp → 远端对 md5 → 解包 → nginx -t
#                    → 原子切换（旧目录改名 bak-时间戳）→ reload → 公网验证 → 失败自动回滚。
# 用法：
#   ./scripts/deploy-frontend.sh                 # 默认 dry-run，只打印命令，不碰服务器
#   ./scripts/deploy-frontend.sh --apply         # 真正发版
#   ./scripts/deploy-frontend.sh --skip-build    # 复用现有 dist（仍会重算清单/哈希）
#   ./scripts/deploy-frontend.sh --keep 5        # 保留 5 个旧 bak 目录（默认 3）
# 约定：只走 SSH key（BatchMode，绝不交互要密码）；脚本内不含任何口令。
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WEB="$REPO/vibemusic-web"

HOST="${HOST:-root@101.132.167.178}"
REMOTE_DIR="${REMOTE_DIR:-/var/www/vibemusic}"
PUBLIC_URL="${PUBLIC_URL:-https://vibe.cyk666.top}"
KEEP="${KEEP:-3}"
APPLY=0
SKIP_BUILD=0

usage() {
  cat <<'EOF'
前端 dist 发版（默认 dry-run）

用法: scripts/deploy-frontend.sh [选项]

  --apply               真正执行（默认只打印将执行的命令）
  --skip-build          跳过 npm ci && npm run build，复用现有 vibemusic-web/dist
  --keep N              远端保留的旧备份目录个数（默认 3，0=不留）
  --host USER@IP        目标主机（默认 root@101.132.167.178，或环境变量 HOST）
  --remote-dir DIR      远端站点目录（默认 /var/www/vibemusic，或 REMOTE_DIR）
  --public-url URL      公网验证地址（默认 https://vibe.cyk666.top）
  -h, --help            本帮助

流程: 构建 → dist 清单+sha256 → tar → scp /tmp → 远端 md5 对包 → 解包到 DIR.new
      → nginx -t → 原子切换 DIR→DIR.bak-<ts>, DIR.new→DIR → reload → 公网验证
      → 失败自动回滚到 DIR.bak-<ts> → 清理超出 --keep 的旧备份
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --dry-run) APPLY=0 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --keep) KEEP="${2:?--keep 需要数字}"; shift ;;
    --keep=*) KEEP="${1#*=}" ;;
    --host) HOST="${2:?--host 需要 USER@IP}"; shift ;;
    --host=*) HOST="${1#*=}" ;;
    --remote-dir) REMOTE_DIR="${2:?--remote-dir 需要路径}"; shift ;;
    --remote-dir=*) REMOTE_DIR="${1#*=}" ;;
    --public-url) PUBLIC_URL="${2:?--public-url 需要 URL}"; shift ;;
    --public-url=*) PUBLIC_URL="${1#*=}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1（见 --help）" >&2; exit 2 ;;
  esac
  shift
done

case "$KEEP" in ''|*[!0-9]*) echo "--keep 必须是 >=0 的整数（当前: $KEEP）" >&2; exit 2 ;; esac

STAMP="$(date +%Y%m%d-%H%M%S)"
BUNDLE="vibemusic-dist-$STAMP.tar.gz"
WORK="${TMPDIR:-/tmp}/opencode/vibemusic-fe-$STAMP"
BAK_DIR="$REMOTE_DIR.bak-$STAMP"
STAGING="$REMOTE_DIR.new"
SWITCHED=0

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }

# 执行（--apply）或只打印（dry-run）。所有远端动作都必须经 run/rsh，保证 dry-run 零副作用。
run() {
  if [ "$APPLY" -eq 1 ]; then "$@"
  else printf '  [dry-run] %s\n' "$*"; fi
}
rsh() { run ssh "${SSH_OPTS[@]}" "$HOST" "$1"; }
# 需要读取远端的输出时用 rout；dry-run 下调用方必须先用 is_dry 短路。
rout() { ssh "${SSH_OPTS[@]}" "$HOST" "$1"; }
is_dry() { [ "$APPLY" -eq 0 ]; }

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺命令: $1" >&2; exit 1; }; }
need ssh; need scp; need tar; need sha256sum; need md5sum
if [ "$SKIP_BUILD" -eq 0 ] && ! is_dry; then need npm; fi
[ -f "$WEB/package.json" ] || { echo "找不到 $WEB/package.json" >&2; exit 1; }

# 公网校验用的取码/取体：只在**传输层失败**（000/空体，如 TLS 中途断开、CF 抖动）重试一次，
# HTTP 错误码（404/500）不重试——那是真问题。2026-10-23 实测：一次 TLS "unexpected eof"
# 把已经切好的发布判成失败并回滚，属于误伤。
fetch_code() {
  local url="$1" c
  c="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$url" 2>/dev/null || true)"
  if [ "$c" = "000" ] || [ -z "$c" ]; then
    sleep 1
    c="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$url" 2>/dev/null || true)"
  fi
  printf '%s' "$c"
}
fetch_body() {
  local url="$1" b
  b="$(curl -sS --max-time 20 "$url" 2>/dev/null || true)"
  if [ -z "$b" ]; then
    sleep 1
    b="$(curl -sS --max-time 20 "$url" 2>/dev/null || true)"
  fi
  printf '%s' "$b"
}

if is_dry; then
  say "*** DRY-RUN：只打印命令，不构建、不 scp、不改远端。加 --apply 才真发。***"
else
  say "*** APPLY：将真实发布到 $HOST:$REMOTE_DIR ***"
fi
say "时间戳 $STAMP | 保留备份 $KEEP 个 | 公网校验 $PUBLIC_URL"

# ---------------------------------------------------------------------------
step "1/9 本地构建"
if [ "$SKIP_BUILD" -eq 1 ]; then
  say "  --skip-build：复用现有 dist（哈希会现场重算，仍可确认发出去的就是这份）"
else
  if is_dry; then
    say "  cd $WEB && npm ci && npm run build"
  else
    ( cd "$WEB" && npm ci && npm run build )
  fi
fi
DIST="$WEB/dist"
if [ ! -f "$DIST/index.html" ]; then
  if is_dry; then say "  （dry-run：dist/index.html 尚不存在，后续按构建后产物推演）"; else
    echo "构建后没有 $DIST/index.html，中止" >&2; exit 1
  fi
fi

# ---------------------------------------------------------------------------
step "2/9 记录 dist 清单 + sha256"
mkdir -p "$WORK"
MANIFEST="$WORK/dist-manifest-$STAMP.txt"
FILE_COUNT=0
MANIFEST_SHA="(dry-run)"
if [ -f "$DIST/index.html" ]; then
  ( cd "$DIST" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum ) > "$MANIFEST"
  FILE_COUNT="$(wc -l < "$MANIFEST" | tr -d ' ')"
  MANIFEST_SHA="$(sha256sum "$MANIFEST" | awk '{print $1}')"
  say "  文件数 $FILE_COUNT | 清单 sha256 $MANIFEST_SHA"
  say "  清单: $MANIFEST"
  # 关键资源（公网验证时用，来自刚构建的 index.html，而不是“上次的记忆”）
  MAIN_JS="$(grep -oE '/assets/[A-Za-z0-9._-]+\.js' "$DIST/index.html" | head -1 || true)"
  MAIN_CSS="$(grep -oE '/assets/[A-Za-z0-9._-]+\.css' "$DIST/index.html" | head -1 || true)"
  INDEX_SHA="$(sha256sum "$DIST/index.html" | awk '{print $1}')"
else
  MAIN_JS='/assets/<build>.js'; MAIN_CSS=''; INDEX_SHA='(dry-run)'
  say "  （dry-run：无 dist，无法生成清单）"
fi

# ---------------------------------------------------------------------------
step "3/9 打包 + 本地哈希"
BUNDLE_PATH="$WORK/$BUNDLE"
if [ -f "$DIST/index.html" ]; then
  tar -C "$DIST" -czf "$BUNDLE_PATH" .
  MD5_LOCAL="$(md5sum "$BUNDLE_PATH" | awk '{print $1}')"
  SHA_LOCAL="$(sha256sum "$BUNDLE_PATH" | awk '{print $1}')"
  say "  $BUNDLE  $(du -h "$BUNDLE_PATH" | awk '{print $1}')"
  say "  md5=$MD5_LOCAL"
  say "  sha256=$SHA_LOCAL"
else
  MD5_LOCAL='(dry-run)'; SHA_LOCAL='(dry-run)'
  say "  将打包 $DIST → $BUNDLE_PATH"
fi

# ---------------------------------------------------------------------------
step "4/9 scp 到 $HOST:/tmp/"
run scp "${SSH_OPTS[@]}" "$BUNDLE_PATH" "$HOST:/tmp/$BUNDLE"

# ---------------------------------------------------------------------------
step "5/9 远端对包 md5（不信报告信字节）"
if is_dry; then
  say "  将比对: ssh $HOST md5sum /tmp/$BUNDLE  ==  $MD5_LOCAL"
else
  MD5_REMOTE="$(rout "md5sum /tmp/$BUNDLE" | awk '{print $1}')"
  [ "$MD5_REMOTE" = "$MD5_LOCAL" ] || { echo "传包损坏: 本地 $MD5_LOCAL vs 远端 $MD5_REMOTE，中止" >&2; exit 1; }
  say "  md5 一致: $MD5_LOCAL"
fi

# ---------------------------------------------------------------------------
step "6/9 解包到 $STAGING + nginx -t"
rsh "set -e; rm -rf '$STAGING'; mkdir -p '$STAGING'; tar -xzf '/tmp/$BUNDLE' -C '$STAGING'"
if is_dry; then
  say "  将校验: 远端文件数 == $FILE_COUNT, 远端 index.html sha256 == $INDEX_SHA"
  say "  ssh $HOST nginx -t"
else
  N_REMOTE="$(rout "find '$STAGING' -type f | wc -l" | tr -d ' ')"
  [ "$N_REMOTE" = "$FILE_COUNT" ] || { echo "解包文件数不符: 远端 $N_REMOTE vs 本地 $FILE_COUNT，中止（未切换，线上未受影响）" >&2; exit 1; }
  IDX_REMOTE="$(rout "sha256sum '$STAGING/index.html'" | awk '{print $1}')"
  [ "$IDX_REMOTE" = "$INDEX_SHA" ] || { echo "index.html 哈希不符: $IDX_REMOTE vs $INDEX_SHA，中止（未切换）" >&2; exit 1; }
  say "  解包校验通过（$FILE_COUNT 个文件，index.html sha256 一致）"
  rout "nginx -t" || { echo "nginx -t 失败（配置/权限问题），未切换，线上未受影响" >&2; exit 1; }
fi

# 回滚（reload 失败 / 公网验证失败都走它）：坏目录留 .bad-<ts> 取证，bak 原路搬回。
rollback() {
  local why="$1"
  echo "!! 发布后校验失败: $why" >&2
  if [ "$SWITCHED" -ne 1 ]; then echo "   尚未切换，无需回滚（线上仍是旧目录）" >&2; return 1; fi
  echo "!! 开始回滚: $BAK_DIR → $REMOTE_DIR" >&2
  if ssh "${SSH_OPTS[@]}" "$HOST" "set -e; test -d '$BAK_DIR' && rm -rf '$REMOTE_DIR.bad-$STAMP' && mv '$REMOTE_DIR' '$REMOTE_DIR.bad-$STAMP' && mv '$BAK_DIR' '$REMOTE_DIR' && nginx -t && systemctl reload nginx && echo ROLLBACK_OK"; then
    echo "!! 已回滚到 $REMOTE_DIR（坏产物留在 $REMOTE_DIR.bad-$STAMP）" >&2
  else
    echo "!! 自动回滚失败，立刻人工处理: ssh $HOST 'mv $BAK_DIR $REMOTE_DIR && nginx -t && systemctl reload nginx'" >&2
  fi
  return 1
}

# ---------------------------------------------------------------------------
step "7/9 原子切换 + reload"
# 两个 rename 都在同一文件系统内、顺序执行；中途若断，旧目录仍在 $BAK_DIR，可人工 mv 回去。
rsh "set -e; if [ -d '$REMOTE_DIR' ]; then mv '$REMOTE_DIR' '$BAK_DIR'; fi; mv '$STAGING' '$REMOTE_DIR'; ls -ld '$REMOTE_DIR' '$BAK_DIR' 2>/dev/null || true"
if [ "$APPLY" -eq 1 ]; then
  SWITCHED=1
  rout "systemctl reload nginx" || { rollback "systemctl reload nginx 失败"; exit 1; }
else
  say "  [dry-run] ssh $HOST systemctl reload nginx"
fi

# ---------------------------------------------------------------------------
step "8/9 公网验证 $PUBLIC_URL"
if is_dry; then
  say "  将检查: GET $PUBLIC_URL/ 200，页面引用 $MAIN_JS"
  if [ -n "${MAIN_CSS:-}" ]; then say "  将检查: GET $PUBLIC_URL$MAIN_CSS 200"; fi
  say "  将检查: GET $PUBLIC_URL$MAIN_JS?v=$STAMP 200"
else
  CODE_INDEX="$(fetch_code "$PUBLIC_URL/?v=$STAMP")"
  [ "$CODE_INDEX" = "200" ] || { rollback "index 返回 $CODE_INDEX"; exit 1; }
  say "  /       → $CODE_INDEX"

  # 内容级校验：公网 HTML 必须引用刚构建出来的那个 js，防“发了但内容不是刚构建的”
  SERVED_HTML="$(fetch_body "$PUBLIC_URL/?v=$STAMP")"
  case "$SERVED_HTML" in
    *"$MAIN_JS"*) say "  html 引用 $MAIN_JS ✓" ;;
    *) rollback "公网 html 未引用新构建的 $MAIN_JS"; exit 1 ;;
  esac

  if [ -n "${MAIN_CSS:-}" ]; then
    CODE_CSS="$(fetch_code "$PUBLIC_URL$MAIN_CSS")"
    [ "$CODE_CSS" = "200" ] || { rollback "关键资源 $MAIN_CSS 返回 $CODE_CSS"; exit 1; }
    say "  $MAIN_CSS → $CODE_CSS"
  fi
  CODE_JS="$(fetch_code "$PUBLIC_URL$MAIN_JS?v=$STAMP")"
  [ "$CODE_JS" = "200" ] || { rollback "关键资源 $MAIN_JS 返回 $CODE_JS"; exit 1; }
  say "  $MAIN_JS → $CODE_JS"
fi

# ---------------------------------------------------------------------------
step "9/9 清理旧备份（保留 $KEEP 个）"
# 按目录名（时间戳 YYYYmmdd-HHMMSS）排序，不用 mtime——rename 过来的目录 mtime 是旧的，会误删刚做的备份。
# 本次的 $BAK_DIR 永不在删除范围（它同时是回滚点）；.bad-* 取证目录也不动。
rsh "set -e
for d in \$(ls -1d '$REMOTE_DIR'.bak-* 2>/dev/null | sort -r | tail -n +$((KEEP + 1))); do
  [ \"\$d\" = '$BAK_DIR' ] && continue
  echo \"  删除旧备份 \$d\"; rm -rf \"\$d\"
done
echo 现存备份:
ls -1d '$REMOTE_DIR'.bak-* 2>/dev/null | sort -r | head -n 20 || true"

say ""
if is_dry; then
  say "DRY-RUN 结束：以上命令均未执行。确认无误后加 --apply 重跑。"
else
  say "发布完成：$REMOTE_DIR ← $BUNDLE（md5 $MD5_LOCAL）；回滚点 $BAK_DIR"
  say "回滚：ssh $HOST \"mv '$REMOTE_DIR' '$REMOTE_DIR.bad-manual' && mv '$BAK_DIR' '$REMOTE_DIR' && nginx -t && systemctl reload nginx\""
fi
say "清单留存: $MANIFEST"
