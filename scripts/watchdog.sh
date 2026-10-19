#!/bin/sh
# vibeMusic 容器看门狗（2026-09-24 补：unhealthy 不会自愈的兜底）
# 逻辑：backend/musicapi 若 healthcheck=unhealthy 且已运行超过 6 分钟（排除启动预热期），
#       自动重启并记录。每 5 分钟由 cron 调用。重启后仍 unhealthy 会再次重启（有界循环，优于一直死着）。
set -u
LOG=/var/log/vibemusic-watchdog.log
for name in vibemusic-backend vibemusic-musicapi; do
  state=$(docker inspect --format '{{.State.Status}}|{{.State.Health.Status}}' "$name" 2>/dev/null) || continue
  case "$state" in
    *unhealthy*)
      started=$(docker inspect --format '{{.State.StartedAt}}' "$name")
      secs=$(( $(date +%s) - $(date -d "$started" +%s) ))
      if [ "$secs" -gt 360 ]; then
        echo "$(date '+%F %T') watchdog: $name unhealthy ${secs}s → restart" >> "$LOG"
        docker unpause "$name" 2>/dev/null || true
        docker restart "$name" >/dev/null
      fi
      ;;
  esac
done
