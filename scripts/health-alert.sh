#!/usr/bin/env bash
# vibeMusic 存活与资源告警（不依赖本机 Prometheus / 笔记本在线）
# cron 每分钟：后端/关键容器异常、磁盘、swap 超阈值、源 Cookie 失效 -> Server酱；恢复 -> 推送恢复。
# 去重：只在「下行/恢复」边沿推送；持续故障每 30 分钟提醒一次。
#
# 2026-10-27 收口：这是全项目**唯一**的告警通道。仓库里 27 条 Prometheus 告警规则已全部删除
# （14 条依赖已移除的后端指标导出器，13 条依赖从未部署的 node-exporter/blackbox）；
# 其中原本唯一每天在用的 4 条（网易主/备 Cookie、QQ Cookie）改写成本脚本第 5 节的 HTTP 探活。
# 恢复监控前先读 docs/2026-10-26-project-audit.md，不要只把 alert-rules.yml 复原。
set -uo pipefail

KEY_FILE=/etc/vibeMusic/serverchan.key
STATE_FILE=/var/run/vibemusic-alert.state
NOTE_STATE=/var/run/vibemusic-alert-note.state
LOG=/var/log/vibemusic-alert.log
UPSTREAM_CONF=/etc/nginx/conf.d/vibemusic-backend-upstream.conf
REPEAT_SEC=1800
DISK_MAX=85          # 根分区使用率上限 %
SWAP_MAX=30          # swap 使用率上限 %（2026-10-26 审计改：原 80% 等于永不告警，实测 521M/4G≈13%）
CONTAINERS="vibemusic-backend vibemusic-musicapi vibemusic-mysql vibemusic-redis"
MUSICAPI_PORT="${MUSICAPI_PORT:-3000}"   # musicapi 固定绑 127.0.0.1:3000（compose 内网）
COOKIE_STATUS_URL="http://127.0.0.1:${MUSICAPI_PORT}/cookie-status"

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

# 5) 源健康（Cookie 探活）——替代已下线的 4 条 Prometheus 源告警规则
# 口径（按"会不会影响播放"分档，避免永久告警疲劳）：
#   网易主备全失效 → 推送（VIP 直接掉档）
#   主失效已切备份 → 推送（备份是最后一道，需要人补 Cookie）
#   仅备份失效     → 只记日志，且只在状态变化时记一次（主仍可用，属已知待修项）
#   QQ Cookie 失效 → 推送（QQ 源直接不可用）
# 探活失败（端点非 200 / 无 python3）只记日志，不推送：musicapi 容器是否活着由第 2 项覆盖，
# 这里再报一次就是同一故障双重告警。
probe_cookie_status() {
    local json
    json=$(curl -s --max-time 5 "$COOKIE_STATUS_URL" 2>/dev/null) || { printf 'ERR'; return 0; }
    printf '%s' "$json" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin).get("data") or {}
except Exception:
    print("ERR"); sys.exit()
# 字段缺失一律按"探活无结果"处理：403 信封 / 上游改结构时绝不能误判成"全失效"去推送
if not all(k in d for k in ("netease", "neteasePrimary", "neteaseBackup", "qq")):
    print("ERR"); sys.exit()
def b(k):
    return "1" if d.get(k) is True else "0"
print(b("netease"), b("neteasePrimary"), b("neteaseBackup"), b("qq"))
' 2>/dev/null || printf 'ERR'
}

read -r NE_OK NE_PRIMARY NE_BACKUP QQ_OK <<<"$(probe_cookie_status)"
if [ -z "${QQ_OK:-}" ]; then
    log "WARN 源健康探活无结果（${COOKIE_STATUS_URL}），本轮跳过 Cookie 检查"
else
    [ "$QQ_OK" = "1" ] || PROBLEMS+=("QQ Cookie 失效（/cookie-status qq=false，QQ 源不可用）")
    if [ "$NE_OK" != "1" ]; then
        PROBLEMS+=("网易云 Cookie 主备全失效（VIP 掉档）")
    elif [ "$NE_PRIMARY" != "1" ]; then
        PROBLEMS+=("网易云主 Cookie 失效已切备份（备份再挂即全失效）")
    fi
    if [ "$NE_PRIMARY" = "1" ] && [ "$NE_BACKUP" != "1" ]; then
        if [ "$(cat "$NOTE_STATE" 2>/dev/null)" != "netease-backup-dead" ]; then
            log "NOTE 网易云备 Cookie 失效（主仍可用，按口径不推送；换新 VIP Cookie 后本条自动停止）"
            printf '%s' "netease-backup-dead" > "$NOTE_STATE" 2>/dev/null || true
        fi
    else
        rm -f "$NOTE_STATE" 2>/dev/null || true
    fi
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
全部检查项恢复正常（4 容器 running + 健康检查 200 + 磁盘 ${DISK:-?}% + swap ${SW_PCT:-0}% + 源 Cookie 正常）"
    fi
    echo "ok $NOW" > "$STATE_FILE"
fi
