#!/usr/bin/env bash
# vibeMusic 存活与资源告警（不依赖本机 Prometheus / 笔记本在线）
# cron 每分钟：后端/关键容器异常、磁盘、swap 超阈值 -> Server酱；恢复 -> 推送恢复。
# 去重：只在「下行/恢复」边沿推送；持续故障每 30 分钟提醒一次。
set -uo pipefail

KEY_FILE=/etc/vibeMusic/serverchan.key
STATE_FILE=/var/run/vibemusic-alert.state
LOG=/var/log/vibemusic-alert.log
UPSTREAM_CONF=/etc/nginx/conf.d/vibemusic-backend-upstream.conf
REPEAT_SEC=1800
DISK_MAX=85          # 根分区使用率上限 %
SWAP_MAX=80          # swap 使用率上限 %
CONTAINERS="vibemusic-backend vibemusic-musicapi vibemusic-mysql vibemusic-redis"

# ---------------------------------------------------------------------------
# 后端探活端口（2026-10-23 修：蓝绿部署后端口会在 8080/8082/8083/8084 之间轮换，
# 写死 8080 会在每次切流量后每分钟误报「健康检查失败」）。解析优先级（高 → 低）：
#   1) 环境变量 HEALTH_PORT —— 临时排查/手工指定时用；
#   2) UPSTREAM_CONF 里的 `server 127.0.0.1:<port>;` —— 蓝绿脚本切流量改的就是这一行，
#      是"当前谁在服务"的唯一事实来源（nginx 指哪就打哪）；
#   3) 回退 8080 —— 蓝绿首次运行前的现状（那时还没有 upstream 文件）。
# 解析失败（文件不存在 / 没匹配到 server 行 / 端口非数字）一律**静默回退**，绝不中断巡检。
resolve_backend_port() {
    local port=""
    if [ -n "${HEALTH_PORT:-}" ]; then
        case "$HEALTH_PORT" in
            *[!0-9]*) ;;                 # 非数字视为非法，继续往下解析
            *) port="$HEALTH_PORT" ;;
        esac
        if [ -n "$port" ]; then printf '%s' "$port"; return 0; fi
    fi
    if [ -r "$UPSTREAM_CONF" ]; then
        port="$(sed -n 's/^[[:space:]]*server[[:space:]][[:space:]]*127\.0\.0\.1:\([0-9][0-9]*\)[[:space:]]*;.*$/\1/p' "$UPSTREAM_CONF" 2>/dev/null | head -1)"
        case "$port" in
            ''|*[!0-9]*) port="" ;;
        esac
        if [ -n "$port" ]; then printf '%s' "$port"; return 0; fi
    fi
    printf '8080'
}

BACKEND_PORT="$(resolve_backend_port)"
HEALTH_URL="http://127.0.0.1:${BACKEND_PORT}/actuator/health"

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

send() {
    local title="$1" body="$2"
    [ -r "$KEY_FILE" ] || { log "WARN key 文件不可读，跳过推送"; return 1; }
    local key; key=$(cat "$KEY_FILE")
    curl -s --max-time 15 -X POST "https://sctapi.ftqq.com/${key}.send" \
        --data-urlencode "title=${title}" --data-urlencode "desp=${body}" >/dev/null \
        && { log "推送成功: ${title}"; return 0; } || { log "推送失败: ${title}"; return 1; }
}

PROBLEMS=()

# 1) 后端存活
docker inspect -f '{{.State.Running}}' vibemusic-backend 2>/dev/null | grep -q true \
    || PROBLEMS+=("backend 容器未运行")
if docker inspect -f '{{.State.Running}}' vibemusic-backend 2>/dev/null | grep -q true; then
    curl -sf --max-time 5 -o /dev/null "$HEALTH_URL" || PROBLEMS+=("backend 健康检查失败")
fi

# 2) 关键容器
for c in $CONTAINERS; do
    st=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null || echo missing)
    [ "$st" = "running" ] || PROBLEMS+=("$c 状态=$st")
done

# 3) 磁盘
DISK=$(df --output=pcent / 2>/dev/null | tr -dc '0-9')
[ -n "${DISK:-}" ] && [ "$DISK" -ge "$DISK_MAX" ] && PROBLEMS+=("根分区使用率 ${DISK}% ≥ ${DISK_MAX}%")

# 4) swap（1.6G 机器的关键指标：持续换页会让 JVM 冷启动从 40s 变 3 分钟）
read -r SW_TOTAL SW_USED <<<"$(awk '/^SwapTotal:/{t=$2}/^SwapFree:/{f=$2}END{print t, t-f}' /proc/meminfo)"
if [ -n "${SW_TOTAL:-}" ] && [ "$SW_TOTAL" -gt 0 ]; then
    SW_PCT=$(( SW_USED * 100 / SW_TOTAL ))
    [ "$SW_PCT" -ge "$SWAP_MAX" ] && PROBLEMS+=("swap 使用 ${SW_PCT}% ≥ ${SWAP_MAX}%（内存吃紧）")
fi

NOW=$(date +%s); LAST_STATE="ok"; LAST_TS=0
[ -f "$STATE_FILE" ] && read -r LAST_STATE LAST_TS < "$STATE_FILE" || true

if [ ${#PROBLEMS[@]} -gt 0 ]; then
    DETAIL=$(printf '· %s\n' "${PROBLEMS[@]}")
    if [ "$LAST_STATE" != "down" ] || [ $((NOW - LAST_TS)) -ge $REPEAT_SEC ]; then
        send "🔴 vibeMusic 异常（${#PROBLEMS[@]} 项）" "主机: 101.132.167.178
时间: $(date '+%F %T')
${DETAIL}
内存: $(free -h | awk '/^Mem:/{print "used "$3" / "$2", avail "$7}')
Swap: ${SW_USED}/${SW_TOTAL} KB (${SW_PCT:-?}%)"
    fi
    echo "down $NOW" > "$STATE_FILE"
    log "异常: ${PROBLEMS[*]}"
else
    if [ "$LAST_STATE" = "down" ]; then
        send "🟢 vibeMusic 已恢复" "主机: 101.132.167.178
时间: $(date '+%F %T')
全部检查项恢复正常（4 容器 running + 健康检查 200 + 磁盘 ${DISK:-?}% + swap ${SW_PCT:-0}%）"
    fi
    echo "ok $NOW" > "$STATE_FILE"
fi
