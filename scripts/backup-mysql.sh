#!/bin/sh
# vibeMusic 云 MySQL 备份（2026-09-24 补建：此前云上没有任何自动备份）
# - 密码从容器环境读取，不落盘、不进日志
# - 单事务一致性快照；gzip 后校验完整性；保留 14 天
set -eu
TS=$(date +%Y%m%d-%H%M)
DIR=/opt/vibeMusic/docker-data/backups/mysql
mkdir -p "$DIR"
PW=$(docker inspect vibemusic-mysql --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MYSQL_ROOT_PASSWORD=//p' | head -1)
[ -n "$PW" ] || { echo "[backup] 未取到 MYSQL_ROOT_PASSWORD"; exit 1; }
OUT="$DIR/vibemusic-$TS.sql.gz"
docker exec -e MYSQL_PWD="$PW" vibemusic-mysql sh -c 'exec mysqldump -uroot --single-transaction --quick --routines --triggers --databases vibemusic' | gzip > "$OUT"
[ -s "$OUT" ] || { echo "[backup] 备份为空，判定失败"; rm -f "$OUT"; exit 1; }
gzip -t "$OUT"
find "$DIR" -name 'vibemusic-*.sql.gz' -mtime +14 -delete
echo "[backup] ok: $(ls -la "$OUT" | awk '{print $5" bytes"}') -> $OUT"
