#!/bin/sh
# vibeMusic Nginx 容器入口
# 安装 logrotate + 设置每日轮转定时任务

set -e

# 1. 安装 logrotate
apk add --no-cache logrotate dcron > /dev/null 2>&1

# 2. 复制 logrotate 配置
cp /etc/nginx/logrotate-nginx.conf /etc/logrotate.d/nginx

# 3. 设置每天凌晨 3 点执行 logrotate 的 crontab
echo "0 3 * * * /usr/sbin/logrotate -s /var/lib/logrotate/status /etc/logrotate.d/nginx > /dev/null 2>&1" \
    | crontab -

# 4. 启动 crond 守护进程（后台运行）
crond -b

# 5. 执行原始 Nginx 入口（保持容器前台运行）
exec /docker-entrypoint.sh "$@"
