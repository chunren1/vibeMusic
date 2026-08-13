# SCRIPTS — vibeMusic 运维脚本

## OVERVIEW
运维脚本目录，三语言混合（PowerShell/Python/JS/Shell），供 root package.json 与手动运维调用。

## INVENTORY
- `health-check.ps1` → `npm run health`：容器健康检查，日志到 docker-data/health-logs，连续 3 次失败告警（Windows only）
- `backup-db.ps1` → `npm run backup:db`：docker exec mysqldump → gzip，保留 7 天，存 docker-data/backups（Windows only）
- `deploy.ps1`：test → mvn package -DskipTests → npm run build → docker-compose down/up --build
- `docker.ps1`：compose 包装（up/down/restart/status/logs）
- `ops.cjs` → `npm run ops`：交互式运维面板（日志/状态/Cookie 检查/停止全部）
- `verify.js`：安全配置一致性检查（拒绝 123456/ChangeMe*/<your 弱默认值）
- `k6-test.js` → `npm run k6`：k6 压测（50 VUs，p95 阈值 search<3000ms / stream<2000ms，需本机安装 k6）
- `get_netease_cookie.mjs` / `get_qq_cookie.mjs`：Cookie 获取辅助
- `ops/`：cookie-monitor.py、check-cookies.py、start-cloudflare-tunnel.{bat,ps1}、setup-scheduler.ps1（Python 依赖见 ops/requirements.txt）
- 其余：health_check.py、test_deepseek.py、batch_download.py、generate-certs.sh

## CONVENTIONS
- 新增脚本后同步更新根 package.json scripts（供 npm run 调用）。
- 敏感值（Cookie/密码）只允许来自环境变量或 .env，禁止写入脚本字面量。

## ANTI-PATTERNS
- 脚本里硬编码密码/密钥 —— verify.js 就是来抓这个的。
- deploy.ps1 的 `docker-compose` 连字符语法已过时，新脚本用 `docker compose`。
