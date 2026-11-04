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
#   3) 日志超过 1MB 自动截断；日志写入改为"打开一次 fd、之后只写 fd"，
#      路径在运行期被替换也不会影响已打开的写入。
set -u

# /var/lib 的父目录本身不是组可写的，攻击者无法把整个目录换掉；
# 对比 /var/log（Debian 默认 root:syslog 0775）从根上少了"替换目录/文件"这一层。
LOG_DIR=/var/lib/vibeMusic
LOG=$LOG_DIR/vibemusic-watchdog.log
STATE=/var/run/vibemusic-watchdog.state
MIN_AGE=360          # 启动 6 分钟内不判（JVM/Node 预热期会被误判 unhealthy）
MIN_INTERVAL=1800    # 同一容器两次重启的最小间隔（秒）
MAX_LOG_BYTES=1048576
TMPFILE=""

log_fail() {
    if command -v logger >/dev/null 2>&1; then
        logger -t vibemusic-watchdog "$*"
    else
        printf '%s\n' "$*" >&2
    fi
}

log() {
    printf '%s %s\n' "$(date '+%F %T')" "$*" >&3
}

# docker 调用统一加超时：dockerd 卡死时原实现会让整轮挂住，
# 而本轮持着 flock，下一轮只会 exit 0 —— 看门狗就此"静默停摆"。
dk() {
    if command -v timeout >/dev/null 2>&1; then
        timeout 30 docker "$@"
    else
        docker "$@"
    fi
}

# 重启单独放宽到 120s：MySQL 启动 + InnoDB 恢复经常超过 30s，
# 按 inspect 的 30s 截断会把"正在正常重启"误判成失败。
dk_restart() {
    if command -v timeout >/dev/null 2>&1; then
        timeout 120 docker restart "$1" >/dev/null 2>&1
    else
        docker restart "$1" >/dev/null 2>&1
    fi
}

mkdir -p "$LOG_DIR" 2>/dev/null || { log_fail "无法创建日志目录 $LOG_DIR"; exit 1; }

# 先判链接、再 chmod：mkdir -p 遇到"指向目录的符号链接"同样返回 0，
# 若先 chmod，权限会跟着链接落到攻击者指定的目标上。
if [ -L "$LOG_DIR" ]; then
    log_fail "日志目录 $LOG_DIR 是符号链接，拒绝写入"
    exit 1
fi

chmod 0750 "$LOG_DIR" 2>/dev/null || true

# 复查真实属性：属主必须是 root、组/其他不可写（chmod 失败时不会静默继续）
dir_meta=$(stat -c '%U %a' "$LOG_DIR" 2>/dev/null) || dir_meta=""
if [ "$dir_meta" != "root 750" ]; then
    log_fail "日志目录 $LOG_DIR 属性异常（当前 ${dir_meta:-unknown}），拒绝写入"
    exit 1
fi

if [ -L "$LOG" ] || [ -L "$STATE" ]; then
    log_fail "日志或状态文件是符号链接，拒绝写入"
    exit 1
fi

# 并发互斥：轮转、重启判定都是"读-改-写"，两轮重叠会互相截断并丢日志，
# 也会让 MIN_INTERVAL 失效（两边都读到旧时间戳，各重启一次）。
#
# 锁文件必须放在 $LOG_DIR（已校验 root:root 0750），不能放 /var/run：
# flock 只要求持有"可读 fd"即可取 LOCK_EX，0644 的锁文件任何本地账号都能
# `flock -n 锁 -c 'sleep 86400'` 把看门狗永久挡在门外，且失败路径不写日志。
if command -v flock >/dev/null 2>&1; then
    LOCK=$LOG_DIR/watchdog.lock
    # 同 exec：探测失败要用子 shell 承接，否则 dash 直接退出、兜底不可达
    if ! ( : >> "$LOCK" ) 2>/dev/null; then
        log_fail "无法创建锁文件 $LOCK"
        exit 1
    fi
    chmod 0600 "$LOCK" 2>/dev/null || true
    exec 9>>"$LOCK"
    flock -n 9 || exit 0
fi

trap 'rm -f "$TMPFILE"' EXIT
trap 'rm -f "$TMPFILE"; exit 130' INT TERM

# 日志滚动：只留尾部 256KB。临时文件用 mktemp（不可预测、O_EXCL、同目录同文件系统）。
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

# 打开一次拿到 fd，后续只往 fd 写：
# 即使 $LOG 在运行期被替换成符号链接，写入也落在打开时的 inode 上，
# "检查—写入"之间的 TOCTOU 窗口对追加路径不再成立。
#
# 注意：dash 下 `exec 3>>文件` 失败属于特殊内建的重定向错误，会**直接退出**，
# `|| { ...; }` 这个兜底根本不会执行（bash 才会）。所以先用一次普通重定向探测可达性。
# `:` 与 exec 一样是特殊内建，重定向失败会让 shell 直接退出；放进子 shell 才能被 if 捕获。
if ! ( : >> "$LOG" ) 2>/dev/null; then
    log_fail "无法写入日志 $LOG"
    exit 1
fi
chmod 0640 "$LOG" 2>/dev/null || true   # 首次创建受 umask 影响是 0644，统一收紧
exec 3>>"$LOG"

last_restart() {
    grep "^$1 " "$STATE" 2>/dev/null | tail -1 | awk '{print $2}'
}

record_restart() {
    printf '%s %s\n' "$1" "$(date +%s)" >> "$STATE"
}

for name in vibemusic-backend vibemusic-musicapi vibemusic-mysql vibemusic-redis; do
    state=$(dk inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null) || continue

    st=${state%%|*}
    health=${state#*|}

    # 三类状态需要区分对待：
    #   1) running + unhealthy —— healthcheck 判死但进程还在，Docker 的 restart 策略不管这种
    #      （它只管"进程退出"），重启是本脚本的主职责；没有 healthcheck 的容器天然跳过。
    #   2) exited / dead —— 进程已不在。这是 2026-11-03 之前漏掉的一类：`docker restart`
    #      在 stop 阶段成功、start 阶段超时（120s）时，容器就停在 exited，而原实现只匹配
    #      unhealthy，于是数据库一直躺着，直到人看到半小时后的告警。
    #   3) restarting —— Docker 自己正在按策略退避重试，此时再 `docker restart` 只会打断
    #      它的退避并重置计数，所以只记录、不介入。
    case "$st" in
        running)
            [ "$health" = "unhealthy" ] || continue
            ;;
        exited|dead)
            # 只接管"本该活着"的容器。restart policy = no 说明它是一次性的或人工管理的
            # （migrate 容器、临时排查容器），强行拉起只会覆盖人的意图。
            policy=$(dk inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$name" 2>/dev/null) || policy=""
            case "$policy" in
                no|'')
                    log "watchdog: $name 状态=$st（重启策略=${policy:-unknown}），不自动拉起"
                    continue
                    ;;
            esac
            ;;
        restarting)
            log "watchdog: $name 处于 restarting（Docker 正在按重启策略重试），本轮不介入"
            continue
            ;;
        *) continue ;;   # created / paused / removing：状态未定或人为操作，不猜
    esac

    started=$(dk inspect --format '{{.State.StartedAt}}' "$name" 2>/dev/null) || continue
    # date 解析失败就跳过本容器：set -u 下算术操作数为空会让整个脚本退出，
    # 后面的容器都不再检查（宁可少判一个，也别整轮失效）
    if ! started_epoch=$(date -d "$started" +%s 2>/dev/null); then
        log "watchdog: $name 的 StartedAt 无法解析（$started），跳过本轮"
        continue
    fi
    secs=$(( $(date +%s) - started_epoch ))
    [ "$secs" -ge 0 ] || secs=0            # 时钟回跳时算出的负秒数不参与判断
    [ "$secs" -gt "$MIN_AGE" ] || continue

    now=$(date +%s)
    prev=$(last_restart "$name")
    # 状态文件被写坏（非数字/空行）时，dash 的算术展开会直接退出整个脚本，
    # 后面的容器一个都不再检查，且不写任何日志——所以先做字符校验，异常值按"从未重启"处理。
    case "$prev" in
        ''|*[!0-9]*) prev=0 ;;
    esac
    since=$(( now - prev ))
    [ "$since" -ge 0 ] || since=0          # 时间戳在未来（时钟回跳/手工改）时放行，别永久卡死
    if [ "$since" -lt "$MIN_INTERVAL" ]; then
        log "watchdog: $name ${st}/${health} ${secs}s，距上次重启 ${since}s 不足 ${MIN_INTERVAL}s，跳过"
        continue
    fi

    log "watchdog: $name ${st}/${health} ${secs}s → restart"
    # 先记账、再重启：重启过程中被 SIGKILL / OOM / cron 杀掉时时间戳已经落盘，
    # 下一轮不会对同一次故障再补一刀（"失败也要计入退避"的最后一个漏洞）。
    record_restart "$name"
    dk unpause "$name" 2>/dev/null || true
    dk_restart "$name"
    rc=$?
    case "$rc" in
        0) ;;
        124) log "watchdog: $name restart 超时（120s），已计入退避" ;;
        *) log "watchdog: $name restart 失败（rc=$rc），已计入退避" ;;
    esac
done
