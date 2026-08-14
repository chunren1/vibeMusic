# vibeMusic 部署指南 v2.0

## ⚠️ 接收方从零上手（克隆后必读）

本仓库**不包含任何真实凭据**（.env、Cookie、密码均不入库）。克隆后需要自行创建 3 个配置文件才能运行：

```bash
# 1. 环境变量（必做）
cp .env.example .env
# 生成强密码并填入 .env:
#   node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"

# 2. musicapi Cookie 配置（必做，musicapi 依赖它启动）
cp musicapi/config.example.js musicapi/config.js
# 填入你自己的 QQ音乐/网易云 Cookie（见 config.example.js 顶部说明）

# 3. Redis 配置（Docker 部署必做；redis.conf 含密码不入库）
cp docker-data/redis/redis.conf.example docker-data/redis/redis.conf
# 编辑 redis.conf，把 requirepass <YOUR_REDIS_PASSWORD> 替换为与 .env 相同的 REDIS_PASSWORD
# （Redis 配置文件不支持环境变量展开，必须写入明文；compose healthcheck/后端读 .env 的 REDIS_PASSWORD，两者需一致）
```

> **为什么缺这 3 个文件？** 它们包含真实凭据（QQ/网易云 Cookie、Redis 密码），已被 `.gitignore` 排除，确保仓库可公开分享。
>
> **隐私说明**：仓库中所有密码均为占位符（`<your-...>`）或环境变量引用（`${...}`）。请勿把你的真实 Cookie/密钥提交到 git。

### 必须修改的弱默认值（生产环境）

`docker-compose.yml` 中的环境变量带弱默认值（如 MySQL `123456`、JWT `ChangeMeJWTSecretKey123!`），**生产部署务必在 .env 中覆盖**：

| 变量 | 弱默认值 | 建议 |
|------|---------|------|
| `MYSQL_ROOT_PASSWORD` / `DB_PASSWORD` | `123456` | 24+ 位随机密码 |
| `JWT_SECRET` | `ChangeMeJWTSecretKey123!` | 256-bit 随机串 |
| `ES_PASSWORD` | `ChangeMeES123!` | 24+ 位随机密码 |
| `MINIO_ROOT_PASSWORD` / `MINIO_SECRET_KEY` | `ChangeMe456!` | 24+ 位随机密码 |
| `GRAFANA_ADMIN_PASSWORD` | `admin` | 强密码 |

### 检查你的配置未泄露

```bash
git status                    # 应只看到你修改的文件
git ls-files | grep -E '(\.env$|config\.js$|redis\.conf$|\.bak)'   # 应为空
```

---

## 前置条件

| 依赖 | 版本要求 | 用途 |
|------|---------|------|
| Docker | ≥ 24.x | 容器运行时 |
| Docker Compose | ≥ v2.x | 多容器编排 |
| Java | 17 | 本地开发编译 |
| Node.js | ≥ 20 | 前端构建 + BFF 网关 |
| 域名 | 可选 | 公网访问（推荐 Cloudflare Tunnel） |

---

## 架构

```
浏览器 → Nginx(:80/443) → Backend(:8080) → musicapi(:3000) → 网易云/QQ
                            ↓    ↓    ↓
                        MySQL Redis MinIO  ES
                            ↓    ↓
                        Prometheus → Grafana → Alertmanager
```

---

## 一、快速部署（本地开发）

```bash
# 1. 复制环境变量
cp .env.example .env
# 生成强密码: node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"

# 2. 启动中间件
npm run docker:middleware

# 3. 启动应用
npm run dev:api        # musicapi (localhost:3000)
npm run dev:web        # 前端 (localhost:5173)
# IDEA 里启动后端 (localhost:8080)

# 4. 监控面板
# Grafana:  http://localhost:3001 (admin / 见 .env)
# Prometheus: http://localhost:9090
```

### 验证

```bash
node scripts/verify.js    # 28 项自动检查
curl http://localhost:3000/health  # musicapi 健康
```

---

## 二、生产部署（Docker 全栈）

### 1. 准备

```bash
# 构建前端
cd vibemusic-web && npm run build

# 构建后端
cd vibeMusic-backend && mvn clean package -DskipTests
```

### 2. 切换 Prometheus 配置

编辑 `docker-data/prometheus/prometheus.yml`：
```yaml
# 注释掉本地开发 target:
# - 'host.docker.internal:8080'
# 取消注释生产 target:
- 'backend:8080'
- 'musicapi:3000'
```

### 3. 启动

```bash
docker compose up -d --build
```

### 4. 验证

```bash
docker compose ps                  # 全部 healthy
curl -k https://localhost/health   # Nginx 转发正常
```

---

## 三、环境变量清单

| 变量 | 必填 | 说明 |
|------|------|------|
| `MYSQL_ROOT_PASSWORD` | ✅ | MySQL root 密码 |
| `DB_PASSWORD` | ✅ | 应用数据库密码 |
| `REDIS_PASSWORD` | ✅ | Redis 认证密码 |
| `ES_PASSWORD` | ✅ | Elasticsearch 密码 |
| `JWT_SECRET` | ✅ | JWT 签名密钥（≥32字节） |
| `MINIO_ROOT_PASSWORD` | ✅ | MinIO 管理密码 |
| `MUSIC_NETEASE_COOKIE` | ✅ | 网易云 VIP Cookie |
| `MUSIC_QQ_COOKIE` | ✅ | QQ 音乐 Cookie (JSON) |
| `GRAFANA_ADMIN_PASSWORD` | ✅ | Grafana 登录密码 |
| `AI_API_KEY` | ❌ | AI 助手 API Key |
| `CORS_ORIGINS` | ❌ | 跨域白名单 |

---

## 四、npm 脚本速查

| 命令 | 功能 |
|------|------|
| `npm run docker:middleware` | 启动全部中间件（含 ES + 监控 + Nginx） |
| `npm run docker:monitor` | 仅 Prometheus + Grafana + Alertmanager |
| `npm run docker:status` | 查看容器状态 |
| `npm run dev:full` | 一键启动前后端 + musicapi |
| `npm run ops` | 运维工具菜单 |

### 2. 构建

```bash
# 前端构建
npm run build

# 后端构建 JAR
cd vibeMusic-backend && mvn package -DskipTests -B && cd ..

# 生成 TLS 证书（如有 Git Bash/WSL）
bash scripts/generate-certs.sh
```

### 3. 启动全栈

```bash
docker compose up -d
```

### 4. 验证

```bash
docker compose ps                    # 所有容器应显示 healthy
curl -k https://localhost/api/songs/search?keyword=test  # API 正常
```

---

## 二、服务清单

| 服务 | 端口 | 说明 |
|------|------|------|
| Nginx (HTTPS) | 80, 443 | 统一入口，自动 HTTP→HTTPS 跳转 |
| Spring Boot | 8080 | 后端 API |
| MySQL | 3306 | 关系数据库 |
| Redis | 6379 | 缓存 |
| MinIO | 9000, 9001 | 对象存储（API + 控制台） |
| Elasticsearch | 9201 | 搜索缓存 |
| musicapi | 3000 | 网易云 + QQ 音乐 API 代理 |
| Prometheus | 9090 | 指标采集 |
| Grafana | 3001 | 可视化仪表盘 |
| Alertmanager | 9093 | 告警管理 |

**自动备份**：MySQL 每天凌晨 2 点备份到 `docker-data/backups/mysql/`，保留 30 天。

---

## 三、公网访问

### 方案 A：Cloudflare Tunnel（推荐，免费）

已有配置：域名 `www.vibemusic.abrdns.com`。启动脚本：`scripts/ops/start-cloudflare-tunnel.bat`（Windows）/ `start-cloudflare-tunnel.ps1`。

> 注意：`.cloudflared/` 目录（含 Tunnel 凭据）已被 `.gitignore` 排除。接收方需自行 `cloudflared tunnel login` 创建自己的 Tunnel（见下方步骤）。

创建自己的 Tunnel（替代原 `.cloudflared/` 目录）：

```bash
# 安装 cloudflared: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
cloudflared tunnel login
cloudflared tunnel create vibemusic
cloudflared tunnel route dns vibemusic your-domain.com
cloudflared tunnel run vibemusic
```

Tunnel 自带 HTTPS 终止，无需额外证书配置。

### 方案 B：直连 + Let's Encrypt

```bash
# 安装 certbot
apt install certbot python3-certbot-nginx
# 获取证书
certbot --nginx -d your-domain.com
# 证书路径替换 nginx/certs/ 下的自签证书
# 配置自动续期（certbot 默认已添加 crontab）
```

### 方案 C：Cloudflare Origin CA

Cloudflare 控制台 → SSL/TLS → Origin Server → 创建证书 → 替换 `nginx/certs/` 下的文件。

---

## 四、监控与告警

### Grafana 仪表盘

- 地址：`http://<server>:3001`
- 默认账号：`admin / admin`
- 预置仪表盘：JVM 指标、搜索延迟、缓存命中率、HTTP 请求统计

### Prometheus 告警规则

内建规则（`docker-data/prometheus/alert-rules.yml`）：
- 服务宕机（连续 1 分钟）
- 搜索 P95 > 1s
- JVM 堆内存 > 85%
- 缓存穿透率 > 70%

### 接入告警通知（微信/钉钉）

编辑 `docker-data/alertmanager/alertmanager.yml`：

```yaml
receivers:
  - name: 'wechat'
    webhook_configs:
      - url: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_KEY'
```

---

## 五、生产安全检查清单

| 项 | 状态 | 说明 |
|----|------|------|
| HTTPS | ✅ | HTTP 自动跳转 HTTPS |
| HSTS | ✅ | max-age=63072000 |
| 安全头 | ✅ | X-Frame-Options, X-Content-Type, XSS-Protection |
| API 限流 | ✅ | 通用 10r/s，登录 3r/s |
| 数据库备份 | ✅ | 每天凌晨 2 点，保留 30 天 |
| 健康检查 | ✅ | 所有容器 healthcheck |
| 自动重启 | ⚠️ | 当前 compose 为 `restart: "no"`，生产环境建议改为 `unless-stopped` |
| .env 排除 Git | ✅ | `.gitignore` 已配置 |
| 证书排除 Git | ✅ | `nginx/certs/.gitignore` 已配置 |

---

## 六、日常运维

### 查看日志

```bash
docker compose logs -f --tail=100 backend   # 后端实时日志
docker compose logs mysql-backup             # 备份日志
```

### 手动备份

```bash
docker compose exec mysql mysqldump -u root -p vibemusic | gzip > backup-$(date +%Y%m%d).sql.gz
```

### 恢复备份

```bash
gunzip < backup-20260707.sql.gz | docker compose exec -T mysql mysql -u root -p vibemusic
```

### 更新部署

```bash
git pull
npm run build
cd vibeMusic-backend && mvn package -DskipTests && cd ..
docker compose up -d --build backend nginx
```

### 扩容（多实例）

```bash
docker compose up -d --scale backend=3
# 3 个后端实例，Nginx 自动负载均衡
```

---

## 七、故障排查

| 症状 | 检查点 |
|------|--------|
| 502 Bad Gateway | `docker compose ps backend` 看健康状态 |
| 搜索超时 | `docker compose ps musicapi` 确认运行中 |
| 播放失败 | musicapi 网易云 Cookie 是否过期 |
| MinIO 登录失败 | `.env` 中 `MINIO_ROOT_PASSWORD` 是否正确 |
| 端口占用 | `netstat -ano \| findstr :8080` 找僵尸进程 |

---

## 八、CI/CD（GitHub Actions）

- `.github/workflows/test.yml`：每次 push/PR 自动跑后端 + 前端测试
- `.github/workflows/deploy.yml`：手动触发或推送 tag 时构建镜像

需补充：自动推送到服务器（SSH + docker compose pull + up）
