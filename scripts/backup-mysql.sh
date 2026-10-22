#!/usr/bin/env bash
# vibeMusic 云 MySQL 备份（2026-09-24 补建：此前云上没有任何自动备份）
# - 密码从容器环境读取，不落盘、不进日志
# - 单事务一致性快照；gzip 后校验完整性；保留 14 天
# 2026-10-22 修「假成功」：原脚本用 sh 且只有 set -eu，mysqldump 失败时 gzip 仍退 0，
# 被截断的 dump 能通过 -s / gzip -t 检查。改用 bash + pipefail，并校验 dump 尾部标记。
set -euo pipefail
TS=$(date +%Y%m%d-%H%M)
DIR=/opt/vibeMusic/docker-data/backups/mysql
mkdir -p "$DIR"
PW=$(docker inspect vibemusic-mysql --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MYSQL_ROOT_PASSWORD=//p' | head -1)
[ -n "$PW" ] || { echo "[backup] 未取到 MYSQL_ROOT_PASSWORD"; exit 1; }
OUT="$DIR/vibemusic-$TS.sql.gz"
TMP="$OUT.part"

# 失败时清理半成品，避免留下看似可用、实则截断的备份
cleanup() {
    rc=$?
    if [ $rc -ne 0 ]; then
        rm -f "$TMP"
        echo "[backup] FAILED rc=$rc —— 已清理半成品，保留上一份完整备份" >&2
    fi
}
trap cleanup EXIT

# pipefail 生效：mysqldump 失败会让整条管道失败，不会再被 gzip 的 0 退出码掩盖
docker exec -e MYSQL_PWD="$PW" vibemusic-mysql \
    sh -c 'exec mysqldump -uroot --single-transaction --quick --routines --triggers --databases vibemusic' \
    | gzip > "$TMP"

[ -s "$TMP" ] || { echo "[backup] 备份为空，判定失败"; exit 1; }
gzip -t "$TMP"

# 内容校验：mysqldump 正常结束时尾部有 "-- Dump completed"，截断的 dump 不会有
TAIL=$(zcat "$TMP" | tail -c 256)
case "$TAIL" in
    *"Dump completed"*) : ;;
    *) echo "[backup] dump 尾部缺少 'Dump completed'，判定截断" >&2; exit 1 ;;
esac

# 体积下限：空库结构也有几十 KB，小于 1KB 基本可判定异常
SIZE=$(wc -c < "$TMP")
if [ "$SIZE" -lt 1024 ]; then
    echo "[backup] 备份体积异常（$SIZE bytes）" >&2
    exit 1
fi

mv "$TMP" "$OUT"          # 只有全部校验通过才转正
find "$DIR" -name 'vibemusic-*.sql.gz' -mtime +14 -delete
find "$DIR" -name '*.part' -mmin +60 -delete   # 清理历史残留半成品
echo "[backup] ok: $SIZE bytes -> $OUT"
