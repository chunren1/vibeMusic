#!/usr/bin/env bash
# 边缘证书到期巡检（2026-11-06 建立，每天执行一次）
#
# 背景：全站 TLS 在 Cloudflare 边缘终止（Universal SSL，GTS/WE1，90 天有效期，
#       到期前 30 天自动续）。源站没有 443、也没有 certbot 证书，所以「证书到期」
#       这个故障点只存在于 Cloudflare 侧 —— 自动续期很稳，但不是不可能失败
#       （DCV 失败、CAA 误配、套餐/域名状态异常都会导致续期不启动）。
#       本脚本是最外一层兜底：任何一张证书进入 14 天窗口就推 Server酱。
#
# 口径：
#   · 只查有公网入口的域名（monitor 为按需演示域名，同样要查）。
#   · `openssl x509 -checkend` 返回非 0 = 即将到期或链路不可用，两种都值得人看一眼。
#   · 每天一次即可：CF 续期从到期前 30 天开始尝试，14 天窗口留了充足人工介入时间。
set -uo pipefail

KEY_FILE=/etc/vibeMusic/serverchan.key
LOG=/var/log/vibemusic-cert.log
HOSTS="cyk666.top www.cyk666.top vibe.cyk666.top monitor.cyk666.top"
WARN_DAYS=14
TIMEOUT=10

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

# 日志轮转（与 health-alert.sh 同口径）
rotate_log() {
    [ -f "$LOG" ] || return 0
    local size
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    case "$size" in ''|*[!0-9]*) return 0 ;; esac
    [ "$size" -gt 262144 ] || return 0
    local tmp
    tmp=$(mktemp "$LOG.XXXXXX" 2>/dev/null) || return 0
    if tail -c 65536 "$LOG" > "$tmp" 2>/dev/null; then
        chmod 0640 "$tmp" 2>/dev/null || true
        mv -f "$tmp" "$LOG" 2>/dev/null || rm -f "$tmp"
    else
        rm -f "$tmp"
    fi
}

send() {
    local title="$1" body="$2"
    [ -r "$KEY_FILE" ] || { log "WARN key 文件不可读，跳过推送"; return 1; }
    local key; key=$(cat "$KEY_FILE")
    curl -s --max-time 15 -X POST "https://sctapi.ftqq.com/${key}.send" \
        --data-urlencode "title=${title}" --data-urlencode "desp=${body}" >/dev/null \
        && { log "推送成功: ${title}"; return 0; } || { log "推送失败: ${title}"; return 1; }
}

rotate_log

BAD=()
DETAIL=""
for h in $HOSTS; do
    chain=$(timeout "$TIMEOUT" openssl s_client -connect "${h}:443" -servername "$h" </dev/null 2>/dev/null)
    if [ -z "$chain" ]; then
        BAD+=("$h 无法建立 TLS 连接")
        DETAIL="${DETAIL}· ${h}: 连接失败\n"
        continue
    fi
    if printf '%s' "$chain" | openssl x509 -noout -checkend $(( WARN_DAYS * 86400 )) >/dev/null 2>&1; then
        end=$(printf '%s' "$chain" | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
        log "OK ${h} 证书有效（到期 ${end}）"
    else
        end=$(printf '%s' "$chain" | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
        issuer=$(printf '%s' "$chain" | openssl x509 -noout -issuer 2>/dev/null | sed 's/^issuer=//')
        BAD+=("$h 证书 ${WARN_DAYS} 天内到期或校验失败")
        DETAIL="${DETAIL}· ${h}: 到期 ${end:-?}，签发者 ${issuer:-?}\n"
    fi
done

if [ ${#BAD[@]} -gt 0 ]; then
    log "ALERT ${BAD[*]}"
    send "🔴 证书到期告警（${#BAD[@]} 个域名）" "主机: 101.132.167.178
时间: $(date '+%F %T')
${DETAIL}
处理：Cloudflare → SSL/TLS → Edge Certificates 查看 Universal SSL 状态与 DCV；
      若已停用/未签发，重新启用 Universal SSL（Free 计划即可）。"
else
    log "全部域名证书正常（窗口 ${WARN_DAYS} 天）"
fi
