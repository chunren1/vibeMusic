#!/usr/bin/env bash
# 后端蓝绿部署（零停机）：本机构建 → 传镜像 → 起 next 容器（另一个端口）→ 等 healthy
#                    → nginx upstream 切到新端口 → reload → 验证 → 停旧容器改名 → 端口轮换。
#
# 为什么蓝绿：现在发版是「停旧容器 → 起新容器 → 等 161 秒冷启动」，用户要断 2.6 分钟。
#            蓝绿把"等待启动"挪到切流量之前：新容器先在备用端口上跑到 healthy，才 reload nginx 切过去；
#            切换本身是毫秒级 reload，对外零停机。任何一步失败时 nginx 一动没动，线上无感。
#
# 内存账（宿主机 1608MB，本方案唯一硬约束）：
#   稳态：backend 360 + mysql 90 + redis 14 + musicapi 74 ≈ 540MB（另有 buff/cache，available 才是准数）
#   蓝绿窗口内多一个 JVM（稳态 ~360MB；启动峰值受 mem_limit 768M 约束）→ 峰值约 900~1300MB
#   ⇒ 切换前必须 available ≥ 400MB，低于此值直接拒绝（--force 可覆盖，但两个 JVM 抢内存会双双变慢）。
#
# ⚠ 首次 --apply 会改一次宿主 nginx 配置（全程只此一次动 /etc/nginx）：
#   1) 新建 /etc/nginx/conf.d/vibemusic-backend-upstream.conf：upstream vibemusic_backend { server 127.0.0.1:<port>; keepalive 16; }
#   2) 把 sites-enabled/vibemusic 里两处 `proxy_pass http://127.0.0.1:8080;` 改成 `proxy_pass http://vibemusic_backend;`
#   改前自动备份 *.bak-<时间戳>；nginx -t 不过就整体还原。之后每次部署只改 upstream 里的端口，幂等跳过。
#   判断依据：sites-enabled/vibemusic 里已出现 vibemusic_backend 字样即视为已迁移。
#
# 数据库迁移（2026-10-29 起）：蓝绿前先跑一台 --rm 一次性容器（FlywayOnlyApplication）做迁移，
#   失败就中止，线上一点不动。新 backend 容器**不再注入** SPRING_FLYWAY_USER/PASSWORD，
#   只保留 DML 账号 —— 于是「后端被攻破也改不了表结构」才真正成立。迁移凭证只来自云端 .env。
#
# 端口轮换：现容器绑哪个端口就从候选里挑下一个空闲端口（8080/8082/8083/8084）。
#           8081 明确排除——宿主机 nginx 自己 listen 127.0.0.1:8081（实测占用，别抢）。
#
# 用法：
#   ./scripts/deploy-backend.sh                          # 默认 dry-run：只打印，不构建/不传/不改远端
#   ./scripts/deploy-backend.sh --apply                  # 真发布
#   ./scripts/deploy-backend.sh --apply --image 2026-10-23
#   ./scripts/deploy-backend.sh --apply --skip-build     # 复用本机已有镜像 tag
#   ./scripts/deploy-backend.sh --apply --from-registry  # 云端只 pull CI 产物（tag=完整 commit sha）
#   ./scripts/deploy-backend.sh --apply --from-registry --image <历史sha>   # 回滚
#   ./scripts/deploy-backend.sh --apply --health-timeout 420
# 约定：只走 SSH key（BatchMode，绝不交互要密码）；脚本内不含任何口令——
#       环境变量一律从现容器 docker inspect 导出（写远端 600 权限 env-file，不上命令行）。
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="$REPO/vibeMusic-backend"

HOST="${HOST:-root@101.132.167.178}"
# 镜像 tag 默认用 git sha（P1-12：产物可追溯，回滚不依赖 latest 指针）；
# 工作区有未提交改动时追加 -dirty，避免"镜像内容与 tag 不一致"的假象。
GIT_SHA="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo latest)"
WORKTREE_DIRTY=0
[ -n "$(git -C "$REPO" status --porcelain 2>/dev/null)" ] && WORKTREE_DIRTY=1
# -dirty 后缀只在「本机构建」路径有意义（registry 模式用完整 sha，脏工作区推不上去）
[ "$WORKTREE_DIRTY" -eq 1 ] && GIT_SHA="${GIT_SHA}-dirty"
IMAGE_EXPLICIT=0
[ -n "${IMAGE:-}" ] && IMAGE_EXPLICIT=1
IMAGE="${IMAGE:-$GIT_SHA}"
SKIP_BUILD=0
# P1-12：CI 把镜像推到 GHCR（tag=完整 commit sha），云端只 pull、不 build、不传 tar。
# 默认关（保持既有的 save|load 路径），--from-registry 打开。
FROM_REGISTRY=0
REGISTRY_IMG="${REGISTRY_IMG:-ghcr.io/chunren1/vibemusic-backend}"
# 预期提交：registry 模式下用来校验镜像 label org.opencontainers.image.revision
FULL_SHA="$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo "")"
EXPECT_REV=""
HEALTH_TIMEOUT=300
FORCE=0
APPLY=0
# --cpus 覆盖新容器的 CPU 配额（默认照抄现容器）。2026-10-23 实测：0.5 核时启动期 86% 周期被限流，
# 生产常态也有 19% 周期被限流，冷启动 136s 里 44s 花在类加载上——瓶颈是配额不是机器（宿主 load 0.45）。
CPUS_OVERRIDE=""
# --set-env 覆盖项（可重复），元素是原样的 KEY=VALUE，不做任何展开
SET_ENV=()

# 远端固定路径（与生产现状一致，改这里等于改部署架构，别乱动）
CUR_NAME="vibemusic-backend"
NEXT_NAME="vibemusic-backend-next"
SITE="/etc/nginx/sites-enabled/vibemusic"
UPSTREAM_CONF="/etc/nginx/conf.d/vibemusic-backend-upstream.conf"
BG_DIR="/root/.vibemusic-bluegreen"
ENVFILE="$BG_DIR/next.env"
# 生产 .env：**唯一**的迁移凭证来源（4/9 的迁移容器从这里取 FLYWAY_USER/FLYWAY_PASSWORD）。
# 不从现容器 inspect 取：新后端已不带 FLYWAY_*，下次部署时现容器里就没有了。
PROD_ENV_FILE="${PROD_ENV_FILE:-/opt/vibeMusic/.env}"
# 站点只 listen 在 127.0.0.1:8081（Cloudflare 隧道回源），验证切换走这里
PROBE_URL="${PROBE_URL:-http://127.0.0.1:8081}"
PROBE_HOST="${PROBE_HOST:-vibe.cyk666.top}"
PORT_CANDIDATES="${PORT_CANDIDATES:-8080 8082 8083 8084}"

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)

usage() {
  cat <<'EOF'
后端蓝绿部署（默认 dry-run）

用法: scripts/deploy-backend.sh [选项]

  --apply               真正执行（默认只打印将执行的命令，零副作用）
  --image TAG           镜像 tag（默认 git sha，或环境变量 IMAGE）
  --skip-build          跳过本地 docker build，复用本机已有的 vibemusic-backend:<TAG>
  --from-registry       不从本机构建/传 tar：云端直接 docker pull $REGISTRY_IMG:<完整 sha>
                        （tag 默认=当前 commit 完整 sha；用 --image <sha> 可回滚到历史镜像）
  --registry IMAGE      registry 镜像名（默认 ghcr.io/chunren1/vibemusic-backend，含 --from-registry）
  --health-timeout N    等新容器 healthy 的秒数上限（默认 300；冷启动约 161s）
  --host USER@IP        目标主机（默认 root@101.132.167.178，或环境变量 HOST）
  --force               内存不足（available < 400MB）时仍然继续
  --cpus N              覆盖新容器的 CPU 配额（默认照抄现容器；例: --cpus 1.5）
  --set-env KEY=VALUE   覆盖/追加新容器的环境变量（可重复，同名后者胜）。
                        在「从现容器 docker inspect 导出」之后生效，值原样写入 env-file
                        （不做 shell 展开、不加引号）；含换行/回车的值会被拒绝。
                        例: --set-env 'JAVA_OPTS=-XX:MaxRAMPercentage=60.0 -XX:+UseG1GC'
  -h, --help            本帮助

流程: 1 预检(端口/内存/容器盘点) → 2 本机 docker build（--from-registry 时跳过）→
      3 save|gzip|ssh load + 旧 latest 打 rollback 标（--from-registry 时改为云端 docker pull + 校验 revision label）
      → 4 起 vibemusic-backend-next（备用端口，env/卷/健康检查照抄现容器，现容器不动）
      → 5 轮询 healthy → 6 写 upstream + 首次迁移 nginx 配置 + reload
      → 7 切换后验证（nginx -T / 直连新端口 / 经 nginx 打探针）→ 8 停旧容器、next 改名转正

失败：upstream 指回原端口 + reload + 删 next，线上保持旧容器，退出非 0 并打印人工处置命令。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --dry-run) APPLY=0 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --from-registry) FROM_REGISTRY=1 ;;
    --registry) REGISTRY_IMG="${2:?--registry 需要镜像名}"; FROM_REGISTRY=1; shift ;;
    --registry=*) REGISTRY_IMG="${1#*=}"; FROM_REGISTRY=1 ;;
    --force) FORCE=1 ;;
    --image) IMAGE="${2:?--image 需要 TAG}"; IMAGE_EXPLICIT=1; shift ;;
    --image=*) IMAGE="${1#*=}"; IMAGE_EXPLICIT=1 ;;
    --health-timeout) HEALTH_TIMEOUT="${2:?--health-timeout 需要秒数}"; shift ;;
    --health-timeout=*) HEALTH_TIMEOUT="${1#*=}" ;;
    --host) HOST="${2:?--host 需要 USER@IP}"; shift ;;
    --host=*) HOST="${1#*=}" ;;
    --set-env) SET_ENV+=("${2:?--set-env 需要 KEY=VALUE}"); shift ;;
    --set-env=*) SET_ENV+=("${1#*=}") ;;
    --cpus) CPUS_OVERRIDE="${2:?--cpus 需要数值，如 1.5}"; shift ;;
    --cpus=*) CPUS_OVERRIDE="${1#*=}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1（见 --help）" >&2; exit 2 ;;
  esac
  shift
done

case "$HEALTH_TIMEOUT" in ''|*[!0-9]*) echo "--health-timeout 必须是整数秒（当前: $HEALTH_TIMEOUT）" >&2; exit 2 ;; esac
case "$CPUS_OVERRIDE" in
  '') ;;
  *[!0-9.]*|.*|*.) echo "--cpus 必须是正数（当前: $CPUS_OVERRIDE）" >&2; exit 2 ;;
esac
if [ "$FROM_REGISTRY" -eq 0 ] && [ "$WORKTREE_DIRTY" -eq 1 ]; then
  echo "WARN: 工作区有未提交改动，镜像 tag 追加 -dirty" >&2
fi

# registry 模式：tag 默认用完整 sha（CI 按完整 sha 推），并据此确定要校验的提交
if [ "$FROM_REGISTRY" -eq 1 ]; then
  if [ "$IMAGE_EXPLICIT" -eq 0 ]; then
    [ -n "$FULL_SHA" ] || { echo "--from-registry 需要 git 仓库以取完整 sha，或显式 --image <sha>" >&2; exit 2; }
    IMAGE="$FULL_SHA"
  fi
  case "$IMAGE" in
    *-dirty) echo "--from-registry 不接受 -dirty tag（工作区改动不可能在 registry 里）" >&2; exit 2 ;;
  esac
  if [ ${#IMAGE} -eq 40 ]; then
    EXPECT_REV="$IMAGE"
  else
    EXPECT_REV="$(git -C "$REPO" rev-parse "$IMAGE" 2>/dev/null || echo "")"
  fi
fi
[ -n "$IMAGE" ] || { echo "--image 不能为空" >&2; exit 2; }

# --set-env 校验：必须是 KEY=VALUE、变量名合法、值里不能有换行/回车。
# env-file 是"一行一个变量"的格式，值里带换行会被拆成两行、静默污染配置 → 直接拒绝，不猜。
for kv in ${SET_ENV[@]+"${SET_ENV[@]}"}; do
  case "$kv" in
    *=*) ;;
    *) echo "--set-env 需要 KEY=VALUE 形式（当前: '$kv'）" >&2; exit 2 ;;
  esac
  env_key="${kv%%=*}"; env_val="${kv#*=}"
  case "$env_key" in
    ''|*[!A-Za-z0-9_]*|[0-9]*)
      echo "--set-env 变量名非法: '$env_key'（只允许 [A-Za-z_][A-Za-z0-9_]*）" >&2; exit 2 ;;
  esac
  case "$env_val" in
    *$'\n'*|*$'\r'*) echo "--set-env $env_key 的值含换行/回车，env-file 一行一个变量，拒绝执行" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# 合并 env：现容器导出（$1）+ 覆盖项（其余参数）→ 写 $2。
# 同名变量在 env-file 里重复出现时 docker 各版本取首个/末个不一致（行为不可靠），
# 所以这里去重：保留**最后一次**出现的值，位置沿用首次出现（顺序稳定、便于人读）。
merge_env_file() {
    local src="$1" dst="$2"; shift 2
    {
        cat "$src"
        if [ "$#" -gt 0 ]; then printf '%s\n' "$@"; fi
    } | awk '
        NF == 0 { next }
        {
            key = $0
            sub(/=.*/, "", key)
            if (key in pos) { line[pos[key]] = $0 }
            else { pos[key] = ++n; line[n] = $0 }
        }
        END { for (i = 1; i <= n; i++) print line[i] }
    ' > "$dst"
}

# 函数源码原样带到远端执行 —— 保证"本地自测的那份"就是"远端跑的那份"，不复制粘贴
MERGE_FN_SRC="$(declare -f merge_env_file)"
# 覆盖项转成 %q 转义的字面量，塞进远端脚本的数组：值不会被本地 shell 展开，也不会被二次拆分
SET_ENV_ARGS=""
for kv in ${SET_ENV[@]+"${SET_ENV[@]}"}; do
  SET_ENV_ARGS+="$(printf '%q' "$kv") "
done
SET_ENV_COUNT="${#SET_ENV[@]}"

# ---------------------------------------------------------------------------
# 镜像内容指纹 + 校验（跨 Docker 版本唯一可靠的判据）
# ⚠ 千万别改回"比 image ID"：实测本地 docker 29.1.3 与云端 29.7.2，同一份 tar 经 docker load 后
#   config JSON 被规范化、.Size 口径也不同 → 本地 id=sha256:666b4dbe… / 云端 id=sha256:668056cc…，
#   ID 必然变；但 RootFS.Layers 9 层 diff_id 逐层相同、Config 的 md5 两边都是 d3d0be0c6381…（内容同一份）。
#   所以判据是：层清单逐层相同 + {{json .Config}} 的 sha256 相同。
image_layers_json() { docker image inspect -f '{{json .RootFS.Layers}}' "$1" 2>/dev/null; }
image_config_sha() { docker image inspect -f '{{json .Config}}' "$1" 2>/dev/null | sha256sum | awk '{print $1}'; }

# 比对两端内容：$1/$2 = 本地/远端层清单 JSON，$3/$4 = 本地/远端 Config sha256
# 一致 → 打印「层 N/N 一致、Config 摘要一致 → 通过」返回 0；否则指出哪一项不一致，返回 1。
verify_image_content() {
    local ll="$1" rl="$2" lc="$3" rc="$4"
    local -a la=() ra=()
    mapfile -t la < <(printf '%s' "$ll" | tr -d '[]" ' | tr ',' '\n' | sed '/^$/d')
    mapfile -t ra < <(printf '%s' "$rl" | tr -d '[]" ' | tr ',' '\n' | sed '/^$/d')
    local ok=1 i matched=0
    if [ "${#la[@]}" -eq 0 ] || [ "${#ra[@]}" -eq 0 ]; then
        printf '  ✗ 取不到层清单（本地 %d 层 / 远端 %d 层），无法校验\n' "${#la[@]}" "${#ra[@]}"
        return 1
    fi
    if [ "${#la[@]}" -ne "${#ra[@]}" ]; then
        printf '  ✗ 层数不一致：本地 %d 层，远端 %d 层\n' "${#la[@]}" "${#ra[@]}"
        ok=0
    fi
    for ((i=0; i<${#la[@]} && i<${#ra[@]}; i++)); do
        if [ "${la[i]}" = "${ra[i]}" ]; then
            matched=$((matched+1))
        else
            printf '  ✗ 第 %d 层不一致：本地 %s，远端 %s\n' "$((i+1))" "${la[i]}" "${ra[i]}"
            ok=0
        fi
    done
    if [ "$lc" != "$rc" ]; then
        printf '  ✗ Config 摘要不一致：本地 %s，远端 %s\n' "$lc" "$rc"
        ok=0
    fi
    if [ "$ok" -eq 1 ]; then
        printf '  层 %d/%d 一致、Config 摘要一致（%s…）→ 通过\n' "$matched" "${#la[@]}" "${lc:0:12}"
        return 0
    fi
    printf '  → 校验未通过：层 %d/%d 一致，Config 摘要%s\n' "$matched" "${#la[@]}" \
        "$([ "$lc" = "$rc" ] && printf '一致' || printf '不一致')"
    return 1
}

# 只把「取指纹」的两个函数带到远端（远端不需要 mapfile）
FP_FN_SRC="$(declare -f image_layers_json image_config_sha)"

STAMP="$(date +%Y%m%d-%H%M%S)"
if [ "$FROM_REGISTRY" -eq 1 ]; then
  LOCAL_IMG="$REGISTRY_IMG:$IMAGE"
else
  LOCAL_IMG="vibemusic-backend:$IMAGE"
fi
# 运行期状态（失败回滚据此决定做什么）
CUR_PORT=""; NEXT_PORT=""; NET=""; AVAIL_MB=0; SWITCHED=0; NGINX_TOUCHED=0; HEALTH_SECS=0

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
is_dry() { [ "$APPLY" -eq 0 ]; }

# 执行（--apply）或只打印（dry-run）。远端写操作一律经 rsh/remote_script，保证 dry-run 零副作用。
run() { if [ "$APPLY" -eq 1 ]; then "$@"; else printf '  [dry-run] %s\n' "$*"; fi; }
rsh() {
  if [ "$APPLY" -eq 1 ]; then ssh "${SSH_OPTS[@]}" "$HOST" "$1"
  else printf '  [dry-run] ssh %s %s\n' "$HOST" "$1"; fi
}
# 只读查询：dry-run 也真跑（不算副作用，且端口/内存必须现场算才准）。
rout() { ssh "${SSH_OPTS[@]}" "$HOST" "$1"; }
# 多行远端脚本：stdin 传过去执行；dry-run 原样打印（--apply 路径的每个写操作都能被完整看到）。
remote_script() {
  if [ "$APPLY" -eq 1 ]; then
    ssh "${SSH_OPTS[@]}" "$HOST" 'bash -s'
  else
    say "  [dry-run] 将经 ssh $HOST 执行以下脚本（未执行）："
    sed 's/^/    | /'
  fi
}

on_interrupt() {
  echo "" >&2
  echo "!! 收到中断信号" >&2
  if [ "$APPLY" -eq 1 ]; then
    if [ "$SWITCHED" -eq 1 ]; then
      echo "!! 流量已切到 $NEXT_PORT：新容器在服务，旧容器还在。要回滚执行：" >&2
      echo "   ssh $HOST \"sed -i 's#server 127.0.0.1:$NEXT_PORT;#server 127.0.0.1:$CUR_PORT;#' $UPSTREAM_CONF && nginx -t && systemctl reload nginx\"" >&2
    else
      ssh "${SSH_OPTS[@]}" "$HOST" "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true; rm -f '$ENVFILE'" >/dev/null 2>&1 || true
      echo "!! 已删除 $NEXT_NAME 与 env 文件；线上未变" >&2
    fi
  fi
  exit 130
}

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺命令: $1" >&2; exit 1; }; }
need ssh; need gzip; need docker
[ -d "$BACKEND" ] || { echo "找不到后端目录 $BACKEND" >&2; exit 1; }
trap on_interrupt INT TERM

# ---------------------------------------------------------------------------
# 失败处置（只在 --apply 路径调用；定义必须在使用之前）
# 未切流量（步骤 1~5 失败）：删 next 即可，nginx 没动。
# 已写配置但 nginx -t 失败：还原 sites-enabled + upstream 备份，从未 reload。
# 已 reload 后失败：upstream 指回原端口 + reload，删 next，旧容器继续服务。
fail_rollback_config() {
  local why="$1"
  echo "" >&2
  echo "!! 失败：$why" >&2
  echo "!! 正在还原 nginx 配置（本次从未 reload，线上仍跑旧配置）" >&2
  ssh "${SSH_OPTS[@]}" "$HOST" 'bash -s' <<EOS >&2 || true
set -uo pipefail
BK='$BG_DIR'; SITE='$SITE'; CONF='$UPSTREAM_CONF'
if [ -f "\$BK/vibemusic.bak-$STAMP" ]; then cp -a "\$BK/vibemusic.bak-$STAMP" "\$SITE"; fi
if [ -f "\$BK/vibemusic-backend-upstream.conf.bak-$STAMP" ]; then
  cp -a "\$BK/vibemusic-backend-upstream.conf.bak-$STAMP" "\$CONF"
else
  rm -f "\$CONF"
fi
nginx -t && echo "  配置已还原且 nginx -t 通过"
EOS
  rsh "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true; rm -f '$ENVFILE'"
  echo "!! 已删除 $NEXT_NAME 与含密钥的 $ENVFILE；线上未变（$CUR_NAME 仍在 127.0.0.1:$CUR_PORT 服务）" >&2
  echo "!! 人工确认: ssh $HOST 'nginx -t; systemctl is-active nginx; docker ps --format \"{{.Names}}\"'" >&2
  exit 1
}

# 收尾阶段（步骤 8）失败专用：此时旧容器可能已删，绝不能把 upstream 指回旧端口（那是指向死人）。
# 只报告现状 + 人工处置命令，让新容器继续服务。
fail_final() {
  local why="$1"
  echo "" >&2
  echo "!! 收尾阶段失败：$why" >&2
  echo "!! 注意：nginx 仍指向 127.0.0.1:$NEXT_PORT（新容器在服务），这里不回滚 nginx。" >&2
  echo "!! 现状：" >&2
  ssh "${SSH_OPTS[@]}" "$HOST" 'docker ps -a --format "   {{.Names}}  {{.Status}}"; echo "   --- nginx ---"; nginx -t 2>&1 | tail -1' >&2 || true
  echo "" >&2
  echo "!! 人工处置：" >&2
  echo "   1) 若 next 还是 next 名字: ssh $HOST \"docker rename $NEXT_NAME $CUR_NAME\"" >&2
  echo "   2) 清理残留旧容器: ssh $HOST \"docker ps -a --format '{{.Names}}' | grep -- '-old-' | xargs -r docker rm -f\"" >&2
  echo "   3) 确认服务: ssh $HOST \"curl -s -o /dev/null -w '%{http_code}\\n' -H 'Host: $PROBE_HOST' $PROBE_URL/uploads/probe.txt\"" >&2
  echo "   4) 删含密钥的 env 文件: ssh $HOST \"rm -f $ENVFILE\"" >&2
  exit 1
}

fail() {
  local why="$1"
  echo "" >&2
  echo "!! 失败：$why" >&2
  if [ "$APPLY" -eq 1 ] && [ "$SWITCHED" -eq 1 ]; then
    echo "!! 回滚：upstream 指回 127.0.0.1:$CUR_PORT 并 reload（旧容器还在，仍在服务）" >&2
    ssh "${SSH_OPTS[@]}" "$HOST" 'bash -s' <<EOS >&2 || echo "!! 自动回滚失败，立刻按下方命令人工处理" >&2
set -uo pipefail
BK='$BG_DIR'; SITE='$SITE'; CONF='$UPSTREAM_CONF'
if [ -f "\$BK/vibemusic-backend-upstream.conf.bak-$STAMP" ]; then
  cp -a "\$BK/vibemusic-backend-upstream.conf.bak-$STAMP" "\$CONF"
else
  printf 'upstream vibemusic_backend {\n    server 127.0.0.1:$CUR_PORT;\n    keepalive 16;\n}\n' > "\$CONF"
fi
if ! nginx -t; then
  echo "  upstream 还原后 nginx -t 仍失败 → 还原 sites-enabled 原文件"
  if [ -f "\$BK/vibemusic.bak-$STAMP" ]; then cp -a "\$BK/vibemusic.bak-$STAMP" "\$SITE" && nginx -t; fi
fi
systemctl reload nginx && echo "  已回滚：upstream → 127.0.0.1:$CUR_PORT 并 reload"
EOS
  fi
  rsh "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true; rm -f '$ENVFILE'"
  echo "!! 已删除 $NEXT_NAME 与含密钥的 $ENVFILE；$CUR_NAME 未被动过" >&2
  echo "" >&2
  echo "!! 人工处置命令：" >&2
  echo "   ssh $HOST 'nginx -t && systemctl reload nginx'" >&2
  echo "   ssh $HOST 'curl -s -o /dev/null -w \"%{http_code}\\n\" -H \"Host: $PROBE_HOST\" $PROBE_URL/uploads/probe.txt'" >&2
  echo "   ssh $HOST 'docker ps --format \"{{.Names}} {{.Status}}\"; free -m'" >&2
  echo "   配置备份: $HOST:$BG_DIR/vibemusic.bak-$STAMP / vibemusic-backend-upstream.conf.bak-$STAMP" >&2
  echo "   回滚镜像: vibemusic-backend:rollback-$STAMP" >&2
  exit 1
}

if is_dry; then
  say "*** DRY-RUN：只打印命令 + 只读盘点远端，不构建、不传镜像、不碰 nginx/容器。加 --apply 才真发。***"
else
  say "*** APPLY：将真实发布到 $HOST（蓝绿，先起新容器再切流量）***"
fi
say "镜像 $LOCAL_IMG | 健康超时 ${HEALTH_TIMEOUT}s | 时间戳 $STAMP"

# ===========================================================================
step "1/9 预检：远端容器/端口/内存盘点（只读）"
if is_dry; then
  if [ "$FROM_REGISTRY" -eq 1 ]; then
    say "  [dry-run] 云端将执行: docker pull $LOCAL_IMG（不在本机构建、不传 tar）"
  else
    say "  [dry-run] 本地将执行: docker build -t $LOCAL_IMG $BACKEND"
  fi
fi

# 一次性把远端关键状态读回来（全部只读：docker inspect / free / ss）
DISCOVER_OUT="$(rout 'bash -s' <<'EOS'
set -uo pipefail
C=vibemusic-backend
if ! docker inspect "$C" >/dev/null 2>&1; then echo "DISCOVER_ERR=$C 不存在"; exit 0; fi
echo "CUR_PORT=$(docker inspect -f '{{with index .HostConfig.PortBindings "8080/tcp"}}{{(index . 0).HostPort}}{{end}}' "$C")"
echo "NET=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$C")"
echo "HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$C")"
echo "RSS=$(docker stats --no-stream --format '{{.MemUsage}}' "$C" 2>/dev/null | awk '{print $1}')"
echo "AVAIL_MB=$(free -m | awk '/^Mem:/{print $7}')"
echo "MEM_TOTAL_MB=$(free -m | awk '/^Mem:/{print $2}')"
echo "SWAP_USED_MB=$(free -m | awk '/^Swap:/{print $3}')"
echo "FREE_PORTS=$(for p in 8080 8082 8083 8084; do ss -tlnH "sport = :$p" 2>/dev/null | grep -q . || printf '%s ' "$p"; done)"
echo "NEXT_EXISTS=$(docker inspect vibemusic-backend-next >/dev/null 2>&1 && echo yes || echo no)"
echo "NGINX_ACTIVE=$(systemctl is-active nginx 2>/dev/null || echo unknown)"
echo "SITE_MIGRATED=$(grep -q 'vibemusic_backend' /etc/nginx/sites-enabled/vibemusic 2>/dev/null && echo yes || echo no)"
echo "HA_URL=$(grep -oE '127\.0\.0\.1:[0-9]+/actuator/health' /opt/vibeMusic/scripts/health-alert.sh 2>/dev/null | head -1)"
EOS
)"
get() { printf '%s\n' "$DISCOVER_OUT" | sed -n "s/^$1=//p" | head -1; }

DISCOVER_ERR="$(get DISCOVER_ERR)"
[ -z "$DISCOVER_ERR" ] || { echo "预检失败：$DISCOVER_ERR（线上没有 $CUR_NAME 容器，本脚本不负责首次装机）" >&2; exit 1; }

CUR_PORT="$(get CUR_PORT)"
NET="$(get NET)"
CUR_HEALTH="$(get HEALTH)"
CUR_RSS="$(get RSS)"
AVAIL_MB="$(get AVAIL_MB)"
MEM_TOTAL_MB="$(get MEM_TOTAL_MB)"
SWAP_USED_MB="$(get SWAP_USED_MB)"
FREE_PORTS="$(get FREE_PORTS)"
NEXT_EXISTS="$(get NEXT_EXISTS)"
NGINX_ACTIVE="$(get NGINX_ACTIVE)"
SITE_MIGRATED="$(get SITE_MIGRATED)"
HA_URL="$(get HA_URL)"

[ -n "$CUR_PORT" ] || { echo "预检失败：读不到 $CUR_NAME 的 8080/tcp 宿主端口映射" >&2; exit 1; }
[ -n "$NET" ] || { echo "预检失败：读不到 $CUR_NAME 的网络" >&2; exit 1; }

say "  当前服务容器 $CUR_NAME：端口 127.0.0.1:$CUR_PORT | 网络 $NET | 健康 $CUR_HEALTH | RSS $CUR_RSS"
say "  宿主机内存 ${MEM_TOTAL_MB}MB，available ${AVAIL_MB}MB，swap 已用 ${SWAP_USED_MB}MB"
say "  nginx $NGINX_ACTIVE；sites-enabled 已迁移到 upstream 名: $SITE_MIGRATED"

# 端口轮换：跳过现用端口，从候选里挑第一个空闲的。8081 不在候选里（nginx 自用）。
NEXT_PORT=""
for p in $PORT_CANDIDATES; do
  [ "$p" = "$CUR_PORT" ] && continue
  case " $FREE_PORTS " in
    *" $p "*) NEXT_PORT="$p"; break ;;
  esac
done
[ -n "$NEXT_PORT" ] || { echo "预检失败：候选端口（$PORT_CANDIDATES）全被占用，无法起新容器" >&2; exit 1; }
say "  端口规划：现用 $CUR_PORT → 新容器 $NEXT_PORT（8081 跳过：宿主 nginx 自用；候选 $PORT_CANDIDATES）"

if [ "$CUR_HEALTH" != "healthy" ]; then
  say "  ⚠ 现容器健康状态是 $CUR_HEALTH（不是 healthy）。蓝绿仍可进行，但请留意旧容器是否本来就带病。"
fi

# 内存闸门：两个 JVM 同时在跑，available 不够就是拿线上赌
MEM_FLOOR=400
if [ "$AVAIL_MB" -lt "$MEM_FLOOR" ]; then
  say ""
  say "  !! 内存不足：available ${AVAIL_MB}MB < ${MEM_FLOOR}MB"
  say "     蓝绿窗口内两个 JVM 并存（每个稳态 ~360MB，启动峰值受 768M limit 约束），此时切换极易双双换页变慢。"
  if [ "$FORCE" -eq 0 ]; then
    if is_dry; then
      say "     dry-run 继续打印完整流程；真跑 --apply 时这里会直接拒绝（确需强行：--force）。"
    else
      echo "拒绝执行：内存不足 ${AVAIL_MB}MB < ${MEM_FLOOR}MB（确需强行加 --force）" >&2
      exit 3
    fi
  else
    say "     --force 已指定，继续（风险自负）。"
  fi
else
  say "  内存闸门通过：available ${AVAIL_MB}MB ≥ ${MEM_FLOOR}MB（窗口内预估峰值 ~$((AVAIL_MB - 360))MB 余量）"
fi

if [ "$NEXT_EXISTS" = "yes" ]; then
  say "  发现残留的 $NEXT_NAME（上次失败/中断留下的），第 4 步会先删掉它再起新的。"
fi

# ===========================================================================
if [ "$FROM_REGISTRY" -eq 1 ]; then
  step "2/9 跳过本地构建（--from-registry：镜像由 CI 构建并推到 $REGISTRY_IMG，云端只 pull）"
  say "  目标镜像: $LOCAL_IMG"
  say "  期望提交: ${EXPECT_REV:-<未指定，跳过 revision 校验>}"
  LOCAL_ID="(registry)"
else
step "2/9 本地构建镜像（云上只有 1.6G 内存，跑不动 mvn，所以构建放本机）"
LOCAL_ARCH="$(uname -m)"
case "$LOCAL_ARCH" in
  x86_64|amd64) ;;
  *) echo "本机架构 $LOCAL_ARCH 与云主机 amd64 不一致，构建出的镜像跑不起来" >&2; exit 1 ;;
esac

if [ "$SKIP_BUILD" -eq 1 ]; then
  say "  --skip-build：复用本机已有镜像 $LOCAL_IMG"
  if is_dry; then
    say "  [dry-run] 将检查: docker image inspect $LOCAL_IMG"
  else
    docker image inspect "$LOCAL_IMG" >/dev/null 2>&1 \
      || { echo "本机没有镜像 $LOCAL_IMG，去掉 --skip-build 或先手动构建" >&2; exit 1; }
  fi
else
  # Dockerfile 是 COPY target/*.jar app.jar，没有 jar 就构建必然失败，先 fail-fast 给准话
  JAR="$(ls -1 "$BACKEND"/target/*.jar 2>/dev/null | head -1 || true)"
  if [ -z "$JAR" ]; then
    echo "找不到 $BACKEND/target/*.jar —— 先打包再发版：cd vibeMusic-backend && ./mvnw package -DskipTests" >&2
    exit 1
  fi
  say "  构建上下文 $BACKEND（jar: $(basename "$JAR")）"
  run docker build -t "$LOCAL_IMG" "$BACKEND"
fi

if is_dry; then
  LOCAL_ID="(dry-run)"
  say "  镜像: $LOCAL_IMG  $LOCAL_ID"
else
  LOCAL_ID="$(docker inspect -f '{{.Id}}' "$LOCAL_IMG")"
  LOCAL_SIZE="$(docker image inspect -f '{{.Size}}' "$LOCAL_IMG" | awk '{printf "%.0fMB", $1/1024/1024}')"
  say "  镜像: $LOCAL_IMG  id=${LOCAL_ID:0:19}  $LOCAL_SIZE（id/size 仅展示：跨 Docker 版本会变，3/9 比的是层+Config 内容）"
fi
fi

# ===========================================================================
if [ "$FROM_REGISTRY" -eq 1 ]; then
step "3/9 从 registry 取镜像（云端只 pull，不 build、不传 tar）+ 旧 latest 保底打标"
rsh "mkdir -p '$BG_DIR' && chmod 700 '$BG_DIR'"
if is_dry; then
  say "  [dry-run] ssh $HOST 'docker pull $LOCAL_IMG'"
else
  say "  拉取中（首次约 300MB；分层已命中则只拉差异层）..."
fi

# 拉完立刻验两件事，任何一项不符就停在 3/9（容器与 nginx 一动没动）：
#   1) label org.opencontainers.image.revision == 期望提交 —— 证明「拉到的就是那个提交」的产物；
#      没有这个 label 的镜像不是本仓 CI 产物（或手工 build 漏了 label），一律拒绝部署。
#   2) 架构 amd64 —— registry 里若混入 arm 产物，拉下来也起不来。
remote_script <<EOS
set -euo pipefail
IMG='$LOCAL_IMG'
EXPECT_REV='$EXPECT_REV'
docker pull "\$IMG"
REV=\$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "\$IMG" 2>/dev/null || true)
ARCH=\$(docker inspect -f '{{.Architecture}}' "\$IMG" 2>/dev/null || true)
echo "  远端镜像 \$IMG arch=\${ARCH:-?} revision=\${REV:-<无 label>}"
[ -n "\$REV" ] || { echo "  !! 镜像缺少 org.opencontainers.image.revision label（非 CI 产物），拒绝部署" >&2; exit 3; }
if [ -n "\$EXPECT_REV" ] && [ "\$REV" != "\$EXPECT_REV" ]; then
  echo "  !! label revision=\$REV 与期望提交 \$EXPECT_REV 不一致，拒绝部署" >&2; exit 3
fi
case "\$ARCH" in amd64|x86_64) ;; *) echo "  !! 镜像架构 \$ARCH 与云主机 amd64 不一致，拒绝部署" >&2; exit 3 ;; esac
NEW_ID=\$(docker inspect -f '{{.Id}}' "\$IMG")
echo "  远端镜像 \$IMG id=\${NEW_ID:0:19}"
if docker image inspect vibemusic-backend:latest >/dev/null 2>&1; then
  OLD_ID=\$(docker inspect -f '{{.Id}}' vibemusic-backend:latest)
  if [ "\$OLD_ID" != "\$NEW_ID" ]; then
    docker tag vibemusic-backend:latest vibemusic-backend:rollback-$STAMP
    echo "  旧 latest 已保底: vibemusic-backend:rollback-$STAMP (id=\${OLD_ID:0:19})"
  else
    echo "  latest 已指向新镜像，无需保底"
  fi
fi
docker tag "\$IMG" vibemusic-backend:latest
echo "  latest → \${NEW_ID:0:19}"
EOS

else
step "3/9 传镜像（docker save | gzip | ssh 'gunzip | docker load'）+ 旧 latest 保底打标"
rsh "mkdir -p '$BG_DIR' && chmod 700 '$BG_DIR'"

if is_dry; then
  say "  [dry-run] docker save $LOCAL_IMG | gzip -c | ssh $HOST 'gunzip | docker load'"
else
  say "  传输中（gzip 压缩后走 ssh，约 200~300MB 流量）..."
  docker save "$LOCAL_IMG" | gzip -c | ssh "${SSH_OPTS[@]}" "$HOST" 'gunzip | docker load' >/dev/null
fi

remote_script <<EOS
set -euo pipefail
IMG='$LOCAL_IMG'
docker image inspect "\$IMG" >/dev/null 2>&1 || { echo "  !! 远端没有 \$IMG，传输失败"; exit 3; }
NEW_ID=\$(docker inspect -f '{{.Id}}' "\$IMG")
echo "  远端镜像 \$IMG id=\${NEW_ID:0:19}"
# 先把旧 latest 保底成 rollback-<ts>，再让 latest 指向新镜像（顺序不能反，否则回滚点丢失）
if docker image inspect vibemusic-backend:latest >/dev/null 2>&1; then
  OLD_ID=\$(docker inspect -f '{{.Id}}' vibemusic-backend:latest)
  if [ "\$OLD_ID" != "\$NEW_ID" ]; then
    docker tag vibemusic-backend:latest vibemusic-backend:rollback-$STAMP
    echo "  旧 latest 已保底: vibemusic-backend:rollback-$STAMP (id=\${OLD_ID:0:19})"
  else
    echo "  latest 已指向新镜像，无需保底"
  fi
fi
docker tag "\$IMG" vibemusic-backend:latest
echo "  latest → \${NEW_ID:0:19}"
EOS

# 不信报告信字节，但"字节"要比内容而不是 image ID：
# 跨 Docker 版本 docker load 会规范化 config JSON（29.1.3 → 29.7.2 实测 id 必变），比 ID 会误杀。
# 判据 = RootFS.Layers 逐层相同 + {{json .Config}} 的 sha256 相同。
LOCAL_LAYERS="$(image_layers_json "$LOCAL_IMG" || true)"
LOCAL_CFG_SHA="$(image_config_sha "$LOCAL_IMG" || true)"

if is_dry; then
  say "  校验方式：层清单（{{json .RootFS.Layers}} 逐层）+ Config 摘要（{{json .Config}} 的 sha256）"
  say "            —— 不比 image ID：跨 Docker 版本 docker load 会规范化 config，ID 必然变"
  REMOTE_FP="$(rout "$FP_FN_SRC
image_layers_json '$LOCAL_IMG'
image_config_sha '$LOCAL_IMG'" 2>/dev/null || true)"
  if [ -n "$REMOTE_FP" ] && [ -n "$LOCAL_LAYERS" ]; then
    say "  dry-run 只读预校验（不传镜像，比的是云端现有该 tag 的内容）："
    verify_image_content "$LOCAL_LAYERS" "$(printf '%s\n' "$REMOTE_FP" | head -1)" \
      "$LOCAL_CFG_SHA" "$(printf '%s\n' "$REMOTE_FP" | tail -1)" \
      || say "  ↑ dry-run 不中止：真跑时会在传输后重新校验，不一致就停在 3/9（容器/nginx 不动）"
  else
    say "  [dry-run] 将校验: 远端层清单 + Config 摘要 == 本地（传输完成后进行；当前取不到云端该 tag 或本地镜像）"
  fi
else
  REMOTE_FP="$(rout "$FP_FN_SRC
image_layers_json '$LOCAL_IMG'
image_config_sha '$LOCAL_IMG'" || true)"
  verify_image_content "$LOCAL_LAYERS" "$(printf '%s\n' "$REMOTE_FP" | head -1)" \
    "$LOCAL_CFG_SHA" "$(printf '%s\n' "$REMOTE_FP" | tail -1)" \
    || { echo "镜像内容校验失败，中止在 3/9（容器与 nginx 一动没动；远端 latest 已指向该 tag）" >&2; exit 1; }
fi
fi

# ===========================================================================
step "4/9 数据库迁移（独立一次性容器，跑完即退；失败则不切流量，线上一点没变）"
say "  为什么单独一步：migrator 账号有 DDL 权限。它一旦注进长期运行的 backend 容器，"
say "  应用被 RCE 后读 /proc/self/environ 就能拿到 DDL 凭证 —— 那样「最小权限」只是纸面。"
say "  所以：迁移由 --rm 容器跑完即退，新 backend 只保留 DML 账号（SPRING_FLYWAY_ENABLED=false）。"
say "  凭证来源：云端 /opt/vibeMusic/.env 的 FLYWAY_USER/FLYWAY_PASSWORD（写 600 env-file，不上命令行）；"
say "            连接参数照抄现容器的 DB_HOST/DB_USERNAME/DB_PASSWORD。"
if is_dry; then
  say "  [dry-run] 将执行: docker run --rm --name vibemusic-flyway-init --network $NET --memory 256m \\"
  say "              --env-file <600 权限临时文件> --entrypoint sh $LOCAL_IMG -c \\"
  say "              'java \$JAVA_OPTS -Dloader.main=com.vibemusic.bootstrap.FlywayOnlyApplication -cp app.jar PropertiesLauncher'"
  say "  [dry-run] 退出码非 0 → 直接中止（不起新容器、nginx 不动）"
else
set +e
remote_script <<EOS
set -euo pipefail
C='$CUR_NAME'
IMG='$LOCAL_IMG'
NET='$NET'
MIGENV='$BG_DIR/flyway.env'
PRODENV='$PROD_ENV_FILE'
mkdir -p '$BG_DIR' && chmod 700 '$BG_DIR'
umask 077

[ -r "\$PRODENV" ] || { echo "预检失败：读不到 \$PRODENV（迁移凭证来源）" >&2; exit 1; }

# 1) 连接参数照抄现容器（与线上完全一致），只挑 DB_* 三项
{
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "\$C" | grep -E '^(DB_HOST|DB_USERNAME|DB_PASSWORD)=' || true
  # 2) 迁移凭证从云端 .env 取 —— 不能从现容器取：新 backend 已不带 FLYWAY_*，
  #    下次部署时现容器里就没有它们了（这次能取到只是过渡期的巧合）。
  grep -E '^(FLYWAY_USER|FLYWAY_PASSWORD)=' "\$PRODENV" || true
} > "\$MIGENV"

# 3) 缺关键项就停：宁可部署失败，也不要"以为迁了其实没迁"
MISSING=""
grep -q '^DB_HOST='        "\$MIGENV" || echo 'DB_HOST=mysql' >> "\$MIGENV"
grep -q '^DB_USERNAME='    "\$MIGENV" || echo 'DB_USERNAME=vibemusic' >> "\$MIGENV"
grep -q '^FLYWAY_USER='    "\$MIGENV" || echo 'FLYWAY_USER=vibemusic_migrator' >> "\$MIGENV"
for k in DB_PASSWORD FLYWAY_PASSWORD; do
  grep -q "^\$k=" "\$MIGENV" || MISSING="\$MISSING \$k"
done
if [ -n "\$MISSING" ]; then
  echo "预检失败：迁移环境缺\$MISSING（检查 \$PRODENV 与现容器 env）" >&2
  rm -f "\$MIGENV"
  exit 1
fi
{
  echo 'SPRING_PROFILES_ACTIVE=docker'
  echo 'TZ=Asia/Shanghai'
  echo 'JAVA_OPTS=-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC'
} >> "\$MIGENV"
chmod 600 "\$MIGENV"
echo "  迁移容器 env（只列名）: \$(cut -d= -f1 "\$MIGENV" | tr '\n' ' ')"

# 4) 跑迁移：--rm 保证不留垃圾容器；退出码就是迁移结果
docker rm -f vibemusic-flyway-init >/dev/null 2>&1 || true
set +e
docker run --rm --name vibemusic-flyway-init --network "\$NET" \
  --memory 256m --cpus 0.5 --env-file "\$MIGENV" \
  --entrypoint sh "\$IMG" \
  -c 'exec java \$JAVA_OPTS -Dloader.main=com.vibemusic.bootstrap.FlywayOnlyApplication -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher'
MIG_RC=\$?
set -e
rm -f "\$MIGENV"
if [ "\$MIG_RC" -ne 0 ]; then
  echo "迁移失败（rc=\$MIG_RC）：未起新容器、nginx 未动，线上仍是 \$C" >&2
  exit 1
fi
echo "  迁移完成（rc=0），schema 已是最新"
EOS
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "数据库迁移失败（rc=$RC）：部署已中止，线上未变（$CUR_NAME 仍在 127.0.0.1:$CUR_PORT 服务）" >&2
  echo "排查看上面迁移容器输出（常见：migrator 缺 DDL/ROUTINE 权限、迁移 SQL 报错、schema 校验不一致）" >&2
  exit 1
fi
fi

# ===========================================================================
step "5/9 起新容器 $NEXT_NAME（127.0.0.1:$NEXT_PORT→8080，现有容器不动）"
say "  配置来源：docker inspect $CUR_NAME —— 环境变量（排除 PATH/JAVA_HOME/LANG 等基础项）、卷、网络、"
say "            健康检查、mem_limit/cpus、日志轮转全部照抄，保证新容器与线上等价。"
if [ "$SET_ENV_COUNT" -gt 0 ]; then
  say "  --set-env 覆盖 $SET_ENV_COUNT 项（在 docker inspect 导出之后生效，同名以后者为准）："
  for kv in "${SET_ENV[@]}"; do
    k="${kv%%=*}"; v="${kv#*=}"
    if is_dry; then
      # 只露前 12 字符：既能确认改对了，又不会把一长串 JVM 参数刷屏
      say "    $k=***${v:0:12}..."
    else
      say "    $k=（值已隐藏，${#v} 字符）"
    fi
  done
fi

set +e
remote_script <<EOS
set -euo pipefail
C='$CUR_NAME'
NEW='$NEXT_NAME'
IMG='$LOCAL_IMG'
PORT='$NEXT_PORT'
NET='$NET'
ENVF='$ENVFILE'
mkdir -p '$BG_DIR' && chmod 700 '$BG_DIR'

# 上次失败可能留下同名容器，先清掉（它没在 nginx 配置里，删掉不影响线上）
if docker inspect "\$NEW" >/dev/null 2>&1; then
  echo "  清理残留容器 \$NEW"
  docker rm -f "\$NEW" >/dev/null
fi

# 1) 环境变量：先整份导出（剔除基础镜像自带项），再合并 --set-env 覆盖项并去重。
#    顺序很重要：覆盖必须发生在"导出之后"，否则旧值会把新值盖回去（本次改 JAVA_OPTS 就靠这个）。
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "\$C" \
  | grep -Ev '^(PATH|JAVA_HOME|JAVA_VERSION|LANG|LANGUAGE|LC_ALL|HOSTNAME|TERM)=' > "\$ENVF.raw"
$MERGE_FN_SRC
merge_env_file "\$ENVF.raw" "\$ENVF" $SET_ENV_ARGS
rm -f "\$ENVF.raw"
# 新容器不再持有 DDL 凭证（迁移已由 4/9 的一次性容器做完）。
# 必须在这里剥：现容器 env 里还留着 SPRING_FLYWAY_USER/PASSWORD，不剥就会原样带到新容器，
# 拆 flyway-init 等于白拆。--set-env 传进来的同名项也一并剥掉（口令轮换只改云端 .env）。
if grep -qE '^SPRING_FLYWAY_(USER|PASSWORD)=' "\$ENVF"; then
  echo "  剥离 SPRING_FLYWAY_USER/PASSWORD（新容器只保留 DML 账号；迁移凭证只在 4/9 那次性容器里用过）"
  grep -Ev '^SPRING_FLYWAY_(USER|PASSWORD)=' "\$ENVF" > "\$ENVF.tmp"
  mv "\$ENVF.tmp" "\$ENVF"
fi
grep -q '^SPRING_FLYWAY_ENABLED=' "\$ENVF" || echo 'SPRING_FLYWAY_ENABLED=false' >> "\$ENVF"
chmod 600 "\$ENVF"
echo "  环境变量 \$(wc -l < "\$ENVF") 条（其中 --set-env 覆盖 $SET_ENV_COUNT 项，已去重；只列名，值不入日志）: \$(cut -d= -f1 "\$ENVF" | tr '\n' ' ')"

# 2) 卷：按 Mounts 生成 -v（保留 rw/ro）
VOLS=()
while IFS='|' read -r src dst rw; do
  [ -n "\${src:-}" ] || continue
  if [ "\$rw" = "true" ]; then VOLS+=(-v "\$src:\$dst:rw"); else VOLS+=(-v "\$src:\$dst:ro"); fi
done < <(docker inspect -f '{{range .Mounts}}{{.Source}}|{{.Destination}}|{{.RW}}{{println}}{{end}}' "\$C")

# 3) 健康检查：照抄现容器（compose 覆盖过：liveness + 15s + start_period 180s + 5 次，
#    镜像 Dockerfile 里那份 30s/3 次/actuator/health 会在冷启动期误判，别用）
HC_ARGS=()
HC_TEST=\$(docker inspect -f '{{if .Config.Healthcheck}}{{join .Config.Healthcheck.Test " "}}{{end}}' "\$C")
if [ -n "\${HC_TEST:-}" ]; then
  case "\$HC_TEST" in
    NONE) ;;
    CMD-SHELL\ *) HC_ARGS+=(--health-cmd "\${HC_TEST#CMD-SHELL }") ;;
    CMD\ *)       HC_ARGS+=(--health-cmd "\${HC_TEST#CMD }") ;;
    *)            HC_ARGS+=(--health-cmd "\$HC_TEST") ;;
  esac
  HC_ARGS+=(--health-interval "\$(docker inspect -f '{{.Config.Healthcheck.Interval}}' "\$C")")
  HC_ARGS+=(--health-timeout  "\$(docker inspect -f '{{.Config.Healthcheck.Timeout}}' "\$C")")
  HC_ARGS+=(--health-retries  "\$(docker inspect -f '{{.Config.Healthcheck.Retries}}' "\$C")")
  SP=\$(docker inspect -f '{{.Config.Healthcheck.StartPeriod}}' "\$C")
  if [ -n "\$SP" ] && [ "\$SP" != "0s" ]; then HC_ARGS+=(--health-start-period "\$SP"); fi
fi

# 4) 资源/重启/日志：照抄现容器（不抄就会变成无上限，1.6G 机器上很危险）
MEM=\$(docker inspect -f '{{.HostConfig.Memory}}' "\$C")
NANO=\$(docker inspect -f '{{.HostConfig.NanoCpus}}' "\$C")
RESTART=\$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "\$C")
LOGOPTS=()
while read -r kv; do
  [ -n "\${kv:-}" ] || continue
  LOGOPTS+=(--log-opt "\$kv")
done < <(docker inspect -f '{{range \$k,\$v := .HostConfig.LogConfig.Config}}{{\$k}}={{\$v}}{{"\n"}}{{end}}' "\$C")

# 5) 入口：现容器的 Entrypoint 来自 compose/手工覆盖，镜像本身没有，必须显式带上
EP=(); CMD=()
# sed '/^$/d'：docker inspect -f 自己会在输出末尾补换行，{{range}} 又给每个元素补换行，
# 结果 mapfile 的最后一个元素恒为空串。不滤掉就会出现 `sh '' -c ...`（空脚本名），
# 容器起来就死循环重启，日志只有 "sh: can't open '': No such file or directory"（2026-10-23 实测踩到）。
mapfile -t EP  < <(docker inspect -f '{{range .Config.Entrypoint}}{{.}}{{"\n"}}{{end}}' "\$C" | sed '/^$/d')
mapfile -t CMD < <(docker inspect -f '{{range .Config.Cmd}}{{.}}{{"\n"}}{{end}}' "\$C" | sed '/^$/d')
if [ \${#CMD[@]} -eq 0 ]; then
  echo "预检失败：现容器的 Entrypoint/Cmd 都为空，重建不出启动命令（别猜，先看 docker inspect \$C）" >&2
  exit 1
fi

RUN=(docker run -d --name "\$NEW" --network "\$NET" -p "127.0.0.1:\$PORT:8080"
     --restart "\${RESTART:-unless-stopped}" --env-file "\$ENVF"
     --memory "\$MEM" "\${LOGOPTS[@]}" "\${VOLS[@]}" "\${HC_ARGS[@]}")
if [ -n "$CPUS_OVERRIDE" ]; then
  # 显式 --cpus 覆盖（配额是上限不是预留，见脚本头注释）
  RUN+=(--cpus "$CPUS_OVERRIDE")
elif [ "\$NANO" != "0" ]; then
  # awk 里 \$NANO 是字段引用（取不到 shell 变量），必须 -v 传进去，否则会得到 0.00
  RUN+=(--cpus "\$(awk -v n="\$NANO" 'BEGIN{printf "%.2f", n/1000000000}')")
fi
if [ \${#EP[@]} -gt 0 ]; then RUN+=(--entrypoint "\${EP[0]}"); fi
RUN+=("\$IMG")
if [ \${#EP[@]} -gt 1 ]; then RUN+=("\${EP[@]:1}"); fi
if [ \${#CMD[@]} -gt 0 ]; then RUN+=("\${CMD[@]}"); fi

echo "  将执行（env 走 600 权限 env-file，命令行不出现任何值）:"
printf '    '; printf '%q ' "\${RUN[@]}"; echo
"\${RUN[@]}"
sleep 2
docker inspect -f '  已启动: {{.Name}} {{.State.Status}} 端口={{json .HostConfig.PortBindings}}' "\$NEW"
EOS
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "起 $NEXT_NAME 失败（rc=$RC）" >&2
  rsh "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true"
  echo "线上未变（$CUR_NAME 仍在 127.0.0.1:$CUR_PORT 服务，nginx 未动）" >&2
  exit 1
fi

# ===========================================================================
step "6/9 等新容器 healthy（上限 ${HEALTH_TIMEOUT}s；冷启动约 161s）"
if is_dry; then
  say "  [dry-run] 将每 10s 轮询: docker inspect -f '{{.State.Health.Status}}' $NEXT_NAME"
  say "  [dry-run] 直到 healthy 或超过 ${HEALTH_TIMEOUT}s；超时则 docker rm -f $NEXT_NAME 并退出非 0（线上一点没变）"
else
  T0="$(date +%s)"
  DEADLINE=$(( T0 + HEALTH_TIMEOUT ))
  while :; do
    NOW="$(date +%s)"
    ELAPSED=$(( NOW - T0 ))
    ST="$(rout "docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' '$NEXT_NAME' 2>/dev/null || echo missing")"
    RUNNING="$(rout "docker inspect -f '{{.State.Running}}' '$NEXT_NAME' 2>/dev/null || echo false")"
    RESTARTS="$(rout "docker inspect -f '{{.RestartCount}}' '$NEXT_NAME' 2>/dev/null || echo 0")"
    say "  [+${ELAPSED}s] 状态=$ST running=$RUNNING restarts=$RESTARTS"
    # --restart unless-stopped 会让崩溃容器反复重启，Running 永远是 true，只能靠重启次数识别死循环
    if [ "${RESTARTS:-0}" -gt 3 ] 2>/dev/null; then
      echo "新容器反复重启（RestartCount=$RESTARTS），最后 30 行日志：" >&2
      rout "docker logs --tail 30 '$NEXT_NAME' 2>&1" >&2 || true
      rsh "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true"
      echo "线上未变（仍是 $CUR_NAME:$CUR_PORT），nginx 未动" >&2
      exit 1
    fi
    case "$ST" in
      healthy) HEALTH_SECS=$ELAPSED; break ;;
      missing) echo "新容器不见了（docker run 失败？），查看上面输出" >&2; exit 1 ;;
    esac
    if [ "$RUNNING" != "true" ]; then
      echo "新容器已退出（running=$RUNNING），最后 30 行日志：" >&2
      rout "docker logs --tail 30 '$NEXT_NAME' 2>&1" >&2 || true
      # 没切流量，直接清掉 next 即可，不需要动 nginx
      rsh "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true"
      echo "线上未变（仍是 $CUR_NAME:$CUR_PORT）" >&2
      exit 1
    fi
    if [ "$NOW" -ge "$DEADLINE" ]; then
      echo "等 healthy 超时（${HEALTH_TIMEOUT}s），最后 30 行日志：" >&2
      rout "docker logs --tail 30 '$NEXT_NAME' 2>&1" >&2 || true
      rsh "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true"
      echo "已删除 $NEXT_NAME；线上未变（仍是 $CUR_NAME:$CUR_PORT），nginx 配置一个字节都没动" >&2
      exit 1
    fi
    sleep 10
  done
  say "  healthy，耗时 ${HEALTH_SECS}s"
  # 宿主侧再确认一次（容器内 health 走 localhost，宿主侧走端口映射，两码事）
  # 探 /actuator/health/liveness 而不是 /actuator/health：后者要查 DB+Redis，
  # 2026-10-23 实测冷启动刚完成时首次响应 7.9~11s（JIT+预热抢 CPU），8s 超时会误判成"新端口不通"。
  # liveness 是轻量探针（容器内 healthcheck 用的也是它），再配 12 次×5s 重试兜住启动尾巴。
  HC_CODE=""
  HC_TRY=0
  for _ in $(seq 1 12); do
    HC_TRY=$((HC_TRY + 1))
    HC_CODE="$(rout "curl -s -o /dev/null -w '%{http_code}' --max-time 10 'http://127.0.0.1:$NEXT_PORT/actuator/health/liveness' || true")"
    [ "$HC_CODE" = "200" ] && break
    sleep 5
  done
  say "  宿主直连 http://127.0.0.1:$NEXT_PORT/actuator/health/liveness → $HC_CODE（第 ${HC_TRY} 次）"
  [ "$HC_CODE" = "200" ] || { rsh "docker rm -f '$NEXT_NAME' >/dev/null 2>&1 || true"; echo "新端口健康检查不是 200，放弃切换（线上未变）" >&2; exit 1; }
fi

# ===========================================================================
step "7/9 切流量：写 upstream + 首次迁移 nginx 配置 + nginx -t + reload"
if [ "$SITE_MIGRATED" = "yes" ]; then
  say "  sites-enabled/vibemusic 已含 vibemusic_backend（迁移过一次），本次只改 upstream 端口（幂等）"
else
  say "  ⚠ 首次运行：将把 sites-enabled/vibemusic 里两处 proxy_pass http://127.0.0.1:8080; 改成 http://vibemusic_backend;"
  say "    改前备份到 $BG_DIR/vibemusic.bak-$STAMP；nginx -t 不过会整体还原"
fi

set +e
remote_script <<EOS
set -euo pipefail
SITE='$SITE'
CONF='$UPSTREAM_CONF'
BK='$BG_DIR'
PORT='$NEXT_PORT'
mkdir -p "\$BK" && chmod 700 "\$BK"

# upstream 文件改前先备份（回滚靠它；首次运行时不存在，回滚函数会现写一份旧端口的）
if [ -f "\$CONF" ]; then cp -a "\$CONF" "\$BK/vibemusic-backend-upstream.conf.bak-$STAMP"; fi

# 端口先写占位符再 sed 替换：引号版 heredoc 不展开变量，这样避免嵌套 heredoc 的引号踩坑
cat > "\$CONF" <<'CONFEOF'
# 由 scripts/deploy-backend.sh 生成/维护：后端蓝绿 upstream
# 部署只改这里的 server 端口（8080/8082/8083/8084 轮换），不要手改 sites-enabled 里的 proxy_pass。
# 注意：keepalive 只有在 location 里配了 proxy_http_version 1.1 + proxy_set_header Connection "" 才真正复用；
#      没配也不影响正确性，只是退化成短连接（当前生产就是短连接，行为不变）。
upstream vibemusic_backend {
    server 127.0.0.1:__BG_PORT__;
    keepalive 16;
}
CONFEOF
sed -i "s/__BG_PORT__/\$PORT/" "\$CONF"
echo "  已写 \$CONF（upstream → 127.0.0.1:\$PORT）"

# 首次迁移只做一次：文件里已出现 vibemusic_backend 就跳过（幂等依据）
if grep -q 'vibemusic_backend' "\$SITE"; then
  echo "  \$SITE 已迁移过，跳过改写（幂等）"
else
  cp -a "\$SITE" "\$BK/vibemusic.bak-$STAMP"
  N=\$(grep -cE 'proxy_pass http://127\.0\.0\.1:808[0-4];' "\$SITE" || true)
  [ "\${N:-0}" -ge 1 ] || { echo "  !! 没找到可改写的 proxy_pass（8080-8084），配置与预期不符，中止"; exit 4; }
  sed -i -E 's#proxy_pass http://127\.0\.0\.1:808[0-4];#proxy_pass http://vibemusic_backend;#g' "\$SITE"
  echo "  首次迁移：改写 \$N 处 proxy_pass → http://vibemusic_backend（备份 \$BK/vibemusic.bak-$STAMP）"
fi

# 配置自检：nginx -t 不过就什么都别 reload
nginx -t
EOS
RC=$?
set -e
NGINX_TOUCHED=1

if [ "$APPLY" -eq 1 ]; then
  # 写配置/迁移/nginx -t 任一失败都要还原，不能裸退（此时从未 reload，线上仍跑旧配置）
  [ "$RC" -eq 0 ] || fail_rollback_config "写 upstream / 迁移 nginx 配置 / nginx -t 失败（rc=$RC，未 reload）"
  rsh "systemctl reload nginx" || fail_rollback_config "systemctl reload nginx 失败"
  SWITCHED=1
  say "  nginx 已 reload，流量切到 127.0.0.1:$NEXT_PORT"
else
  say "  [dry-run] ssh $HOST systemctl reload nginx"
fi

# ===========================================================================
step "8/9 切换后验证（不信 reload 成功，要看到流量真的到新端口）"
if is_dry; then
  say "  [dry-run] 将校验: nginx -T 里 upstream vibemusic_backend → server 127.0.0.1:$NEXT_PORT"
  say "  [dry-run] 将校验: 直连 http://127.0.0.1:$NEXT_PORT/actuator/health == 200"
  say "  [dry-run] 将校验: 经 nginx 打探针 $PROBE_URL/actuator/health == 200（502 = 到不了 upstream）"
else
  UP_EFF="$(rout "nginx -T 2>/dev/null | grep -A3 'upstream vibemusic_backend' | grep -oE '127.0.0.1:[0-9]+' | head -1" || true)"
  say "  生效配置里的 upstream: ${UP_EFF:-<读不到>}"
  [ "$UP_EFF" = "127.0.0.1:$NEXT_PORT" ] || fail "nginx -T 里的 upstream 不是 127.0.0.1:$NEXT_PORT（配置没生效）"

  CODE_NEW="$(rout "curl -s -o /dev/null -w '%{http_code}' --max-time 8 'http://127.0.0.1:$NEXT_PORT/actuator/health' || true")"
  say "  直连新端口 health → $CODE_NEW"
  [ "$CODE_NEW" = "200" ] || fail "直连 127.0.0.1:$NEXT_PORT 健康检查 $CODE_NEW"

  # 探针走 nginx 打 /actuator/health（permitAll，实测经 nginx 回 200）：这是唯一能证明
  # "流量真的改道"的运行时证据。2026-10-23 起要求严格 200（旧版打 /uploads/ 缺失文件只拿到 400，证明力弱）。
  CODE_NGX="$(rout "curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'Host: $PROBE_HOST' '$PROBE_URL/actuator/health' || true")"
  say "  经 nginx 探针 → $CODE_NGX"
  [ "$CODE_NGX" = "200" ] || fail "经 nginx 打 backend 返回 $CODE_NGX（502/504=到不了 upstream，000=连不上 nginx）"

  say "  切换验证通过（nginx → 127.0.0.1:$NEXT_PORT 通路正常）"
fi

# ===========================================================================
step "9/9 收尾：停旧容器、next 转正、清理"
if is_dry; then
  say "  [dry-run] docker rename $CUR_NAME ${CUR_NAME}-old-$STAMP"
  say "  [dry-run] docker stop -t 30 ${CUR_NAME}-old-$STAMP && docker rm ${CUR_NAME}-old-$STAMP"
  say "  [dry-run] docker rename $NEXT_NAME $CUR_NAME"
  say "  [dry-run] 删 $ENVFILE（含环境变量值，600 权限也不留在盘上）"
  say "  [dry-run] 复验：经 nginx 探针非 5xx（此时旧容器已删，只有新容器在服务）"
else
  # 顺序要点：先改名再停，避免"名字已空但旧容器还在跑"的空窗；停旧容器时新容器已在服务
  rsh "set -e
    docker rename '$CUR_NAME' '${CUR_NAME}-old-$STAMP'
    docker stop -t 30 '${CUR_NAME}-old-$STAMP' >/dev/null
    docker rm '${CUR_NAME}-old-$STAMP' >/dev/null
    docker rename '$NEXT_NAME' '$CUR_NAME'
    rm -f '$ENVFILE'
    docker inspect -f '  转正后: {{.Name}} 状态={{.State.Status}} 健康={{.State.Health.Status}}' '$CUR_NAME'" \
    || fail_final "旧容器改名/停止或 next 转正失败"

  CODE_FINAL="$(rout "curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H 'Host: $PROBE_HOST' '$PROBE_URL/actuator/health' || true")"
  say "  收尾复验（旧容器已删，只剩新容器）经 nginx 探针 → $CODE_FINAL"
  [ "$CODE_FINAL" = "200" ] || fail_final "收尾复验失败：经 nginx 返回 $CODE_FINAL"
fi

say ""
if is_dry; then say "===== DRY-RUN 推演结果（以下均为计划，未执行）====="; else say "===== 蓝绿部署完成 ====="; fi
say "镜像        : $LOCAL_IMG（latest 将指向它）"
if [ "$FROM_REGISTRY" -eq 1 ]; then
  say "镜像来源    : registry pull（云端未构建；revision=${EXPECT_REV:-<未校验>}）"
else
  say "镜像来源    : 本机构建 + save/load（云端未构建）"
fi
say "服务端口    : 127.0.0.1:$CUR_PORT → 127.0.0.1:$NEXT_PORT（下次部署自动轮换回 $CUR_PORT）"
if is_dry; then
  say "健康耗时    : （dry-run 未启动容器；真跑时这段时间旧容器一直在服务，用户无感）"
else
  say "健康耗时    : ${HEALTH_SECS}s（这段时间旧容器一直在服务，用户无感）"
fi
say "回滚镜像    : vibemusic-backend:rollback-$STAMP"
say "配置备份    : $BG_DIR/vibemusic.bak-$STAMP、$BG_DIR/vibemusic-backend-upstream.conf.bak-$STAMP"
say ""
say "回滚方式（换回上一个镜像，同样零停机）："
say "  ./scripts/deploy-backend.sh --apply --skip-build --image rollback-$STAMP"
say "仅把流量指回旧端口（旧容器已删时无意义，仅备用）："
say "  ssh $HOST \"sed -i 's#server 127.0.0.1:$NEXT_PORT;#server 127.0.0.1:$CUR_PORT;#' $UPSTREAM_CONF && nginx -t && systemctl reload nginx\""
say ""

# 配套提醒：仓库版 health-alert.sh 已改成"自动跟随 upstream conf 里的端口"（2026-10-23）。
# 所以只有「云上副本还是旧版（探活地址写死成数字端口）」时才提示——旧版切完流量会每分钟误报。
if [ -n "${HA_URL:-}" ]; then
  say "⚠ 配套提醒：云上 /opt/vibeMusic/scripts/health-alert.sh 还是旧版（探活写死 $HA_URL），"
  if is_dry; then
    say "  真跑后 backend 会搬到 $NEXT_PORT —— 旧版会每分钟误报「健康检查失败」。同步仓库新版即可（新版自动跟随端口）："
  else
    say "  backend 现在在 $NEXT_PORT —— 旧版会每分钟误报「健康检查失败」。同步仓库新版即可（新版自动跟随端口）："
  fi
  say "  scp scripts/health-alert.sh $HOST:/opt/vibeMusic/scripts/health-alert.sh"
  say "  （应急也可给 cron 加 HEALTH_PORT=$NEXT_PORT 临时覆盖）"
fi
say "注意：转正后的容器是 docker run 手工起的（端口与 compose 里的 8080 不同），"
say "      以后别再跑 docker compose up -d backend（会因容器名/端口冲突失败）；要回 compose 管理见 docs/deploy-backend.md。"
if is_dry; then
  say ""
  say "DRY-RUN 结束：以上命令均未执行，远端容器/nginx 配置一个字节都没变。确认无误后加 --apply 重跑。"
fi
