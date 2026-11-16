#!/usr/bin/env bash
# 后端 AI token 用量巡检
#
# 背景：2026-11-15 有人拿走了 AI key，从 17:00 一直刷到次日早上把余额刷光，
# 而系统里没有任何"用了多少"的记录——只能等控制台账单出来才发现。
#
# 做法：每 30 分钟统计后端容器日志里的 [AI 用量] 行（AssistantClient 每次调用都会打
# total=N），超过窗口阈值就推 Server酱；无论是否超阈值都落一行到日志，便于事后回溯。
#
# 阈值默认 50000 token / 30 分钟。正常一条消息约 1.4k token，50k 相当于每半小时
# 三十多次对话；异常刷量会瞬间越过这条线。可用环境变量 AI_WINDOW_TOKEN_LIMIT 覆盖。
#
# 调度：cron 每 30 分钟。依赖：docker CLI、/etc/vibeMusic/serverchan.key。
set -uo pipefail

CONTAINER="${AI_USAGE_CONTAINER:-vibemusic-backend}"
KEY_FILE=/etc/vibeMusic/serverchan.key
LOG=/var/log/vibemusic-ai-usage.log
STATE=/var/run/vibemusic-ai-usage.state
LIMIT="${AI_WINDOW_TOKEN_LIMIT:-50000}"
WINDOW_MIN="${AI_WINDOW_MINUTES:-30}"
REPEAT_SEC=1800

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

# 日志轮转：超过 1MB 留尾部 256KB
if [ -f "$LOG" ]; then
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "${size:-0}" -gt 1048576 ]; then
        tail -c 262144 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"
    fi
fi

tmp=$(mktemp /tmp/ai-usage.XXXXXX) || exit 0
trap 'rm -f "$tmp"' EXIT
timeout 15 docker logs --since "${WINDOW_MIN}m" "$CONTAINER" > "$tmp" 2>&1 || true

tokens=$(grep -o 'total=[0-9]*' "$tmp" | cut -d= -f2 | awk '{s+=$1} END{printf "%d", s+0}')
calls=$(grep -c '\[AI 用量\]' "$tmp" || true)
log "window=${WINDOW_MIN}m calls=${calls:-0} tokens=${tokens:-0} limit=$LIMIT"

[ "${tokens:-0}" -le "$LIMIT" ] && exit 0

# 去抖：30 分钟内只推一次
now=$(date +%s)
last=0
[ -f "$STATE" ] && read -r last < "$STATE" 2>/dev/null || true
case "${last:-0}" in ''|*[!0-9]*) last=0 ;; esac
[ $(( now - last )) -lt "$REPEAT_SEC" ] && exit 0

if [ -r "$KEY_FILE" ]; then
    key=$(cat "$KEY_FILE")
    curl -s --max-time 15 -X POST "https://sctapi.ftqq.com/${key}.send" \
        --data-urlencode "title=⚠️ vibeMusic AI 用量异常（${WINDOW_MIN} 分钟 ${tokens} token）" \
        --data-urlencode "desp=主机: $(hostname)
时间: $(date '+%F %T')
最近 ${WINDOW_MIN} 分钟: ${calls} 次调用 / ${tokens} token（阈值 ${LIMIT}）
排查: docker logs --since 1h ${CONTAINER} | grep AI | tail -30" >/dev/null \
        && log "已推送告警（${tokens} token）" || log "推送失败"
fi
printf '%s' "$now" > "$STATE" 2>/dev/null || true
