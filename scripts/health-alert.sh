#!/usr/bin/env bash
# vibeMusic 存活与资源告警（不依赖本机 Prometheus / 笔记本在线）
# cron 每分钟：后端/关键容器异常、磁盘、swap 超阈值、源 Cookie 失效 -> Server酱；恢复 -> 推送恢复。
# 去重：连续 2 轮确认才推 🔴；恢复要「推过异常 且 持续 ≥5 分钟」才推 🟢；持续故障每 30 分钟提醒一次。
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
RESTART_STATE=/var/run/vibemusic-alert-restarts.state   # 每容器重启计数窗口基线

# docker CLI 统一加超时。原实现所有 docker 调用都是无上限的：dockerd 一旦卡住，
# 本轮就永远不返回，而 cron 每分钟还会再起一轮 —— 在 1.6G 的机器上，
# 这是能最快把内存吃干的方式（watchdog 已于 2026-11-03 加过同样的保护，这里补上）。
DK_TIMEOUT=10
dk() {
    if command -v timeout >/dev/null 2>&1; then
        timeout "$DK_TIMEOUT" docker "$@"
    else
        docker "$@"
    fi
}

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

# 日志轮转：与 watchdog 同口径（超过 1MB 只留尾部 256KB）。
# 原实现只追加不轮转，每分钟一轮、每轮至少一行，一年 20MB+；这个文件落在 /var/log，
# 而本脚本自己就会报"根分区使用率 ≥85%"——把自己的日志撑成告警原因是最难查的一种。
rotate_log() {
    [ -f "$LOG" ] || return 0
    local size
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    case "$size" in ''|*[!0-9]*) return 0 ;; esac
    [ "$size" -gt 1048576 ] || return 0
    local tmp
    tmp=$(mktemp "$LOG.XXXXXX" 2>/dev/null) || return 0
    if tail -c 262144 "$LOG" > "$tmp" 2>/dev/null; then
        chmod 0640 "$tmp" 2>/dev/null || true
        mv -f "$tmp" "$LOG" 2>/dev/null || rm -f "$tmp"
    else
        rm -f "$tmp"
    fi
}

# 并发互斥：cron 每分钟一轮，而一轮里 docker / curl / python 的耗时都不受控。
# 上一轮没结束时又起一轮，会出现两轮同时读状态文件、同时推送（重复告警），
# 以及两轮同时重写重启计数基线（计数丢一段）。拿不到锁就退出：本轮由持锁者代表。
if command -v flock >/dev/null 2>&1; then
    LOCK_FILE=/var/run/vibemusic-alert.lock
    if : >> "$LOCK_FILE" 2>/dev/null; then
        chmod 0600 "$LOCK_FILE" 2>/dev/null || true
        exec 9>>"$LOCK_FILE"
        flock -n 9 || exit 0
    fi
fi

# 轮转放在取锁之后：两轮同时抓日志会让后一轮读到已截断的文件头
rotate_log

send() {
    local title="$1" body="$2"
    [ -r "$KEY_FILE" ] || { log "WARN key 文件不可读，跳过推送"; return 1; }
    local key; key=$(cat "$KEY_FILE")
    curl -s --max-time 15 -X POST "https://sctapi.ftqq.com/${key}.send" \
        --data-urlencode "title=${title}" --data-urlencode "desp=${body}" >/dev/null \
        && { log "推送成功: ${title}"; return 0; } || { log "推送失败: ${title}"; return 1; }
}

PROBLEMS=()
RESTARTS_NEW=()

# 1) 后端存活
#    容器"在不在跑"由第 2 项统一报告（同一容器原来被 inspect 三次、且"未运行"会被报两遍），
#    这里只做 HTTP 探活。
if dk inspect -f '{{.State.Running}}' vibemusic-backend 2>/dev/null | grep -q true; then
    curl -sf --max-time 5 -o /dev/null "$HEALTH_URL" || PROBLEMS+=("backend 健康检查失败")
fi

# 2) 关键容器：运行状态 + 健康状态 + OOM + 崩溃循环
#    restart 策略只在"进程退出"时生效；容器 running 但 healthcheck=unhealthy 时
#    Docker 不会做任何事，watchdog 负责重启、本脚本负责把这件事说出来。
NOW_EPOCH=$(date +%s)
for c in $CONTAINERS; do
    info=$(dk inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.RestartCount}}|{{.State.OOMKilled}}' "$c" 2>/dev/null)
    if [ -z "$info" ]; then
        PROBLEMS+=("$c 不存在或无法 inspect")
        continue
    fi
    IFS='|' read -r st health restarts oom <<<"$info"
    [ "$st" = "running" ] || PROBLEMS+=("$c 状态=$st")
    [ "$health" = "unhealthy" ] && PROBLEMS+=("$c 健康检查 unhealthy")
    # OOMKilled 在容器重新启动时会被清零，running 状态下它几乎总是 false；
    # 只在非 running 时读它，避免把 stale 值当成新故障。
    [ "$oom" = "true" ] && [ "$st" != "running" ] && PROBLEMS+=("$c 上次退出为 OOM（容器内存超限）")
    case "$restarts" in
        ''|*[!0-9]*) restarts="" ;;
    esac
    if [ -n "$restarts" ]; then
        # 崩溃循环判据不能用「累计 RestartCount + 一小时内」：
        # watchdog 自己重启一次、或运维手动 `docker restart`，都会让健康容器重新落进
        # "一小时内重启 ≥3 次"，而真正一分钟崩三次的容器反而可能因为窗口滑动被漏掉。
        # 改为记录窗口基线（since_epoch, base_count），只统计**窗口内新增**的重启次数。
        base_line=$(awk -v c="$c" '$1==c{print $2" "$3}' "$RESTART_STATE" 2>/dev/null | tail -1)
        base_epoch=${base_line%% *}
        base_count=${base_line##* }
        case "$base_epoch" in ''|*[!0-9]*) base_epoch=$NOW_EPOCH ;; esac
        case "$base_count" in ''|*[!0-9]*) base_count=$restarts ;; esac
        # 容器被重建（compose up 重建会归零 RestartCount）或窗口已过一小时 → 重设基线
        if [ "$restarts" -lt "$base_count" ] || [ $(( NOW_EPOCH - base_epoch )) -ge 3600 ]; then
            base_epoch=$NOW_EPOCH
            base_count=$restarts
        fi
        delta=$(( restarts - base_count ))
        [ "$delta" -ge 3 ] && PROBLEMS+=("$c 一小时内新增重启 ${delta} 次（疑似崩溃循环）")
        RESTARTS_NEW+=("$c $base_epoch $base_count")
    fi
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
    local raw json http
    raw=$(curl -s --max-time 5 -w '\n%{http_code}' "$COOKIE_STATUS_URL" 2>/dev/null) || { printf 'ERR'; return 0; }
    http=${raw##*$'\n'}
    json=${raw%$'\n'*}
    # 403 = 端点要求管理令牌。必须与"探活无结果"分开：按无结果处理会让 Cookie 监控
    # 永久失聪——每分钟只记一行 WARN、永不告警，直到有人想起"最近怎么没收到过 Cookie 告警"。
    if [ "$http" = "403" ]; then printf 'DENIED'; return 0; fi
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
if [ "${QQ_OK:-}" = "DENIED" ]; then
    # 探活被 403 拒绝：这是**监控链路**故障，不是 Cookie 故障，必须响亮地报出来
    PROBLEMS+=("源健康探活被 403 拒绝——Cookie 告警链路已失效，检查 /cookie-status 鉴权与 MUSICAPI_ADMIN_TOKEN")
elif [ -z "${QQ_OK:-}" ]; then
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

NOW=$(date +%s); LAST_STATE="ok"; LAST_TS=0; LAST_PUSHED=0; LAST_PUSH_TS=0; LEGACY_STATE=0
[ -f "$STATE_FILE" ] && read -r LAST_STATE LAST_TS LAST_PUSHED LAST_PUSH_TS < "$STATE_FILE" || true
case "$LAST_STATE" in down|ok) ;; *) LAST_STATE="ok" ;; esac
case "$LAST_TS" in ''|*[!0-9]*) LAST_TS=0 ;; esac
case "$LAST_PUSH_TS" in ''|*[!0-9]*) LAST_PUSH_TS=0 ;; esac
if [ "$LAST_STATE" = "down" ] && [ -z "${LAST_PUSHED:-}" ]; then
    # 旧格式 down <ts>：那个 ts 以前**每轮都被刷新**，既不代表异常起点、也看不出有没有推送过。
    # 兼容口径 = 视为已推送、起点未知（下面会从本轮重新计时，宁可晚推恢复也不立刻推）。
    LEGACY_STATE=1
    LAST_PUSHED=1
fi
case "$LAST_PUSHED" in 0|1) ;; *) LAST_PUSHED=0 ;; esac

# 连续异常轮数（cron 每分钟一轮）：首轮异常只记日志，连续第 STREAK_CONFIRM 轮才推 🔴。
# 单独一个文件：状态文件要保持「down <start> <pushed>」的形状，计数器脏了也不影响它。
STATE_DIR=$(dirname "$STATE_FILE")
STREAK_FILE="$STATE_DIR/vibemusic-alert-streak"
STREAK_CONFIRM=2
RECOVERY_MIN_DOWN_SEC=300

STREAK=0
if [ -f "$STREAK_FILE" ]; then
    read -r STREAK < "$STREAK_FILE" || true
fi
case "$STREAK" in ''|*[!0-9]*) STREAK=0 ;; esac

# 落盘本轮的窗口基线：容器集合固定，直接整体覆盖，避免旧条目无限堆积。
if [ ${#RESTARTS_NEW[@]} -gt 0 ]; then
    printf '%s\n' "${RESTARTS_NEW[@]}" > "$RESTART_STATE" 2>/dev/null || true
fi

if [ ${#PROBLEMS[@]} -gt 0 ]; then
    DETAIL=$(printf '· %s\n' "${PROBLEMS[@]}")
    STREAK=$(( STREAK + 1 ))
    DOWN_START=$NOW
    PUSHED=0
    LAST_PUSH=0
    if [ "$LAST_STATE" = "down" ]; then
        DOWN_START=$LAST_TS
        PUSHED=$LAST_PUSHED
        LAST_PUSH=$LAST_PUSH_TS
        if [ "$LEGACY_STATE" = "1" ] || [ "$DOWN_START" -le 0 ]; then
            # 起点不可信：从本轮重新计时（只影响恢复门槛，不影响本轮是否推 🔴）
            DOWN_START=$NOW
            LEGACY_STATE=1
        fi
    fi

    SHOULD_PUSH=0
    if [ "$PUSHED" = "1" ]; then
        # 重复提醒：距上次推送 ≥ REPEAT_SEC（首次推送后按 30 分钟节拍，不每分钟重推）
        ANCHOR=$LAST_PUSH
        if [ "$ANCHOR" -le 0 ]; then ANCHOR=$DOWN_START; fi
        if [ $(( NOW - ANCHOR )) -ge $REPEAT_SEC ]; then SHOULD_PUSH=1; fi
    elif [ "$STREAK" -ge "$STREAK_CONFIRM" ]; then
        SHOULD_PUSH=1
    fi

    if [ "$SHOULD_PUSH" = "1" ]; then
        if send "🔴 vibeMusic 异常（${#PROBLEMS[@]} 项）" "主机: 101.132.167.178
时间: $(date '+%F %T')
${DETAIL}
内存: $(free -h | awk '/^Mem:/{print "used "$3" / "$2", avail "$7}')
Swap: ${SW_USED}/${SW_TOTAL} KB (${SW_PCT:-?}%)"; then
            PUSHED=1
            LAST_PUSH=$NOW
        fi
    else
        log "INFO 异常第 ${STREAK} 轮（连续 ${STREAK_CONFIRM} 轮才推送，本轮只记日志）: ${PROBLEMS[*]}"
    fi
    printf 'down %s %s %s\n' "$DOWN_START" "$PUSHED" "$LAST_PUSH" > "$STATE_FILE"
    printf '%s\n' "$STREAK" > "$STREAK_FILE" 2>/dev/null || true
    log "异常: ${PROBLEMS[*]}"
else
    if [ "$LAST_STATE" = "down" ]; then
        DURATION_UNKNOWN=0
        if [ "$LEGACY_STATE" = "1" ] || [ "$LAST_TS" -le 0 ]; then DURATION_UNKNOWN=1; fi
        if [ "$LAST_PUSHED" != "1" ]; then
            log "INFO 恢复（本轮异常从未推送过告警，按去抖口径不推恢复）"
        elif [ "$DURATION_UNKNOWN" = "1" ] || [ $(( NOW - LAST_TS )) -lt $RECOVERY_MIN_DOWN_SEC ]; then
            log "INFO 恢复（异常持续时间未知或 < $((RECOVERY_MIN_DOWN_SEC / 60)) 分钟，按去抖口径不推恢复）"
        else
            send "🟢 vibeMusic 已恢复" "主机: 101.132.167.178
时间: $(date '+%F %T')
全部检查项恢复正常（4 容器 running + 健康检查 200 + 磁盘 ${DISK:-?}% + swap ${SW_PCT:-0}% + 源 Cookie 正常）"
        fi
    fi
    echo "ok $NOW" > "$STATE_FILE"
    rm -f "$STREAK_FILE" 2>/dev/null || true
fi
