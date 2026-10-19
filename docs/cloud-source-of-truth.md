# 云上部署真源（Cloud Source of Truth）

> 更新：2026-10-19 · 维护者：部署/运维 lane
> **⚠️ 2026-10-19 晚·运维窗口已执行（本文若干行写于窗口之前，已就地下修）**：云上 MySQL 弱口令已轮换为 24 字符强值（旧口令实测被拒）· 云上 3 个死文件已**隔离**（非删除、可逆）至 `/opt/vibeMusic/.dead-20261019/` · 仓库侧口令兜底已全部改 fail-fast（含 `JWT_SECRET` 那个公开签名密钥）· `monitoring/` 已纳入版本管理。
> 配套工具：`scripts/reconcile-cloud.sh`（只读对账）· 纳管副本：`deploy/production/`
> 一句话：**云服务器 `101.132.167.178` 上的 `/opt/vibeMusic` 不是 git 仓库，靠 `scp` 部署**，
> 所以「云上真源」和仓库会各自漂移。本文说明**谁才是权威**、**怎样把仓库改动安全发上云**、
> 以及**怎样一眼看出两边差在哪**。

---

## 1. 一句话结论

| 问题 | 答案 |
|---|---|
| 生产跑的是哪个 compose？ | **`/opt/vibeMusic/docker-compose.demo.yml`**（5 服务），不是 `docker-compose.yml` |
| 生产前端/反代是谁？ | **云主机上的系统 nginx**（`/etc/nginx`），**不是**仓库里的 `nginx/nginx.conf` 容器配置 |
| 仓库里 `nginx/nginx.conf` 是干嘛的？ | **CI 校验用的"影子配置"**（`ci.yml` 跑 `nginx -t`），生产不使用 |
| 对账方向？ | **永远只有「云上 → 仓库」**。绝不允许把仓库覆盖回云上。 |
| 生产入口？ | Cloudflare Tunnel → 云主机 nginx `127.0.0.1:8081` → `/var/www/vibemusic`（前端静态）+ `/api/` → `127.0.0.1:8080`（后端容器） |

---

## 2. 真实生产拓扑（2026-10-19 实测）

```
                外网
                 │  HTTPS
                 ▼
        ┌──────────────────────┐
        │  Cloudflare（Tunnel） │  隧道名 cyk666，cloudflared systemd 常驻
        └──────────┬───────────┘
                   │ 出站隧道（云主动连 CF，无入站端口）
                   ▼
   ┌───────────────────────────────────────────────────────────┐
   │ 阿里云 ECS 101.132.167.178（Ubuntu，2C/1.6G，根盘 58%）    │
   │                                                           │
   │  系统 nginx（/usr/sbin/nginx, systemd active）             │
   │   ├─ :80                 → cyk666.top 门面页              │
   │   ├─ :8081 (仅回环)      → vibe.cyk666.top                │
   │   │      root /var/www/vibemusic                          │
   │   │      /api/  ──► 127.0.0.1:8080                        │
   │   └─ :3001 (仅回环)      → monitor.cyk666.top（Grafana）   │
   │                                                           │
   │  Docker（compose 项目 vibemusic，工程目录 /opt/vibeMusic，   │
   │          实际加载 docker-compose.demo.yml）                │
   │   ├─ vibemusic-backend   127.0.0.1:8080                   │
   │   ├─ vibemusic-musicapi  127.0.0.1:3000                   │
   │   ├─ vibemusic-mysql     127.0.0.1:3306                   │
   │   ├─ vibemusic-redis     127.0.0.1:6379                   │
   │   ├─ vibemusic-redis-exporter 127.0.0.1:9121              │
   │   └─ node-exporter       127.0.0.1:9100                   │
   │                                                           │
   │  cron（root）                                             │
   │   ├─ 30 3 * * *  scripts/backup-mysql.sh   （每日备份）    │
   │   └─ */5 * * * * scripts/watchdog.sh       （容器看门狗）  │
   └───────────────────────────────────────────────────────────┘
```

关键事实（都已实测，不要凭记忆猜）：

- **没有 nginx 容器在跑**。`docker ps` 里只有 6 个容器，没有 nginx。反代由**系统 nginx** 承担。
- 前端产物落在 `/var/www/vibemusic`（**不在** `/opt/vibeMusic` 里，也不在 git 里）。
- `cloudflared` 配置文件 `/etc/cloudflared/config.yml`，指向 `localhost:8081/80/3001`。
- `vibe.cyk666.top` 走 **Cloudflare Tunnel**，不是灰云 A 记录直连（nginx 并没监听 443）。
- `/opt/vibeMusic/monitoring/`、`docker-compose.yml`、`docker-compose.full.yml` 在云上**都是死文件**（没被加载）。

---

## 3. 谁是权威？（按文件）

### 3.1 云上权威 → 已纳管进仓库

这些文件**仓库里没有或落后**，云上是唯一真源，本次已收录。

| 云上路径 | 仓库纳管位置 | 说明 |
|---|---|---|
| `/opt/vibeMusic/scripts/backup-mysql.sh` | `scripts/backup-mysql.sh` | MySQL 每日备份，cron 03:30。**逐字节原样收录**（md5 一致，可被对账脚本比对） |
| `/opt/vibeMusic/scripts/watchdog.sh` | `scripts/watchdog.sh` | 容器 unhealthy 看门狗，cron 每 5 分钟。**逐字节原样收录** |
| `/etc/nginx/sites-enabled/vibemusic` | `deploy/production/nginx/vibemusic-site.conf` | **真正的生产 vhost**（`vibe.cyk666.top`，8081 → 8080） |
| `/etc/nginx/conf.d/security.conf` | `deploy/production/nginx/security.conf` | 生产安全头 + limit_req/limit_conn 共享内存区 |
| `/etc/cloudflared/config.yml` | `deploy/production/cloudflared/config.yml` | 隧道 ingress 规则 |
| root crontab | `deploy/production/crontab.txt` | 两条定时任务（对账时按"有效行"归一化比对） |

> 注意：`/etc/nginx/nginx.conf`（主配置，md5 `cc381056`）是 **Ubuntu 发行版默认**，未做改动，
> 因此**不收录**（收录只会增加噪音）。真正自定义的只有 `sites-enabled/vibemusic` 与
> `conf.d/security.conf`。

### 3.2 仓库权威 → 云上落后，**不回灌**

这些文件仓库比云上新（且多数更安全）。**把它们同步到云上属于"发布"动作，本轮不做。**

| 文件 | 云上状态 | 结论 |
|---|---|---|
| `docker-compose.demo.yml` | **曾**仅第 4 行注释措辞不同；**2026-10-19 起仓库版已把 `${VAR:-123456}` 等弱兜底改为 `${VAR:?}`，云上仍是旧兜底**（云上 `.env` 已是强口令，故兜底不生效） | 仓库为准；下次发版时同步到云上 |
| `docker-compose.yml` | 云上是 9-04 的旧综合版（16 服务，含 ES/alertmanager/otel，且与云的 `full.yml` 完全相同），端口写 `3306:3306`（公网暴露）；仓库是 9-23 现版（14 服务，`127.0.0.1:3306`） | 仓库新且更安全 |
| `docker-compose.full.yml` | 同上旧版；`GRAFANA_ADMIN_PASSWORD` 用 `:-admin` 弱默认 | 仓库用 `:?` fail-fast |
| `nginx/nginx.conf` | 云版是旧版（无 `gzip_static`、无 `.map` 拒访、CSP 缺 `worker-src blob:`） | 仓库新；且**此文件是 CI 影子配置**，生产不用 |
| `nginx/docker-entrypoint.sh` | 云版 `set -e` + `apk add` 失败会导致容器重启循环 | 仓库含 round6 健壮性修复 |
| `scripts/health_check.py` | 云版多一行 ES 健康检查，但 ES 已从项目移除 | 云版是移除 ES 前的残留 |
| `scripts/adversarial-chaos.sh` | 云版**没有**目标守卫与 cleanup trap | 仓库含 O5 安全守卫（非本地目标须 `--yes`）——云版会无确认 pause 生产容器，**危险** |
| `scripts/adversarial-security.sh` | 同上 | 仓库含非本地目标守卫 |
| `monitoring/prometheus.yml` | 云版是精简旧版 | 仓库是"本机唯一监控中心"版（**2026-10-19 起 `monitoring/` 已纳入版本管理**，仓库为真源；云上旧版已随该目录隔离） |
| `monitoring/docker-compose.yml` | 云版曾**明文写死** `GF_SECURITY_ADMIN_PASSWORD`（**值不落文档、不落仓库**——本文档遵循仓库既有口径：只记位置/形态，不记值） | ✅ 已处理（2026-10-19）：口令已轮换、云上该目录已整体隔离；仓库版走 `${GF_SECURITY_ADMIN_PASSWORD:?}` fail-fast |

### 3.3 完全一致（无漂移）

`.env.docker`、`nginx/Dockerfile`、`nginx/k8s-nginx.conf`、`nginx/logrotate-nginx.conf`、
`musicapi/Dockerfile`、`vibeMusic-backend/Dockerfile`、`scripts/batch_download.py`、
`scripts/generate-certs.sh`、`scripts/ops/check-cookies.py`、`scripts/ops/cookie-monitor.py`、
`scripts/test_deepseek.py`、`scripts/warmup-backend.sh`、`monitoring/blackbox.yml`、
`vibemusic-web/.env.example`。

---

## 4. 「两个 nginx 配置」到底是什么

这是最容易踩的坑，务必分清：

| | `nginx/nginx.conf`（仓库根，252 行） | `deploy/production/nginx/vibemusic-site.conf`（本次新增） |
|---|---|---|
| 谁在用 | **CI**（`.github/workflows/ci.yml` 用 `nginx -t` 校验），以及**自建 nginx 容器**方案 | **云主机系统 nginx**（就是线上真实反代） |
| 监听 | `443 ssl` + 自签/挂载证书，`server_name _` | `127.0.0.1:8081`，`server_name vibe.cyk666.top` |
| 上游 | `upstream backend:8080`（Docker DNS 服务名） | `proxy_pass http://127.0.0.1:8080`（宿主回环） |
| 证书 | 容器内 `/etc/nginx/certs/` | **不用证书**（TLS 由 Cloudflare 终结） |
| 前端根 | `/usr/share/nginx/html` | `/var/www/vibemusic` |
| 能否互改 | ❌ 改了会挂 CI | ❌ 改了会挂线上 |

**结论：两份配置不是一个东西，不要合并、不要互相覆盖。**
生产配置以**新增文件**形式纳管（`deploy/production/nginx/*`），刻意不放进 `nginx/` 目录，
就是为了避免和 CI 影子配置混淆。

---

## 5. 对账脚本怎么用

```bash
cd ~/projects/vibeMusic
scripts/reconcile-cloud.sh          # 打印漂移表；有漂移→退出码 1，全一致→0
scripts/reconcile-cloud.sh --help
```

特性：

- **纯只读**：远端只执行 `md5sum` / `wc -l` / `crontab -l`，绝不写云上。
- **幂等可重复**：结果只取决于当时两边状态。
- **凭据安全**：按变量名从 `~/.dsh/.credentials.yaml` 读 `VIBEMUSIC_SERVER_*`，不硬编码、不打印。
- **不依赖 sshpass**：装了走 `sshpass -e`（密码走环境变量），没装自动退化为 `key-only ssh`。
  （实测云主机支持 key-only 登录，所以两种方式都能跑。）
- **退出码可用于 CI**：`0` 一致 / `1` 有漂移 / `3` 凭据缺失 / `4` 连不上。

输出列：`文件 | md5(云) | md5(仓) | 行数(云) | 行数(仓) | 状态`，
状态取 `一致` / `★漂移` / `仅云上有` / `仅仓库有`。

> 未纳入对账的项目（有意为之）：
> - `.env`（云上是生产密钥，仓库本地 `.env` 是开发用，比对没有意义且涉密）
> - `/etc/nginx/nginx.conf`（发行版默认，未自定义）
> - `/var/www/vibemusic`（前端构建产物，不是配置）

---

## 6. 如何把仓库改动安全发到云上

> ⚠️ **本轮不发布**。以下是**将来**发版时的操作规范，供新人照做。

### 6.1 发到 `/opt/vibeMusic` 的文件（compose / scripts / nginx 容器配置）

```bash
cd ~/projects/vibeMusic

# 0. 先对账，确认要发的文件当前漂在哪
scripts/reconcile-cloud.sh || true

# 1. 备份云上目标文件（云上写操作仅此一处，且只新增 .bak，不删不改旧文件）
ssh root@101.132.167.178 'cp -a /opt/vibeMusic/docker-compose.demo.yml \
                            /opt/vibeMusic/docker-compose.demo.yml.bak-$(date +%Y%m%d-%H%M%S)'

# 2. 传新文件（scp 覆盖目标文件）
scp docker-compose.demo.yml root@101.132.167.178:/opt/vibeMusic/docker-compose.demo.yml

# 3. 校验落地字节 == 仓库字节（"不信报告信字节"）
md5sum docker-compose.demo.yml
ssh root@101.132.167.178 'md5sum /opt/vibeMusic/docker-compose.demo.yml'

# 4. 生效（这一步才是"发布"）
ssh root@101.132.167.178 'cd /opt/vibeMusic && docker compose -f docker-compose.demo.yml up -d'

# 5. 回滚：把第 1 步的 .bak 拷回原名，再重跑第 4 步
```

### 6.2 发到系统 nginx（`deploy/production/nginx/*`）

```bash
# 备份 → 传 → nginx -t 校验 → 才 reload（顺序不能反）
ssh root@101.132.167.178 'cp -a /etc/nginx/sites-enabled/vibemusic \
                            /etc/nginx/sites-enabled/vibemusic.bak-$(date +%Y%m%d-%H%M%S)'
scp deploy/production/nginx/vibemusic-site.conf root@101.132.167.178:/etc/nginx/sites-enabled/vibemusic
ssh root@101.132.167.178 'nginx -t && systemctl reload nginx'   # -t 失败就千万别 reload
```

### 6.3 发前端产物（不在本 lane 范围，仅备注）

前端 `dist` 需要落到 `/var/www/vibemusic`（云主机，非容器）。参见仓库 README 的部署段与
`vibeMusic` 部署技能文档。**不要**把这个目录当成配置纳管。

### 6.4 禁忌清单（硬性）

1. ❌ 禁止把仓库文件"顺手"覆盖回云上而不备份、不对 md5。
2. ❌ 禁止 `docker compose down -v`（会删数据卷）。只允许 `up -d` 做增量更新。
3. ❌ 禁止在云上跑构建：内存只有 1.6G，`docker build` / `mvn` 会 OOM 并拖垮线上。
   镜像必须在别处构建好再推/传。
4. ❌ 禁止 `docker system prune` / 清 docker cache —— 云盘已 58% 满，且会误删有用镜像。
5. ✅ **已于 2026-10-19 运维窗口处理**（本行原先写的是「禁止」，现作废）：云上 MySQL 口令已轮换并验证（新值可连、**旧值被拒**），仓库侧 `-123456`/`-admin` 兜底已清零改用 `${VAR:?}` fail-fast。云上 `docker-compose.demo.yml` 内仍留旧兜底，等下次发版同步。
6. ❌ 禁止直接编辑 `/etc/nginx/sites-enabled/vibemusic` 这类"已生效文件"而不先备份，
   也禁止绕过 `nginx -t` 直接 `reload`。
7. ❌ 禁止把 `/root/.cloudflared/*.json`（隧道私钥）或云上 `.env` 提交进仓库。

---

## 7. 待用户裁决（语义冲突 / 安全隐患，本 lane 未擅自处理）

| # | 事项 | 现状 | 建议 |
|---|---|---|---|
| 1 | ~~云上 `docker-compose.yml` 与 `docker-compose.full.yml` 是死文件~~ | ✅ **已处理（2026-10-19）** | 两文件已**隔离**至 `/opt/vibeMusic/.dead-20261019/`（**可逆，非删除**），并附 README 写明原因与恢复方式。因它们含 `3306:3306` 公网绑定，误 `up -d` 会起第二个 nginx，故不再留在部署目录 |
| 2 | ~~云上 `monitoring/docker-compose.yml` 明文写死 Grafana 口令~~ | ✅ **已处理（2026-10-19）** | 云上 Grafana 口令已随窗口轮换为 20 字符强值（值在云上 `.env` 与本地 `~/.dsh/.credentials.yaml` 的 `VIBEMUSIC_GRAFANA_PASSWORD`）；`monitoring/` 目录已整体隔离。注：云上**没有** Grafana 容器，该口令属惰性配置 |
| 3 | 云上 `.env.example` 含 `ES_PASSWORD`/`ES_URI`、`THREADPOOL_ASYNCCACHE*`，缺少 `COOKIE_ENC_KEY`（后端启动硬性要求） | 仓库版是 ES 移除后的现行版 | 建议以仓库版为准；其中 `THREADPOOL_ASYNCCACHE*` 是否要补回仓库**待确认**（后端 `ThreadPoolConfig` 未引用该前缀） |
| 4 | 云上 `nginx/sites-available/vibemusic`(1120B) 与 `sites-enabled/vibemusic`(1273B) **内容不同**（前者是旧版无 `/uploads/` 反代） | `sites-enabled` 是生效版 | 建议清理 `sites-available` 旧版，避免将来笔误 enable 到旧配置 |
| 5 | ~~云上 `monitoring/prometheus.yml` 旧版 / `monitoring/` 是否入库~~ | ✅ **已决（2026-10-19）** | 仓库 `monitoring/` 已从 `.gitignore` 放出并**纳入版本管理**（9 个配置可见，含 dashboards）；云上旧精简版已随目录隔离。**仓库为真源** |

---

## 8. 本次纳入清单（2026-10-19）

新增：

```
deploy/production/nginx/vibemusic-site.conf      ← 云 /etc/nginx/sites-enabled/vibemusic（逐字节一致）
deploy/production/nginx/security.conf            ← 云 /etc/nginx/conf.d/security.conf（逐字节一致）
deploy/production/cloudflared/config.yml         ← 云 /etc/cloudflared/config.yml（逐字节一致）
deploy/production/crontab.txt                    ← 云 root crontab（去注释后一致）
deploy/production/README.md
deploy/production/nginx/README.md
scripts/backup-mysql.sh                          ← 云独有，逐字节原样收录
scripts/watchdog.sh                              ← 云独有，逐字节原样收录
scripts/reconcile-cloud.sh                       ← 只读对账工具
docs/cloud-source-of-truth.md                    ← 本文
```

**未改动**：`nginx/nginx.conf`（CI 影子配置，禁止覆盖）、后端/前端业务代码、
`.github/workflows/`、任何数据库口令、任何 compose 文件。

> 说明：云上唯一权威的脚本以**逐字节原样**收录（不追加来源注释），这样对账脚本才能直接
> md5 比对出`一致`；来源信息记在本表与 `deploy/production/README.md`，不写进脚本本体。
