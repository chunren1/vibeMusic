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

LOG=/var/log/vibemusic-watchdog.log
STATE=/var/run/vibemusic-watchdog.state
MIN_AGE=360          # 启动 6 分钟内不判（JVM/Node 预热期会被误判 unhealthy）
MIN_INTERVAL=1800    # 同一容器两次重启的最小间隔（秒）
MAX_LOG_BYTES=1048576

# 防御：本脚本以 root 运行，若日志/状态文件被替换成符号链接，绝不跟随写入
[ -L "$LOG" ] && exit 0
[ -L "$STATE" ] && exit 0

# 日志滚动：只留尾部 256KB，避免无限增长。
# 临时文件必须用 mktemp 生成不可预测的名字：固定名 + /var/log 组可写时，
# 同组账号可预置同名符号链接，让 root 的 ">" 写到任意文件上（CWE-59）。
if [ -f "$LOG" ]; then
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
        tmp=$(mktemp "$LOG.XXXXXX" 2>/dev/null) || tmp=""
        if [ -n "$tmp" ]; then
            if tail -c 262144 "$LOG" > "$tmp" 2>/dev/null; then
                chmod 0640 "$tmp" 2>/dev/null || true
                mv -f "$tmp" "$LOG"
            else
                rm -f "$tmp"
            fi
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
    started_epoch=$(date -d "$started" +%s 2>/dev/null) || continue
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
