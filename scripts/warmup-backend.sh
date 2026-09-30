#!/bin/sh
# 发版后热身：后端重启后 JVM 为冷态（类懒加载+连接池预热），前几分钟命中者全吃冷启动。
# 本脚本在 backend healthy 后依次打一遍重型端点，把 JIT/连接/缓存烧热。
# 用法：发版 up -d backend 且 healthy 后，在云上执行一次：
#   /bin/sh /opt/vibeMusic/scripts/warmup-backend.sh
# 只读请求，失败只告警不报错退出码（健康门由容器 healthcheck 承担）。
BASE="http://127.0.0.1:8080"
hit() {
  code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 --max-time 60 "$1" 2>/dev/null || echo "curl-fail")
  echo "warmup $2 -> $code"
}
echo "== backend warmup $(date "+%F %T") =="
hit "$BASE/api/songs/hotwords" "hotwords"
hit "$BASE/api/songs/random?count=5" "random"
hit "$BASE/api/recommend/personalized?limit=5" "personalized"
hit "$BASE/api/songs/search?keyword=%E6%99%B4%E5%A4%A9&size=3" "search"
hit "$BASE/api/songs/search?keyword=%E7%83%AD%E6%AD%8C&size=3" "search2"
echo "== warmup done =="
exit 0
