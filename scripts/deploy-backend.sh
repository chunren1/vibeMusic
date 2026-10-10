#!/bin/sh
# 后端一条命令发版：本地打 jar → 传云 → 重建 → healthy → md5 对包 → 热身 → 推荐冒烟。
# 用法：在仓库根执行 ./scripts/deploy-backend.sh
# 约定：密码只从 ~/.dsh/.credentials.yaml 按变量名取，本脚本不存任何密钥；
# 云上 compose 文件固定用 docker-compose.demo.yml（默认文件 backend 服务定义不一致）。
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="$REPO/vibeMusic-backend"
CREDS="$HOME/.dsh/.credentials.yaml"
COMPOSE="docker-compose.demo.yml"

need() { command -v "$1" >/dev/null 2>&1 || { echo "缺命令: $1"; exit 1; }; }
need sshpass; need scp; need ssh; need java
[ -x "$BACKEND/mvnw" ] || { echo "缺 mvnw"; exit 1; }

HOST="$(grep '^  VIBEMUSIC_SERVER_HOST:' "$CREDS" | awk '{print $2}')"
USER="$(grep '^  VIBEMUSIC_SERVER_USER:' "$CREDS" | awk '{print $2}')"
PASS="$(grep '^  VIBEMUSIC_SERVER_PASSWORD:' "$CREDS" | awk '{print $2}')"
[ -n "$HOST" ] && [ -n "$USER" ] && [ -n "$PASS" ] || { echo "凭据缺失，检查 $CREDS"; exit 1; }
SSH="sshpass -p $PASS ssh -o StrictHostKeyChecking=no -o ConnectTimeout=15 $USER@$HOST"
SCP="sshpass -p $PASS scp -o StrictHostKeyChecking=no -o ConnectTimeout=30"

echo "== 1/7 本地打包 =="
cd "$BACKEND" && ./mvnw package -DskipTests -q 2>&1 | tail -2
JAR="$BACKEND/target/vibeMusic-backend-0.0.1-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo "jar 未生成"; exit 1; }
MD5_LOCAL="$(md5sum "$JAR" | awk '{print $1}')"
echo "本地 md5: $MD5_LOCAL"

echo "== 2/7 云上备份+清 target（防双 jar 血坑） =="
$SSH "cd /opt/vibeMusic/vibeMusic-backend/target && TS=\$(date +%Y%m%d-%H%M%S) && for f in *.jar; do [ \"\$f\" = '*.jar' ] || cp \"\$f\" /tmp/app-backup-\$TS-\"\$f\"; done; rm -f *.jar; ls -la"

echo "== 3/7 传包+对 md5 =="
$SCP "$JAR" "$USER@$HOST:/opt/vibeMusic/vibeMusic-backend/target/app.jar"
MD5_CLOUD="$($SSH 'md5sum /opt/vibeMusic/vibeMusic-backend/target/app.jar' | awk '{print $1}')"
[ "$MD5_LOCAL" = "$MD5_CLOUD" ] || { echo "传包损坏: $MD5_LOCAL vs $MD5_CLOUD"; exit 1; }
echo "md5 一致"

echo "== 4/7 云上重建 =="
$SSH "cd /opt/vibeMusic && docker compose -f $COMPOSE build backend 2>&1 | tail -2"

echo "== 5/7 启动+等 healthy（最多 8 分钟） =="
$SSH "cd /opt/vibeMusic && docker compose -f $COMPOSE up -d backend 2>&1 | tail -1"
echo "  （注意：应用启动需 3-4 分钟——预热 17 个关键词要打外部 API；启动期间 healthcheck 报 unhealthy 属正常，会持续等到 healthy）"
for i in $(seq 1 16); do
  ST="$($SSH 'docker ps --format "{{.Names}} {{.Status}}" | grep vibemusic-backend' || true)"
  echo "  [$i] $ST"
  # 必须用 "(healthy)" 精确匹配：*healthy* 会把 "unhealthy" 也匹配上（2026-10-10 踩坑）
  case "$ST" in *"(healthy)"*) break;; esac
  sleep 30
done
case "$ST" in *"(healthy)"*) ;; *) echo "backend 未 healthy（8 分钟超时）；unhealthy 会等待应用跑完预热自动恢复，持续 unhealthy 见手册回滚"; exit 1;; esac

echo "== 6/7 三重验证：容器内 md5 + 启动日志 + 热身 =="
MD5_RUN="$($SSH 'docker exec vibemusic-backend md5sum /app/app.jar' | awk '{print $1}')"
[ "$MD5_LOCAL" = "$MD5_RUN" ] || { echo "线上跑的不是新包! $MD5_RUN"; exit 1; }
$SSH "docker logs vibemusic-backend 2>&1 | grep -q 'Started VibeMusicBackendApplication' && echo 启动日志OK" || { echo "启动日志异常：未见 Started（理论上 healthy 后必有，请查 docker logs）"; exit 1; }
$SSH "/bin/sh /opt/vibeMusic/scripts/warmup-backend.sh"

echo "== 7/7 推荐冒烟：同设备连刷 3 次，查重叠 =="
$SSH 'for i in 1 2 3; do curl -s --noproxy "*" --max-time 30 "http://127.0.0.1:8080/api/recommend/personalized?refresh=true" -H "X-Device-Id: deploy-smoke" | python3 -c "import json,sys; d=json.load(sys.stdin); print(sorted(s[\"sourceId\"] for s in d[\"data\"][\"songs\"]))"; done' || { echo "冒烟失败：部署已就绪但推荐接口验收未通过，检查上方输出"; exit 1; }
echo "== 发版完成 =="
