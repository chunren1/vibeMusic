#!/bin/sh
# vibeMusic 容器看门狗
#
# 作用：healthcheck 变 unhealthy 时 Docker 自己**不会**重启容器（restart 策略只管进程退出），
#       本脚本补这一层：unhealthy 且已过预热期 → docker restart。
# 调度：cron 每 5 分钟（deploy/production/crontab.txt）。
#
# 2026-11-03 加强：
#   1) 覆盖 mysql / redis——数据库"进程活着但不响应"是本机最常见的长尾故障，
#      它们本来就有 healthcheck，之前却没进这个循环。
#   2) 同一容器两次重启至少间隔 30 分钟：原实现每 5 分钟重启一次，
#      一个起不来的容器会变成 288 次/天的重启风暴，在 1.6G 机器上反而拖垮其他服务。
#   3) 日志超过 1MB 自动截断：/var/log 被撑满会连带影响 health-alert 写日志。
set -u

LOG_DIR=/var/log/vibeMusic
LOG=$LOG_DIR/vibemusic-watchdog.log
STATE=/var/run/vibemusic-watchdog.state
MIN_AGE=360          # 启动 6 分钟内不判（JVM/Node 预热期会被误判 unhealthy）
MIN_INTERVAL=1800    # 同一容器两次重启的最小间隔（秒）
MAX_LOG_BYTES=1048576
TMPFILE=""

# 日志放进 root 独占目录（0750）。
# 根因是 /var/log 在 Debian 默认 root:syslog 0775：只做 [ -L ] 检查挡不住 TOCTOU——
# 组内账号可以在"检查"和"写入"之间把文件换成符号链接。目录不可写，这条路才真正断掉。
mkdir -p "$LOG_DIR" 2>/dev/null || true
chmod 0750 "$LOG_DIR" 2>/dev/null || true
[ -d "$LOG_DIR" ] || exit 1

# 文件本身仍是链接（历史残留/人为替换）就拒绝，并让 cron 看到失败而不是静默成功
if [ -L "$LOG" ] || [ -L "$STATE" ]; then
    command -v logger >/dev/null 2>&1 && logger -t vibemusic-watchdog "日志或状态文件是符号链接，拒绝写入"
    exit 1
fi

# 异常退出（INT/TERM/被 OOM 杀掉前的 EXIT）时清掉轮转临时文件，避免残留
trap 'rm -f "$TMPFILE"' EXIT INT TERM

# 日志滚动：只留尾部 256KB，避免无限增长。
# 临时文件必须用 mktemp 生成不可预测的名字：固定名 + /var/log 组可写时，
# 同组账号可预置同名符号链接，让 root 的 ">" 写到任意文件上（CWE-59）。
if [ -f "$LOG" ]; then
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
        tmp=$(mktemp "$LOG.XXXXXX" 2>/dev/null) || tmp=""
        if [ -n "$tmp" ]; then
            TMPFILE="$tmp"
            if tail -c 262144 "$LOG" > "$tmp" 2>/dev/null; then
                chmod 0640 "$tmp" 2>/dev/null || true
                mv -f "$tmp" "$LOG" || rm -f "$tmp"
            else
                rm -f "$tmp"
            fi
            TMPFILE=""
        fi
    fi
fi

last_restart() {
    grep "^$1 " "$STATE" 2>/dev/null | tail -1 | awk '{print $2}'
}

record_restart() {
    echo "$1 $(date +%s)" >> "$STATE"
}

for name in vibemusic-backend vibemusic-musicapi vibemusic-mysql vibemusic-redis; do
    state=$(docker inspect --format '{{.State.Status}}|{{.State.Health.Status}}' "$name" 2>/dev/null) || continue

    # 只处理 running 且明确 unhealthy；没有 healthcheck 的容器这里天然跳过
    case "$state" in
        *unhealthy*) ;;
        *) continue ;;
    esac

    started=$(docker inspect --format '{{.State.StartedAt}}' "$name" 2>/dev/null) || continue
    # date 解析失败就跳过本容器：set -u 下算术操作数为空会让整个脚本退出，
    # 后面的容器与日志滚动就都不执行了（宁可少判一个，也别整轮失效）
    if ! started_epoch=$(date -d "$started" +%s 2>/dev/null); then
        echo "$(date '+%F %T') watchdog: $name 的 StartedAt 无法解析（$started），跳过本轮" >> "$LOG"
        continue
    fi
    secs=$(( $(date +%s) - started_epoch ))
    [ "$secs" -gt "$MIN_AGE" ] || continue

    now=$(date +%s)
    prev=$(last_restart "$name")
    prev=${prev:-0}
    if [ $(( now - prev )) -lt "$MIN_INTERVAL" ]; then
        echo "$(date '+%F %T') watchdog: $name unhealthy ${secs}s，但距上次重启不足 ${MIN_INTERVAL}s，跳过" >> "$LOG"
        continue
    fi

    echo "$(date '+%F %T') watchdog: $name unhealthy ${secs}s → restart" >> "$LOG"
    docker unpause "$name" 2>/dev/null || true
    if docker restart "$name" >/dev/null 2>&1; then
        record_restart "$name"
    else
        echo "$(date '+%F %T') watchdog: $name restart 失败" >> "$LOG"
    fi
done
