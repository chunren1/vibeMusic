#!/usr/bin/env bash
# ============================================================
# reconcile-cloud.sh — 云上部署一致性对账（**纯只读**）
# ============================================================
# 目的
#   云服务器 101.132.167.178:/opt/vibeMusic 不是 git 仓库，靠 scp 部署，
#   因此「云上真源」会与仓库悄悄漂移。本脚本把两个世界的部署文件逐一对账，
#   输出人可读的漂移表，用于把云上真源补回仓库（方向永远只有一个：云上 → 仓库）。
#
# 铁律（本脚本自身遵守，使用者也要守）
#   1. 只读：只会执行 md5sum / wc / crontab -l，绝不写、删、改、重启云上任何东西。
#   2. 凭据只按变量名从 ~/.dsh/.credentials.yaml 读取，绝不硬编码、绝不打印。
#   3. 幂等、可重复跑；任何时候跑结果只取决于当时云上与仓库的状态。
#   4. 不依赖 sshpass：装了就用（sshpass -e 走环境变量），没装自动退化为 key-only ssh。
#
# 退出码
#   0 = 无真漂移（允许「预期差异」，见 3b 节）
#   1 = 存在真漂移（可用于 CI/cron 门禁）
#   3 = 凭据缺失
#   4 = 连不上云 / 远端执行失败
#
# 用法
#   scripts/reconcile-cloud.sh           # 对账，存在漂移则退出码 1
#   scripts/reconcile-cloud.sh --help
# ============================================================
set -uo pipefail
# 强制 C locale：sort 的排序依赖 locale，两边 locale 不同会造成假漂移
export LC_ALL=C

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CREDS="${VIBEMUSIC_CREDS_FILE:-$HOME/.dsh/.credentials.yaml}"

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
fi

# ------------------------------------------------------------
# 1) 凭据：只按变量名取值（值绝不进入 stdout / set -x / 日志）
# ------------------------------------------------------------
cred() { sed -n "s/^  $1:[[:space:]]*//p" "$CREDS" 2>/dev/null | head -1; }

HOST="$(cred VIBEMUSIC_SERVER_HOST)"
SSH_USER="$(cred VIBEMUSIC_SERVER_USER)"
SSH_PASS="$(cred VIBEMUSIC_SERVER_PASSWORD)"

[ -n "$HOST" ] || { echo "✗ 凭据缺失：VIBEMUSIC_SERVER_HOST（读取 $CREDS）" >&2; exit 3; }
[ -n "$SSH_USER" ] || { echo "✗ 凭据缺失：VIBEMUSIC_SERVER_USER（读取 $CREDS）" >&2; exit 3; }

# ------------------------------------------------------------
# 2) 传输方式：sshpass 优先（密码走环境变量），否则 key-only 退化
# ------------------------------------------------------------
SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=15 -o LogLevel=ERROR)
if command -v sshpass >/dev/null 2>&1 && [ -n "$SSH_PASS" ]; then
  export SSHPASS="$SSH_PASS"
  TRANSPORT="sshpass -e ssh（密码经环境变量）"
  ssh_run() { sshpass -e ssh "${SSH_OPTS[@]}" "$SSH_USER@$HOST" "$@"; }
else
  TRANSPORT="key-only ssh（未装 sshpass 或凭据无密码，已退化）"
  ssh_run() { ssh -o BatchMode=yes "${SSH_OPTS[@]}" "$SSH_USER@$HOST" "$@"; }
fi

# ------------------------------------------------------------
# 3) 对账清单：label|本地相对路径|远端路径
#    远端路径以 cmd: 开头表示「执行命令取输出」（用于 crontab）
# ------------------------------------------------------------
MANIFEST='docker-compose.yml|docker-compose.yml|/opt/vibeMusic/docker-compose.yml
docker-compose.full.yml|docker-compose.full.yml|/opt/vibeMusic/docker-compose.full.yml
docker-compose.demo.yml|docker-compose.demo.yml|/opt/vibeMusic/docker-compose.demo.yml
.env.example|.env.example|/opt/vibeMusic/.env.example
nginx/nginx.conf|nginx/nginx.conf|/opt/vibeMusic/nginx/nginx.conf
nginx/docker-entrypoint.sh|nginx/docker-entrypoint.sh|/opt/vibeMusic/nginx/docker-entrypoint.sh
nginx/Dockerfile|nginx/Dockerfile|/opt/vibeMusic/nginx/Dockerfile
nginx/k8s-nginx.conf|nginx/k8s-nginx.conf|/opt/vibeMusic/nginx/k8s-nginx.conf
nginx/logrotate-nginx.conf|nginx/logrotate-nginx.conf|/opt/vibeMusic/nginx/logrotate-nginx.conf
scripts/backup-mysql.sh|scripts/backup-mysql.sh|/opt/vibeMusic/scripts/backup-mysql.sh
scripts/watchdog.sh|scripts/watchdog.sh|/opt/vibeMusic/scripts/watchdog.sh
scripts/health_check.py|scripts/health_check.py|/opt/vibeMusic/scripts/health_check.py
scripts/health-alert.sh|scripts/health-alert.sh|/opt/vibeMusic/scripts/health-alert.sh
scripts/adversarial-chaos.sh|scripts/adversarial-chaos.sh|/opt/vibeMusic/scripts/adversarial-chaos.sh
scripts/adversarial-security.sh|scripts/adversarial-security.sh|/opt/vibeMusic/scripts/adversarial-security.sh
monitoring/prometheus.yml|monitoring/prometheus.yml|/opt/vibeMusic/monitoring/prometheus.yml
monitoring/docker-compose.yml|monitoring/docker-compose.yml|/opt/vibeMusic/monitoring/docker-compose.yml
monitoring/blackbox.yml|monitoring/blackbox.yml|/opt/vibeMusic/monitoring/blackbox.yml
host-nginx: vibemusic-site|deploy/production/nginx/vibemusic-site.conf|/etc/nginx/sites-enabled/vibemusic
host-nginx: security.conf|deploy/production/nginx/security.conf|/etc/nginx/conf.d/security.conf
cloudflared: config.yml|deploy/production/cloudflared/config.yml|/etc/cloudflared/config.yml
host crontab|deploy/production/crontab.txt|cmd:crontab -l'

# ------------------------------------------------------------
# 3b) 预期差异（label|原因）：这些差异是已知且可接受的，打印但不计失败
#     判据：① 生产根本不使用该文件（影子配置）；② 仓库更新但未部署且不影响线上
#     出现新的、不在本表里的差异 = 真漂移 → 退出码 1
# ------------------------------------------------------------
EXPECTED='docker-compose.yml|生产不用：生产栈加载 docker-compose.demo.yml
docker-compose.full.yml|生产不用：本地完整栈模板
docker-compose.demo.yml|仓库为真源：云上副本停留在 2026-10-19，app 容器实际由 scripts/deploy-backend.sh 的 docker run 起（线上容器无 compose 标签，实测 NanoCpus=1.5e9/Memory=768M 与仓库版一致），compose 仅托管 mysql/redis
.env.example|仓库更新未部署：本地模板，生产用自有 .env；两边键集差异来自已下线的 ES/THREADPOOL 配置清理
nginx/nginx.conf|影子配置：生产反代是宿主机 nginx（deploy/production/nginx），仓库版供 CI nginx -t
nginx/docker-entrypoint.sh|影子配置：生产不跑 nginx 容器
monitoring/prometheus.yml|生产未部署：监控文件仅纳入版本管理
monitoring/docker-compose.yml|生产未部署：监控文件仅纳入版本管理
monitoring/blackbox.yml|生产未部署：监控文件仅纳入版本管理
scripts/health_check.py|仓库更新未部署：仓库版已删掉下线的 ES 端点
scripts/adversarial-chaos.sh|仓库更新未部署：演练脚本，不影响线上服务
scripts/adversarial-security.sh|仓库更新未部署：仓库版多了非本机目标必须 --yes 的校验（O5）'

expected_reason() { printf '%s\n' "$EXPECTED" | awk -F'|' -v k="$1" '$1==k {print $2; exit}'; }

# 归一化：crontab 比较「有效行」（去注释/空行/行尾空白），避免注释差异造成假漂移
normalize() { grep -vE '^[[:space:]]*(#|$)' | sed 's/[[:space:]]*$//' | sort; }
needs_norm() { [ "$1" = "host crontab" ]; }

# ------------------------------------------------------------
# 4) 远端一次往返取回全部 md5/行数（只读命令）
# ------------------------------------------------------------
REMOTE_MANIFEST="$(printf '%s\n' "$MANIFEST" | awk -F'|' '{print $1"|"$3}')"

# manifest 走 base64 单引号内联（避免引号/换行地狱），脚本本体经 stdin 送给 `sh -s`
# 注意：不用 `read`（末行无换行时会漏读），改用 IFS=换行 的 for 循环逐条处理
REMOTE_SCRIPT='
set -f
export LC_ALL=C
MANIFEST="$(printf "%s" "$MANIFEST_B64" | base64 -d)"
IFS="
"
for entry in $MANIFEST; do
  [ -z "$entry" ] && continue
  label="${entry%%|*}"; rpath="${entry#*|}"
  case "$rpath" in
    cmd:*)
      out=$(eval "${rpath#cmd:}" 2>/dev/null) || out=""
      m=$(printf "%s\n" "$out" | grep -vE "^[[:space:]]*(#|$)" | sed "s/[[:space:]]*$//" | sort | md5sum | cut -d" " -f1)
      n=$(printf "%s\n" "$out" | grep -vcE "^[[:space:]]*(#|$)")
      ;;
    *)
      if [ -f "$rpath" ]; then
        m=$(md5sum "$rpath" 2>/dev/null | cut -d" " -f1)
        n=$(wc -l < "$rpath" 2>/dev/null | tr -d " ")
      else
        m=MISSING; n="-"
      fi
      ;;
  esac
  printf "%s|%s|%s\n" "$label" "$m" "$n"
done
'

MANIFEST_B64="$(printf '%s' "$REMOTE_MANIFEST" | base64 -w0)"
REMOTE_OUT="$(printf '%s' "$REMOTE_SCRIPT" | ssh_run "MANIFEST_B64='$MANIFEST_B64' sh -s" 2>/dev/null)"
rc=$?
if [ $rc -ne 0 ] || [ -z "$REMOTE_OUT" ]; then
  echo "✗ 无法连接云服务器或远端执行失败（$SSH_USER@$HOST）" >&2
  exit 4
fi

# ------------------------------------------------------------
# 5) 本地侧同样算 md5/行数
# ------------------------------------------------------------
local_stat() {
  local label="$1" lpath="$REPO_ROOT/$2"
  if [ ! -f "$lpath" ]; then
    printf '%s|MISSING|-\n' "$label"; return
  fi
  if needs_norm "$label"; then
    printf '%s|%s|%s\n' "$label" \
      "$(normalize < "$lpath" | md5sum | cut -d' ' -f1)" \
      "$(normalize < "$lpath" | grep -c .)"
  else
    printf '%s|%s|%s\n' "$label" \
      "$(md5sum "$lpath" | cut -d' ' -f1)" \
      "$(wc -l < "$lpath" | tr -d ' ')"
  fi
}

LOCAL_OUT="$(printf '%s\n' "$MANIFEST" | while IFS='|' read -r label lpath _; do
  [ -z "${label:-}" ] && continue
  local_stat "$label" "$lpath"
done)"

# ------------------------------------------------------------
# 6) 出表
# ------------------------------------------------------------
lookup() { printf '%s\n' "$2" | awk -F'|' -v k="$1" '$1==k {print $2"|"$3; exit}'; }

printf '\n云上部署一致性对账 · %s\n' "$(date '+%F %T')"
printf '云主机 : %s@%s:/opt/vibeMusic\n' "$SSH_USER" "$HOST"
printf '仓库   : %s\n' "$REPO_ROOT"
printf '传输   : %s\n' "$TRANSPORT"
printf '%s\n' '────────────────────────────────────────────────────────────────────────────'
printf '%-30s %-9s %-9s %-5s %-5s %s\n' "文件" "md5(云)" "md5(仓)" "行云" "行仓" "状态"
printf '%s\n' '────────────────────────────────────────────────────────────────────────────'

drift=0; total=0; ok=0; expected=0
EXPECTED_HITS=()
while IFS='|' read -r label lpath _; do
  [ -z "${label:-}" ] && continue
  total=$((total+1))
  cm=$(lookup "$label" "$REMOTE_OUT"); c_md5="${cm%%|*}"; c_lines="${cm##*|}"
  lm=$(lookup "$label" "$LOCAL_OUT");  l_md5="${lm%%|*}"; l_lines="${lm##*|}"
  if   [ "$c_md5" = MISSING ] && [ "$l_md5" != MISSING ]; then st="仅仓库有"
  elif [ "$l_md5" = MISSING ] && [ "$c_md5" != MISSING ]; then st="仅云上有"
  elif [ "$c_md5" = "$l_md5" ]; then st="一致"
  else st="★漂移"; fi
  reason=""
  if [ "$st" = "一致" ]; then
    ok=$((ok+1))
  else
    reason="$(expected_reason "$label")"
    if [ -n "$reason" ]; then st="预期差异"; expected=$((expected+1)); EXPECTED_HITS+=("$label")
    else drift=$((drift+1)); fi
  fi
  printf '%-30s %-9s %-9s %-5s %-5s %s\n' \
    "$label" "${c_md5:0:8}" "${l_md5:0:8}" "$c_lines" "$l_lines" "$st"
done <<<"$MANIFEST"

if [ "${#EXPECTED_HITS[@]}" -gt 0 ]; then
  printf '\n预期差异说明（不计入失败，明细见脚本 3b 节）：\n'
  for label in "${EXPECTED_HITS[@]}"; do
    printf '  · %s —— %s\n' "$label" "$(expected_reason "$label")"
  done
fi

printf '%s\n' '────────────────────────────────────────────────────────────────────────────'
if [ "$drift" -eq 0 ]; then
  printf '结果：%d/%d 一致，%d 项预期差异，无真漂移 ✅\n\n' "$ok" "$total" "$expected"
  exit 0
else
  printf '结果：%d 项真漂移 ❌（%d 一致 / %d 预期差异，共 %d；方向只能是 云上 → 仓库，见 docs/cloud-source-of-truth.md）\n\n' \
    "$drift" "$ok" "$expected" "$total"
  exit 1
fi
