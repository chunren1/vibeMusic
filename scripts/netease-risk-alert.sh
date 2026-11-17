#!/usr/bin/env bash
# 网易云 -460 风控巡检
#
# 背景：2026-11-16 晚起，服务端出口 IP 被网易云风控（-460），所有 /song/url/v1 取链失败，
# 播放会静默降级到酷狗/咪咕的 128k 副本。用户只会觉得"音质突然变差"，不会知道是风控。
# 这里每 30 分钟统计网关日志里的 -460 条数，超过阈值推 Server酱。
#
# 阈值默认 15 条/30 分钟（正常应为 0）；可用 AI_RISK_THRESHOLD 覆盖。
set -uo pipefail

CONTAINER=${RISK_CONTAINER:-vibemusic-musicapi}
KEY_FILE=/etc/vibeMusic/serverchan.key
LOG=/var/log/vibemusic-netease-risk.log
STATE=/var/run/vibemusic-netease-risk.state
THRESHOLD=${RISK_THRESHOLD:-15}
REPEAT_SEC=7200

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

if [ -f "$LOG" ]; then
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "${size:-0}" -gt 1048576 ]; then
        tail -c 262144 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"
    fi
fi

count=$(docker logs --since 30m "$CONTAINER" 2>&1 | grep -c -- '-460' || true)
count=${count:-0}
log "window=30m risk460=$count threshold=$THRESHOLD"

[ "$count" -lt "$THRESHOLD" ] && exit 0

now=$(date +%s)
last=0
[ -f "$STATE" ] && read -r last < "$STATE" 2>/dev/null || true
case "${last:-0}" in ''|*[!0-9]*) last=0 ;; esac
[ $(( now - last )) -lt "$REPEAT_SEC" ] && exit 0

if [ -r "$KEY_FILE" ]; then
    key=$(cat "$KEY_FILE")
    curl -s --max-time 15 -X POST "https://sctapi.ftqq.com/${key}.send" \
        --data-urlencode "title=⚠️ 网易云 -460 风控持续（30 分钟 $count 次）" \
        --data-urlencode "desp=出口 IP 被网易云风控，取链全部失败，播放正在降级到其他平台（多为 128k）。
时间: $(date '+%F %T')
最近 30 分钟 -460 次数: $count（阈值 $THRESHOLD）
处理建议: 通常是临时风控，几小时到一天自解；持续超过 24h 考虑换出口 IP。
排查: docker logs --since 30m $CONTAINER | grep -- -460 | tail -20" >/dev/null \
        && log "已推送告警（$count 次）" || log "推送失败"
fi
printf '%s' "$now" > "$STATE" 2>/dev/null || true
