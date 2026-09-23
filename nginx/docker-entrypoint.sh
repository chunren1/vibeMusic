#!/bin/sh
# vibeMusic Nginx 容器入口
# 可选：安装 logrotate + 设置每日轮转定时任务
#
# 健壮性：容器启动不得依赖公网 alpine 源。此前 `set -e` + `apk add` 使得
# 源不可达时 entrypoint 直接退出 → 容器反复 restart（round6 I4）。
# 现在装不上就跳过轮转，日志退化为「容器内累积 + docker logs」，服务照常启动。

set -e

# 1. 尝试安装 logrotate（失败不致命）
if apk add --no-cache logrotate dcron > /dev/null 2>&1; then
    # 2. 复制 logrotate 配置
    cp /etc/nginx/logrotate-nginx.conf /etc/logrotate.d/nginx

    # 3. 设置每天凌晨 3 点执行 logrotate 的 crontab
    echo "0 3 * * * /usr/sbin/logrotate -s /var/lib/logrotate/status /etc/logrotate.d/nginx > /dev/null 2>&1" \
        | crontab -

    # 4. 启动 crond 守护进程（后台运行）
    crond -b
    echo "[entrypoint] logrotate + crond 已启用"
else
    echo "[entrypoint] 警告：logrotate/dcron 安装失败（alpine 源不可达?），跳过日志轮转；服务继续启动" >&2
fi

# 5. 执行原始 Nginx 入口（保持容器前台运行）
exec /docker-entrypoint.sh "$@"
