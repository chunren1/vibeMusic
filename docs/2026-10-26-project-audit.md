# vibeMusic 全栈项目评估（行业视角）

> 评估日期：2026-10-26 · 方法：**只写实测到的**，每条结论附命令输出或 `文件:行号`；未取证的一律标注「未验证」
> 证据采集：宿主/容器/DB/Redis/nginx/日志 4 轮远端只读体检 + 本地代码静态统计 + CI 配置审阅
> 姊妹文档：`docs/2026-10-22-project-optimization-plan.md`（历史优化批次与 §13.x 明细）

---

## 0. 一句话结论

**这是一个「运维成熟度明显高于代码成熟度、边缘架构选型明显优于体量预期」的单人全栈项目。**
真正该修的都不是"技术栈老旧"，而是 4 类可低成本消除的风险：**前端源码公开泄露（含 `sourcesContent`）、MySQL 镜像 4 年未更新、可观测性只有装饰没有采集、异地备份有断档且从未真实恢复演练过**。
服务器**不是**瓶颈（CPU 负载 0.19，容量余量 1000 倍以上），不该升级配置。

---

## 1. 系统全景（实测）

| 层 | 实测事实 | 证据 |
|---|---|---|
| 边缘 | Cloudflare 免费版橙云 → **Cloudflare Tunnel**（cloudflared active） | `pgrep -a cloudflared` → `tunnel --config /etc/cloudflared/config.yml run`；`cf-ray: …-AMS` |
| 反代 | **宿主 nginx 1.18.0**（Ubuntu 22.04 自带），只监听 `127.0.0.1:8081` | `nginx -v` → `nginx/1.18.0 (Ubuntu)`；`ss -tlnp` → `127.0.0.1:8081` |
| 前端 | Vue 3.5.32 + **Vite 8.0.8** + Pinia 3.0.4 + axios 1.17，78 文件 / 15,136 行，dist 2.8 MB | `du -sh dist`；`package.json` devDeps |
| 后端 | Spring Boot **4.0.6**，216 文件 / 30,452 行，19 Controller / 41 Service / 7 Mapper | `pom.xml:8`；`find` 统计 |
| 运行时 | 编译目标 `java.version=17`，**实际跑 JDK 25.0.4.1 LTS** | `pom.xml:30`；`docker exec … java -version` |
| 聚合层 | musicapi（Express 4.18.2），12 文件 / 4,044 行，**生产跑 Node 20.19.6** | `docker exec … node -v` |
| 原生 | Kotlin 2.0.20 + Compose BOM 2024.10 + Media3 1.9.3，56 kt / 20,443 行 + 82 测试文件 | `libs.versions.toml` |
| 数据库 | **MySQL 8.0.27，镜像构建于 2021-12-21**（4 年前） | `docker image inspect mysql:8.0` → `2021-12-21T02:56:09`；`SELECT VERSION()` → `8.0.27` |
| 缓存 | Redis 7.4.11，maxmemory 96 MB / allkeys-lru，**AOF on** | `redis-cli info` |
| 宿主 | 阿里云 ECS **2 核 / 1,608 MB / 40 GB（49%）/ swap 4 GB（已用 521 MB）** | `free -m`；`df -h`；`cat /proc/loadavg` |
| 编排 | 生产 **4 容器**（backend 768M、musicapi 128M、mysql 768M、redis 128M）；仓库 12 服务 compose **未部署** | `docker stats`；`docker-compose.yml` |
| 部署 | GHCR 镜像 + 蓝绿（8082↔upstream 文件切换）；**`/opt/vibeMusic` 不是 git 仓库** | `docker inspect` upstream；`[ -d .git ]` → 否 |
| 流量 | 15 小时 **512 请求**（≈0.01 rps），UA 首位 `okhttp/4.12.0`（Android 客户端 244 次） | `/var/log/nginx/access.log` 全量统计 |

**入口链路真实形态（与仓库文档对照后修正）**：

```
用户 → Cloudflare 边缘(AMS) --加密 Tunnel--> cloudflared(宿主) → nginx:8081(loopback)
                                                        ├─ 静态 /var/www/vibemusic
                                                        └─ proxy → 127.0.0.1:8082 (backend, 蓝绿)
```

源站**完全没有对外监听 443**，也不暴露公网 IP 的 80（除博客 `cyk666.top`）。这是比"源站开 443 + CF Full(Strict)"更省心的做法——免证书续期、免回源 IP 暴露。

---

## 2. 架构与选型：哪里做对了（先说对的，避免改错方向）

| 做法 | 行业对照 | 评价 |
|---|---|---|
| Cloudflare Tunnel 而非公网 443 | 大量同类自建站还在裸奔 443 + 手动 certbot | **优于行业平均**：零证书运维、零回源暴露 |
| 蓝绿 + upstream 单文件切换 + 健康检查 | 多数个人项目直接 `docker compose up -d` 硬重启 | **优于行业平均**：实测 66 秒零停机切换 |
| 备份带「假成功」防护（`pipefail` + 校验 `Dump completed` 尾部标记） | 大多数自建备份只 `mysqldump > x.sql`，失败静默 | **优于行业平均**，注释里写清修的是哪个事故 |
| watchdog（unhealthy 超 6 分钟才重启，避开预热期） | 常见做法是每分钟无脑 restart，会打断启动 | 合理，有边界判断 |
| 告警边沿去重 + 30 分钟重提醒 | 多数自建告警是每分钟刷屏 | 合理 |
| 4 层测试基线（后端 828 / 网关 212 / 前端 229 / 原生 939）+ JaCoCo 60-70% 门禁 + `vue-tsc` 硬门禁 | 单人项目常见是 0 测试 | **显著优于行业平均** |
| 3 个机器守卫（网关测试清单 / TZ 契约 / media3 `minCompileSdk`） | 极少见 | **罕见**，且每条都对应一次真实事故 |
| 契约测试（pytest 打真实后端 + `contract/`） | 中大型团队才做 | 超出体量 |

**判断**：不要因为"个人项目"就怀疑架构。这里真正欠缺的不是设计能力，是**把已有能力接到位**（Prometheus 装了没采、告警只覆盖存活不覆盖业务、备份做了没异地）。

---

## 3. 服务器适配度：数据说话，别升级

### 3.1 实测占用（`docker stats --no-stream`）

| 容器 | 内存 | 限额 | 占比 | CPU | 重启 | OOM |
|---|---|---|---|---|---|---|
| backend | 331.2 MiB | 768 MiB | 43.1% | 0.19% | 0 | 否 |
| musicapi | 72.5 MiB | 128 MiB | **56.6%** | 0.37% | 0 | 否 |
| mysql | 78.2 MiB | 768 MiB | 10.2% | 0.19% | 0 | 否 |
| redis | 10.1 MiB | 128 MiB | 7.9% | 0.56% | 0 | 否 |
| **合计** | **≈492 MiB** | — | — | — | — | — |

宿主：`total 1608M / used 766M / avail 663M`，**swap 已用 521 MB**，负载 `0.19 0.20 0.17`。

### 3.2 结论

1. **CPU 是巨大余量**（0.19 / 2 核）。当前流量 0.01 rps，理论容量余量 **1000 倍以上**。
2. **内存是唯一真实约束**：492 MiB 容器 + 宿主进程（dockerd 60 MB、containerd 34 MB、`mysqld` 进程另计）→ 1.6 GB 已接近用满，靠 521 MB swap 兜着。
3. **`musicapi` 56.6% 是最紧的一个**（72.5/128 MiB）。128 MB 限额对 Express + 大 JSON 代理偏小，遇到大歌单响应有 OOM 风险。
4. **MySQL 128 MB buffer pool / 196 MB 数据** → 数据基本全在内存，是正确配置，不需要调大。
5. **升级配置不解决问题**：瓶颈是"1.6 GB 装 4 个容器"这个比例，不是绝对性能。真需要余量时，把 musicapi 限额从 128 → 256 MB（宿主有 663 MB avail + 4 GB swap）比换机器便宜 100 倍。

### 3.3 该做的不是扩容，是"管住余量"

- swap 已用 521 MB，但**告警阈值是 80%**（`health-alert.sh:SWAP_MAX=80`）→ 3.2 GB 才告警，实际已经悄悄换了 521 MB 没人知道。**阈值应改 25-30%**。
- `/var/log/journal` **233 MB 未限额**（`journalctl --disk-usage`）→ 应设 `SystemMaxUse=200M`。
- 容器日志：`docker info --format '{{.LoggingDriver}}'` → 默认 `json-file`，未见 `max-size` 配置。

---

## 4. 可靠性与容灾

### 4.1 现状（实测）

| 项 | 事实 |
|---|---|
| 备份 | `backup-mysql.sh` 每日 03:30，`--single-transaction --quick --routines --triggers`，gzip + `gzip -t` + 尾部 `Dump completed` 校验，保留 14 天 |
| 备份产物 | **18 份**，最老 `vibemusic-20261012-0330.sql.gz`，日志连续 8 天 `[backup] ok` |
| 备份位置 | `/opt/vibeMusic/docker-data/backups/mysql` ← **与数据目录同盘** |
| 副本 | **无异地副本**（`/opt/vibeMusic/backups/` 只有 1 个 musicapi 源码包） |
| 数据冗余 | MySQL 单实例无副本；Redis AOF on（重启不丢） |
| 自愈 | `watchdog.sh` 每 5 分钟；`health-alert.sh` 每分钟 |
| 告警接收 | ServerChan key 存在；`/var/log/vibemusic-alert.log` 仅 **878 字节** |
| 恢复演练 | **从未做过**（无记录、无脚本） |

### 4.2 异地副本：已落地并在工作（**此处我第一版判断错了，记录更正**）

> ⚠️ **更正记录**：本文件初版写的是「备份与数据同盘、无异地副本，是最大单点风险」。
> 那是**只看云上就下的结论**——查了 `/opt/vibeMusic/backups/` 和 `~/backups`，漏了 `~/.dsh/backups/`。
> 实际项目早已实现拉取式异地备份。**这正是 `lessons/evidence-before-claim.md` 里「未取证不得作结论」的反面教材，保留在此以示警戒。**

**实际状态（实测）**：

| 项 | 事实 | 证据 |
|---|---|---|
| 异地副本机制 | `scripts/offsite-backup-pull.sh` + `docs/OFFSITE-BACKUP.md`，**拉取式**（本机主动拉，不新增第三方凭据） | 脚本头注释明确「远端命令白名单只有 `ls`/`sha256sum`/`cat`，严禁写操作」 |
| 调度 | 本机 crontab `40 9 * * *` | `crontab -l` |
| 最近一次 | 2026-10-26 09:40，`校验通过 : 大小 73828 bytes + gzip -t OK + sha256 与远端一致` | `~/.dsh/backups/offsite-cron.log` |
| 本地留存 | **4 份**，`--keep 7` 滚动 | `~/.dsh/backups/vibemusic/` |
| 恢复脚本 | `scripts/restore-mysql.sh` + `docs/RESTORE-DRILL.md`，带硬守卫（拒绝对 `vibemusic` 库恢复、剥离 `DROP TABLE`/`USE`、默认只进一次性容器） | 脚本头「红线 1/2/3」 |

**这套设计质量高于行业平均**：拉取式避开了"云上往哪推"的凭据问题，且校验了 sha256（不只是"文件存在"）。

**但有两个真实缺口（这才是该修的）**：

1. **副本有断档**：8 天里只有 4 份（10-19、10-22、10-23、10-26），缺 10-20/21、10-24/25。
   原因就在 `docs/OFFSITE-BACKUP.md` 自己写的缺点——「**需要本机在跑**（WSL 关机期间不拉）」。
   这不是设计错，是**没有"漏拉告警"**：拉失败/没拉，云上没人知道，本机也没人知道。
   → 修法（20 分钟）：`offsite-backup-pull.sh` 增加「距上一份副本超过 36 小时 → ServerChan 告警」，或在云上的 `health-alert.sh` 里加一条「检查本机是否在 N 小时内拉取过」（云上有 SSH 能力，反向探）。
2. **恢复演练无执行记录**：`docs/RESTORE-DRILL.md` 只有「本机一分钟自测」（语法门 + 守卫拒绝测试），**没有用真实 dump 跑过一次完整恢复的记录**。
   → 修法：合并进 MySQL 8.4 升级那一次（§14 候选 3），一次动作两个目的，不额外花时间。

**用户已明确：备份"足够用就行，不做多级冗余"。** 所以本文件不再建议加月备/多副本/对象存储——
现有「云上日备 + 本机拉取 + sha256 校验 + keep 7」已经是合适的档位，**只补两个缺口：漏拉告警 + 一次真实恢复演练**。

### 4.3 未验证项（必须承认）

- **备份能否恢复？未知。** 从未在临时库上 `zcat | mysql` 试过。校验只证明"文件完整"，不证明"能恢复"。
- **RTO/RPO 未定义。** 现在按实际情况推：RPO ≤ 24h（日备）、RTO 未知（要重装机器 + 拉镜像 + 恢复库，粗估 1-2 小时，含 ghcr 拉取慢的问题）。

---

## 5. 安全与合规（按风险排序）

| # | 问题 | 证据 | 风险 |
|---|---|---|---|
| 1 | **25 个 source map 公开可访问，1.6 MB 占 dist 57%，且含完整原始源码** | 源站：`ls /var/www/vibemusic/assets/*.map \| wc -l` → 25；`du -ch *.map` → 1.6M。**公网实测**：`curl https://vibe.cyk666.top/assets/ChatView-C5KrxvEu.js.map` → **200 / 18,592 B**，内容为 `{"version":3,…,"sources":["../../src/views/ChatView.vue"],"sourcesContent":["<script setup>…"]}`——`sourcesContent` 意味着**原始 `.vue` 源码全文可取**，不只是映射表 | **高**：前端全部业务逻辑（含内部 API 路径、参数结构、未公开页面）对外公开；对照 `nope.js.map` → 404，排除"通配兜底"误判 |
| 2 | **MySQL 镜像 4 年未更新（8.0.27 / 2021-12-21）** | `docker image inspect` | 高：4 年累积的已修复漏洞全部在位 |
| 3 | **无异地备份**（见 §4.2） | 同上 | 高 |
| 4 | **系统待重启 + 29 个可升级包** | `[ -f /var/run/reboot-required ]` → 是；`apt list --upgradable` → 29 | 中高：内核/OpenSSL 类补丁可能未生效 |
| 5 | **无 HSTS** | `curl -sI https://vibe.cyk666.top` 无 `strict-transport-security` | 中：首次访问可被降级（Tunnel 加密了 CF↔源站，但 CF↔浏览器首跳没强制） |
| 6 | **无 CSP** | `security.conf` 只有 XFO/nosniff/Referrer-Policy/X-XSS-Protection（后者已废弃） | 中：XSS 无第二道防线 |
| 7 | **MySQL `root@'%'` 存在** | `SELECT user,host FROM mysql.user` → `root / %` | 中：应用应使用最小权限账号，当前用 root 连库 |
| 8 | **无依赖/漏洞扫描**：无 dependabot、无 codeql、无 trivy/grype、CI 无 `npm audit` / OWASP dependency-check | `ls .github/dependabot.yml .github/workflows/codeql*` → 无 | 中：新 CVE 不会有人告诉你 |
| 9 | **`blog.service` 是遗留 unit**（不是故障） | `systemctl --failed` 显示 failed since 2026-09-06，`status=143`（SIGTERM，人为停止），且 unit 为 `disabled`。该 unit 跑 Next.js 在 :3100，而 `cyk666.top` 实际由 `/var/www/testweb-dist-new`（Astro，`/_astro/` 路径为证）提供服务 | **低**：功能已被 Astro 版取代，属该删的残留；建议 `systemctl mask blog` 并删 unit，避免它长期占着 `systemctl --failed` 造成告警疲劳 |
| 10 | **限流用 503 而非 429**（已修）；且 `vibe.cyk666.top` 这个 server **完全没有 nginx 层限流** | 8 次 503 全部来自扫描器探测（`/.env.backup`、`/service-account.json`、`/local_settings.py`、`/web.config`），但**命中的是 `cyk666.top`（博客）vhost**——`limit_req`/`limit_conn` 只写在 testweb 里。音乐站靠应用层 `RateLimitService`（Redis）兜 | 低：状态码已改 429；音乐站缺 nginx 层限流属设计选择（应用层有限流），但意味着**大流量扫描会直接打到 Spring**，值得后续在 `/api/` 加一条宽松的 `limit_req` |
| 11 | **CORS 白名单含旧域名** `https://www.vibemusic.abrdns.com` | `docker inspect` env `CORS_ORIGINS` | 低 |
| 12 | 未备案域名 | 已接受现状（不再讨论） | 合规事实 |

### 5.0 补充说明：sourcemap 的防护「意图正确、实现不完整」

`vibemusic-web/vite.config.*:121` 写着：

```js
sourcemap: 'hidden',  // 生成 sourcemap 但产物不含 //# sourceMappingURL，防源码泄露
```

**这个注释说明作者想到了风险，但 `hidden` 只做一件事：不往 `.js` 末尾写 `//# sourceMappingURL=` 注释。它不会阻止 `.map` 文件被 emit 到 `dist/assets/`，而 `dist/assets/` 是整目录部署的** → 文件仍然可访问，只是"没人告诉你 URL"。

实测证明这条防护无效：直接猜 `ChatView-C5KrxvEu.js.map` → 200，且 `sourcesContent` 里是 `.vue` 原文。

**正确做法二选一**：
- 构建侧：生产 `sourcemap: false`（简单，但失去错误堆栈还原能力）
- 部署侧：`sourcemap: 'hidden'` 保留 + nginx 加 `location ~ \.map$ { return 404; }`（推荐：产物留档可用于还原线上错误，但公网取不到）

### 5.1 做得对的安全项

- SSH：`permitrootlogin without-password` + `passwordauthentication no` + `pubkeyauthentication yes` ✅
- ufw active + fail2ban active ✅
- MySQL `local_infile=0`、`general_log=0`、`require_secure_transport=0`（内网可接受）
- 密码全走容器 env / 凭据库，仓库无明文 ✅
- `/uploads/` 的 `proxy_hide_header X-Frame-Options` 去重（避免 DENY vs SAMEORIGIN 双值冲突）——**这是有经验的人才会想到的坑**
- **Swagger 在生产被正确关闭**：`application-docker.yml:30-35` 两个 `enabled: false`。
  ⚠️ 审计陷阱记录：`curl https://vibe.cyk666.top/v3/api-docs` 返回 **200**，但 `content-type: text/html`，内容是 SPA 兜底 `index.html`（`location /` 的 `try_files … /index.html`），**不是真的 API 文档**。只看状态码会误判成「Swagger 公网泄露」——必须看 content-type 和首字节。对照：`/api/nonexistent-xyz` → 401 `application/json`（认证过滤器先于路由）。

### 5.2 版权（非技术风险，必须陈述）

聚合网易 / QQ / 酷狗 / 咪咕 4 源（代码里 `netease` 189 处、`qq` 110、`kugou` 47、`migu` 42）。个人自用不分发是当前定位；一旦面向他人提供服务，这是**最大的非技术风险**，优先级高于本文件所有技术项。已决定不改变定位，故只记录不展开。

---

## 6. 性能与成本

| 指标 | 实测 | 评价 |
|---|---|---|
| 公网 TTFB | 新建 1.1s / 复用 0.44-0.63s（CF 橙云绕美，AMS 边缘） | 已接受现状，不再讨论 |
| 源站处理 | nginx 日志**未记录 `$request_time`** → 无法测量源站耗时 | 缺陷：优化无从下手 |
| 单请求最大响应 | `/api/songs/stream` **9,428,158 B**（9.4 MB，网易源） | 音频全量经 backend + Tunnel 代理 |
| Range 支持 | 26 次 206 | ✅ 拖动进度可用 |
| 前端体积 | 2.8 MB（**去掉 map 后 1.2 MB**） | 入口 js 76 KB + 路由级 lazy 分割 ✅ 合理 |
| DB 性能 | `Slow_queries=0`（阈值 1s）、`Max_used_connections=6`（上限 50） | ✅ 毫无压力 |
| Redis 命中率 | hits 6,708 / misses 7,138 → **48.4%** | 偏低，值得看（`lyric` 前缀 582 个 key 是主体） |
| 带宽成本 | 音频 9.4 MB/首经 CF Tunnel | CF 免费版无限流量，但源站出站吃阿里云带宽 |

**结论**：性能不是当前问题（0.01 rps）。真要压测，得先补 `$request_time` 日志，否则连基线都没有。

---

## 7. 可观测性：装了但没接

| 组件 | 状态 |
|---|---|
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | **已引入**（`pom.xml:143,148`） |
| `/actuator/prometheus` 暴露 | **已配置**（`application-prod.yml:27` include `health,prometheus`） |
| Prometheus 采集 | **未部署**：9090 端口 0 监听、`docker ps -a` 里 0 个 prometheus/grafana 容器 |
| redis_exporter / mysqld_exporter | 未运行（9104/9121 均 0 监听） |
| 链路追踪 | 无 micrometer-tracing / OTel |
| 日志聚合 | 无（`docker logs` + `/var/log` 手工查） |
| 告警覆盖 | 只有容器存活 / 磁盘 85% / swap 80% —— **无业务指标** |

**这是一处典型的"能力已具备但没接线"**：代码侧全部就绪，只差把仓库里已有的 `monitoring/` 起起来（但 1.6 GB 内存装不下 Prometheus+Grafana，需要外置或换轻量方案）。

**已知的具体盲区**：musicapi 每 15 分钟打 `ERROR ❌ 网易云备 Cookie 异常（播放能力探针未通过 (not-playable)）`，24 小时 12 次，**没有任何接收方**。业务错误不进告警，等于没监控。

---

## 8. 交付流程（CI/CD）

### 8.1 现状

- CI 7 job：`backend`（Maven verify + JaCoCo）、`backend-jdk25`、`frontend`（vue-tsc 硬门禁 + vitest coverage）、`gateway`（node --test 15 文件 + 清单守卫）、`api-test`（pytest 打真实后端）、`infra-lint`（含 TZ 契约守卫）、`docker-build`
- 另有 2 个独立守卫 workflow：网关测试清单、`deps-guard`（media3 `minCompileSdk`）
- 发布：GHCR 镜像 → 蓝绿 → upstream 切换

### 8.2 缺陷

| 问题 | 证据 | 影响 |
|---|---|---|
| **CI 测试环境与生产运行时不一致** | CI 主 job 用 JDK 17（`pom.xml:30`），生产跑 JDK 25.0.4.1；覆盖率门禁只在 JDK 17 上跑 | 生产专属行为（JDK 21+ 的 GC/虚拟线程/类加载）不受覆盖率保护 |
| **musicapi Node 版本无人校验** | `package.json` `engines.node>=24`，生产 **v20.19.6**，CI `gateway` job 不校验 musicapi 运行时 | 已存在的漂移；升级 Node 24 时不会有任何测试告诉你哪里断了 |
| **`/opt/vibeMusic` 不是 git 仓库** | `[ -d .git ]` → 否 | 部署物来源不可追溯；线上改动无法 diff（本次评估就靠"线上文件 vs 仓库文件"逐字节比对才发现 nginx 配置一致——这是运气） |
| **无 dependabot / codeql / 镜像扫描** | 见 §5 #8 | 依赖腐化静默发生（MySQL 4 年就是活例） |
| **仓库有两套 nginx 配置，命名不区分用途** | `nginx/nginx.conf`（252 行，容器 1.27 版）vs `deploy/production/nginx/*`（线上真身，与宿主 **diff=0**）。**已核实不是死配置**：`scripts/reconcile-cloud.sh:99` 明确标注「影子配置：生产反代是宿主机 nginx（deploy/production/nginx），仓库版供 CI `nginx -t`」 | 低：不是缺陷，但改错文件的概率高；建议把 `nginx/` 改名 `nginx-ci-syntax-only/` 或在 README 顶部加一句醒目说明 |

### 8.3 做得对

- `deploy/production/nginx/nginx.conf` 与线上 **`diff` = 0 行**，`vibemusic-site.conf` **= 0 行**，cloudflared 配置 **= 0 行** → 这三处文档化程度是行业水准。`security.conf` 仅差一个行尾换行。
- 每个守卫脚本的注释都写清"防的是哪次事故"——这是长期可维护性的关键。

---

## 9. 代码规模与可维护性

### 9.1 硬约束违规（项目自定：单文件 ≤250 纯 LOC）

| 文件 | 纯 LOC | 超限倍数 |
|---|---|---|
| `vibemusic-native/…/MainActivity.kt` | **3,454** | **13.8×** |
| `vibemusic-native/…/VibeApi.kt` | **1,565** | **6.3×** |
| `vibemusic-native/…/DiscoverUi.kt` | 612 | 2.4× |
| `vibemusic-native/…/UiAtoms.kt` | 608 | 2.4× |
| `vibemusic-native/…/SettingsScreen.kt` | 598 | 2.4× |
| `vibemusic-native/…/SearchScreen.kt` | 509 | 2.0× |
| `vibeMusic-backend/…/SongSearchService.java` | 508 | 2.0× |
| `vibemusic-native/…/MineScreen.kt` | 470 | 1.9× |
| `vibemusic-native/…/PlaybackStateStore.kt` | 420 | 1.7× |
| `vibeMusic-backend/…/PlaylistService.java` | 311 | 1.2× |
| `vibeMusic-backend/…/NeteaseApiService.java` | 307 | 1.2× |

**后端整体健康**（216 文件只有 3 个超限，最大 2×）；**原生是重灾区**（12 个超限，最大 13.8×）。

### 9.2 为什么这是真问题（不是教条）

- `MainActivity.kt` 3,454 行意味着**播放、UI、生命周期、通知、权限、更新检查大概率都在一个文件里**。本项目已发生两次「通知/媒体按钮改动实装后又回滚」（README:74 立规"第三次不做"）——超大类正是这类回归的温床：改动 A 的副作用要读 3,400 行才能判断。
- `VibeApi.kt` 1,565 行 = 单一 API 客户端承载全部端点。任何接口签名变化都改同一文件 → 冲突面 100%。

### 9.3 建议的拆法（按收益排序）

1. `MainActivity` 先抽出 **播放器生命周期** 与 **通知/媒体会话** 两块（各约 300-500 行），行为零变更、纯移动
2. `VibeApi` 按域拆：`AuthApi` / `SongApi` / `PlaylistApi` / `SocialApi`
3. 后端 `SongSearchService` 508 行：搜索编排 vs 源适配分离（已有 `UpstreamBreaker`，说明适配层概念已存在）

---

## 10. 多端一致性

| 项 | Web | 原生 |
|---|---|---|
| 页面/功能 | 12 View + `views/mobile` 子目录 | 56 kt 文件 |
| 播放逻辑 | `stores/` (Pinia) | `PlaybackStateStore.kt` |
| API 客户端 | `src/api/` | `VibeApi.kt` (1,565 行) |
| 测试 | 229 例 | 939 例 |
| 版本展示 | 未发现 | 2.0.15-ai |

**问题**：搜索/播放/收藏/歌单逻辑在两端各实现一次，且**客户端侧无契约生成**。需要区分两件事：`contract/gateway.contract.json` 是 **musicapi 网关 → Java 后端** 的响应形状契约（由 `musicapi/test/contract.test.js` 在 CI 校验，做得好）；而 **Java 后端 → Web/原生客户端** 这一层没有生成式契约——`springdoc` 虽已引入（`pom.xml:128`）但**生产被显式关闭**（`application-docker.yml:30-35` `api-docs.enabled=false` / `swagger-ui.enabled=false`），属正确配置。当前规模下重复成本可接受，但接口一改就要同步两处，且原生端 939 例测试跑得慢（R8 + 82 测试文件）。

**判断**：这是"可接受的技术债"，不是 P0。真要投，优先做 **OpenAPI 契约生成**（后端已有完整注解体系，成本低），而不是抽共享业务层。

---

## 11. 优化路线（每条含证据 / 成本 / 收益 / 验收）

### P0 —— 本周，合计约 4-6 小时，全部低风险

| # | 动作 | 证据 | 成本 | 收益 | 验收 |
|---|---|---|---|---|---|
| 1 | **生产关闭 source map**（`vite build --sourcemap false` 或 nginx 拦 `\.map$`） | 25 个 `.map` 公开，1.6 MB | 20 min | 堵住前端源码公开 | `curl -I https://vibe.cyk666.top/assets/*.map` → 404；dist 从 2.8 MB → 1.2 MB |
| 2 | **加 HSTS + CSP(report-only 起步)** | 无 `strict-transport-security`；`security.conf` 11 行无 CSP | 40 min | 防降级 + XSS 第二道防线 | `curl -sI` 见两个头；浏览器 console 无 CSP 违规 |
| 3 | **异地副本补「漏拉告警」**（不要重建方案，已有机制够用） | 8 天只有 4 份副本（缺 10-20/21、10-24/25），拉失败无人知 | 20 min | 让断档可见 | 人为制造一次失败 → 收到 ServerChan 告警 |
| 4 | **MySQL 建专用账号 + 收回 `root@'%'`** | `mysql.user` 里 `root / %` | 30 min | 最小权限 | 应用用 `vibemusic` 账号连通；`root@'%'` 删除 |
| 5 | **MySQL 镜像 8.0.27 → 8.0.4x**（同 major 小版本，先备份） | 镜像构建 2021-12-21 | 60 min | 拿 4 年安全修复 | `SELECT VERSION()` 变化；全表 `count` 与备份一致 |
| 6 | **打系统补丁 + 重启** | 待重启=是，29 个可升级 | 30 min（含重启） | 内核/OpenSSL 补丁生效 | `[ -f /var/run/reboot-required ]` → 否 |
| 7 | **journal 限额 + 容器日志轮转** | journal 233 MB；logging driver 默认 json-file | 20 min | 防磁盘被日志吃光 | `journalctl --disk-usage` ≤ 200M；`docker inspect` 见 `max-size` |
| 8 | **限流返回 429 而非 503** + 扫描路径单独处理 | 8 次 503 全来自漏洞扫描器 | 20 min | 区分"被刷"和"服务挂了" | `curl` 超频 → 429；日志 5xx 只剩真故障 |
| 9 | **修 `blog.service`** | `systemctl --failed` | 30 min | 恢复功能 | `systemctl is-active blog` → active |
| 10 | **swap 告警阈值 80% → 30%** | 已用 521 MB / 4 GB 无人知 | 5 min | 提前发现内存压力 | 改 `health-alert.sh:SWAP_MAX=30` 并触发验证 |

### P1 —— 本月，合计约 2-4 天

| # | 动作 | 证据 | 成本 | 收益 |
|---|---|---|---|---|
| 11 | **可观测性接线或删装饰**：要么部署 Prometheus（内存不够 → 用 VictoriaMetrics 单二进制 / 或远端 push），要么删掉 `micrometer-registry-prometheus` 依赖 | 依赖在、端点开、无采集 | 0.5-1 天 | 从"没有指标"到"有指标" |
| 12 | **业务告警**：API 5xx 率、上游源成功率、Cookie 失效 → ServerChan | 网易 Cookie 24h 12 次 ERROR 无人知 | 0.5 天 | 业务故障可见 |
| 13 | **补 `$request_time` 到 nginx 日志** | 无法测源站耗时 | 20 min | 性能优化有基线 |
| 14 | **dependabot + trivy 镜像扫描 + `npm audit` 进 CI** | 无任何依赖扫描 | 0.5 天 | 依赖腐化提前发现（MySQL 4 年就是缺这个） |
| 15 | **musicapi Node 20 → 24**（对齐 `engines>=24`）+ 补 tzdata | 生产 v20.19.6；`TZ` 写了不生效 | 0.5 天 | 运行时对齐；时区隐患 |
| 16 | **恢复演练**：从最新备份恢复到临时库 | 从未验证过 | 0.5 天 | RTO 从"未知"变"已知" |
| 17 | **拆 `MainActivity.kt`（先抽播放器生命周期 + 通知）** | 3,454 行；该类改动已两次回滚 | 1-2 天 | 降低最高风险文件的回归面 |
| 18 | **删/标注死的 `nginx/nginx.conf`** | 两套配置易改错 | 10 min | 消除误改 |
| 19 | **`/opt/vibeMusic` 纳入 git**（哪怕只是 `git init` + 每日 commit） | 不是 git 仓库 | 30 min | 线上改动可追溯、可 diff |

### P2 —— 季度，按需

| # | 动作 | 说明 |
|---|---|---|
| 20 | nginx 1.18 → 1.24+（Ubuntu 24.04 或官方 stable） | 功能代差 7 个大版本；当前靠 Ubuntu 回填补丁 |
| 21 | 后端编译目标 Java 17 → 21/25 对齐运行时 | 消除"CI 在 17 上测、生产跑 25"的割裂 |
| 22 | OTel tracing | 当前单机单服务，收益低于成本，可缓 |
| 23 | 容量基线压测（k6/vegeta，50/200/500 并发） | 现在 0.01 rps，压测结论意义有限；等有真实增长再做 |
| 24 | OpenAPI 契约生成 → Web/原生共享 | 后端注解齐全，成本低收益中 |
| 25 | 拆 `VibeApi.kt` 按域分文件 | 1,565 行，接口一改全冲突 |

---

## 12. 技术栈生命周期风险表（官方核查，抓取日 2026-10-26）

> 来源纪律：每条只认官方页面 / 官方 Maven / 官方 release API。Android 站直连超时，改用官方中国镜像
> `developer.android.google.cn`（同一套内容、官方域名）。官方**没有**支持政策页面的项，明确标注「未找到官方声明」，
> 不用「一般认为」代替。

### 12.1 已经踩线或即将踩线的（按紧急度）

| 组件 | 我方 | 官方状态 | 关键日期 | 风险 |
|---|---|---|---|---|
| **MySQL** | 8.0.27（镜像 2021-12） | **已 EOL**：2026-04-21 起转入 Oracle Sustaining Support，**不再有常规补丁与安全修复** | EOL 2026-04-21；8.4 LTS Premier 至 2029-04 | **高** |
| **Spring Boot** | 4.0.6 | 4.0.x OSS 支持 **2026-12-31 到期（还剩约 2 个月）**；4.1 已 GA（当前 4.1.2）；4.0 线末补丁 4.0.9 | OSS 止 2026-12-31；商业支持至 2027-12-31 | **高** |
| **MyBatis-Plus** | `mybatis-plus-spring-boot3-starter` **3.5.9** | 官方对 Spring Boot 4 的支持**从 3.5.13 才开始**，且需专用 `mybatis-plus-spring-boot4-starter`（当前 3.5.18） | SB4 支持自 3.5.13 | **高**（现用 SB3 starter 跑在 SB4 上，官方矩阵外） |
| **Node.js（musicapi 生产）** | **20.19.6** | 20 已 **EOL**（2026-04-30）；24 为 Active LTS→Maintenance | 20 EOL 2026-04-30；24 至 2028-04-30 | **高** |
| **AGP** | 8.5.2 + compileSdk **35** | AGP 8.5 官方写明「maximum API level … is **API level 34**」；API 35 自 AGP 8.6 起才被声明支持。8.5 是 Past release，末版 8.5.2，当前稳定 9.4.1 | — | **中高**（构建能用，但在官方矩阵外，出问题官方不认） |
| **nginx（生产）** | **1.18.0**（Ubuntu 22.04 自带，2020） | 1.27 分支已 EOL（末版 1.27.5 / 2025-04-16）；当前 stable 1.30.6、mainline 1.31.8。1.18 更老，仅靠 Ubuntu 回填补丁 | 1.27 末版 2025-04-16 | 中 |
| **Vite** | **8.0.16**（installed） | 官方支持线：8.3 常规、7.3/8.2 重要修复、6.4/8.1 仅安全补丁；**更早版本「no longer supported」→ 8.0.x 不在支持线内** | 当前 8.3.4 | 中 |
| **Kotlin / KGP** | 2.0.20 + Gradle 8.10.2 | 官方兼容矩阵：2.0.20 对应 Gradle **6.8.3–8.8**、AGP 7.1.3–8.5；8.7–8.10 仅脚注「可用但有告警」。当前 Kotlin 2.4.21 | 2.0.20 = 2024-08-22 | 中 |
| **Compose BOM** | 2024.10.00 | 最新 **2026.09.00**，我方落后约 23 个月 / ~38 个 BOM 版本 | — | 中 |
| **Media3** | 1.9.3 | 1.9 线**还有 1.9.4**（2026-05-16）后停更；当前稳定 1.11.1 | 1.9.4 = 2026-05-16 | 中（落后一个补丁） |

### 12.2 健康或影响可控的

| 组件 | 我方 | 官方状态 | 关键日期 | 风险 |
|---|---|---|---|---|
| Java（运行时） | 25.0.4.1 LTS | Premier 至 2030-09 / Extended 2033-09；Spring Boot 4.0/4.1 最低 17、兼容至 27 | — | 低 |
| Java（编译目标） | 17 | 同上（17 已非 LTS 主线但仍在支持） | — | 中（与运行时不一致，见 §8.2） |
| Redis | 7.4.11 | 仍在维护（7.4.11、7.2.16 均 2026-08-17）；主线 8.10.2。**官方无 EOL 政策页面** | — | 低 |
| Vue | 3.5.32 | 活跃开发，非 maintenance（3.5.44 / 2026-10-13；3.6 RC 中）。Vue 2 已 EOL 2023-12-31 | — | 低 |
| Vitest | 4.1.10 | 4.x 仍发补丁（4.1.12），当前 5.0.5。**官方无支持政策** | — | 低 |
| Gradle | 8.10.2 | 8.x 线仍维护（8.14.6 / 2026-10-08），最新 9.8.2。**官方无 EOL 政策** | — | 中 |
| Cloudflare 免费版 | Free + 橙云 | 官方可证：免费版**不强制橙云**（只是 onboarding 默认开）；中国大陆节点是 **Enterprise 专属独立订阅且要求 ICP 备案**（未备案用不了）；Tiered Cache 免费版可用，Argo 付费 | — | 中（性能，非合规） |
| GitHub Actions | Free 个人账号 | 2,000 min/月（**仅 private repo 计费；public repo 标准 runner 完全免费**） | — | 低 |

### 12.3 一条需要澄清的「看似严重、实际不适用」

**Google Play 的 targetSdk 要求**：官方自 **2026-08-31** 起要求新应用与更新必须 target **API 36**，延期窗口至 2026-11-01。我方 targetSdk 35，**按规则已无法提交更新**。

但——**本项目不发 Google Play**：全仓库无 `com.android.vending` / supply / fastlane / publishing 配置，分发走 GitHub Releases（`v2.0.15-ai` 实测）。所以**这条不构成阻塞**，只是「哪天想上架就必须先升到 36」的既知条件。不要被"严重"标签误导去做一次高风险的 targetSdk 36 迁移（会强制 edge-to-edge、默认 predictive back、大屏忽略方向限制，是独立工程）。

### 12.4 由此产生的两个新行动项

| 动作 | 依据 | 成本 | 窗口 |
|---|---|---|---|
| **Spring Boot 4.0.6 → 4.0.9**（先吃满 4.0 线补丁），再评估 4.0 → 4.1 | 4.0 OSS 2026-12-31 到期 | 半天（改版本号 + 全量测试） | **必须在 12-31 前完成 4.1 迁移**，否则 OSS 补丁断供 |
| **MyBatis-Plus 换 `mybatis-plus-spring-boot4-starter` ≥ 3.5.13** | 官方 SB4 支持自 3.5.13 起，现用 SB3 starter 3.5.9 | 半天（换 artifact + 回归） | 与 Spring Boot 升级同批做，一次回归 |

## 13. 一句话行动建议

**先做 P0 的 1/2/3/5 四条**（关 source map、加 HSTS+CSP、备份异地、MySQL 升级）——合计约 2.5 小时，消掉全部"高"风险项。
**不要做的事**：不要升级服务器配置（CPU 余量 1000 倍）、不要上 K8s/微服务（0.01 rps）、不要重写前端（代码质量在线）、不要动通知/媒体按钮路径（已两次回滚，README:74 立规）。

---

## 14. 如果接受「颠覆性改动」：真正值得动的只有 3 个，另有 2 个明确劝退

前提：项目当前 **0.01 rps、5 个用户、2 核 1.6 GB**。所以判断标准不是"行业流行什么"，而是
**「改完之后，未来每一次改动的成本是否下降」**。按这个标准，能称得上颠覆的只有下面三个。

### ✅ 候选 1（最高性价比）：把 `MainActivity.kt` 3,454 行拆掉 + OpenAPI 生成客户端

**为什么这是"颠覆"**：它不是重构，是**改变可维护性斜率**。

- `MainActivity.kt` 3,454 纯 LOC（超自定上限 13.8×），`VibeApi.kt` 1,565 行手写客户端。
- 证据：通知/媒体按钮路径**已两次实装两次回滚**（`README.md:74` 立规"第三次不做"）。超大类是这类回归的温床——改 A 的副作用要读 3,400 行才能判断。
- `VibeApi.kt` 1,565 行 = 全部端点在一个文件，任何接口签名变化冲突面 100%。

**做法（两件事互相放大）**：
1. 从后端已有注解生成 OpenAPI（`springdoc` 已引入，生产关掉即可）→ 生成 Kotlin client，**替换手写的 1,565 行**；同时生成 TS client 替换 Web 的 `src/api/`
2. `MainActivity` 按"播放器生命周期 / 通知与媒体会话 / 权限 / 更新检查"切块，每块 300-500 行，**纯移动、零行为变更**，939 例测试兜底

**成本**：4-6 天（1 天 OpenAPI + 生成器，3-5 天拆 MainActivity）
**收益**：接口变更从"运行期 404"变成"编译期报错"；后续播放/通知改动回归面下降一个数量级
**风险**：低（有测试网；拆分类不改变行为）
**验收**：`VibeApi.kt` 消失；`MainActivity.kt` ≤ 400 纯 LOC；939 例全绿；`lintRelease` 通过；APK 功能与 2.0.15-ai 等价

---

### ✅ 候选 2（唯一真正的架构颠覆）：把音频流量从服务端剥掉

**现状证据**：
- `/api/songs/stream` 单次响应最大 **9,428,158 B（9.4 MB）**，全量经 `backend → nginx → Cloudflare Tunnel`
- backend 常驻 **331 MiB**（占 768 MB 限额 43%）；**未验证**其中流式代理缓冲占多少（要验证需在压测时对比 `jvm_memory_used_bytes` 与 `http_server_requests` 的并发，当前流量太低测不出）
- Range 已支持（26 次 206），但代理层要维护 `Range` 透传 + 源站差异

**方案**：backend 不再转发字节流，改为返回**短时效签名直链**，客户端直连源站/CDN。
- 服务端职责从"搬字节"变成"发凭证"
- Range / 断点续传 / 边下边播交给源站和客户端

**成本**：3-5 天（backend 签名 + Web 播放器 + 原生 ExoPlayer 三端改造）
**收益**：backend 内存与出站带宽骤降；起播延迟降（少一跳）；Tunnel 不再是音频单点
**风险**：**中高**——直链一旦泄露等于绕过鉴权；4 个源（网易/QQ/酷狗/咪咕）的直链时效、CORS、Range 行为不一致；需要逐源验证
**判断**：**只有在"要做多用户 / 带宽成为真实成本"时才值得**。当前 0.01 rps，收益主要是"架构更正确"，不是"现在更省"。如果你要的是"行业级架构"，这是唯一一条；如果你要的是"现在更稳"，P0 就够了。

---

### ✅ 候选 3（不是颠覆，但必做且会被误当颠覆）：MySQL 8.0.27 → 8.4 LTS

- 现状：镜像构建于 **2021-12-21**，服务端 `8.0.27`，4 年未更新
- 8.4 是 LTS 线，长期支持；但**升级不是改 tag**：`mysql_native_password` 默认关闭、`--default-authentication-plugin` 参数移除、部分 8.0 语法废弃
- 成本：1 天（备份 → 起 8.4 临时实例 → 导入 → 校验行数/校验和 → 切 → 保留回滚）
- **这一步顺便做恢复演练**（§4.3 说"从未验证过备份能恢复"），一举两得

---

### ❌ 明确劝退 1：把 musicapi（4k 行 Node）重写进 Java，消灭第 4 个容器

**看起来很美**：4 容器 → 3，省 128 MB，消除 Node 20/24 版本漂移和 UTC 时区问题，CI 少一个 job。

**为什么不做**：
- musicapi 那 4,044 行是**逆向来的源协议实现**（网易/QQ/酷狗/咪咕的加密、签名、cookie 体系），细节密度极高，不是"业务逻辑"而是"协议兼容层"
- 212 例网关测试是围绕这些协议写的，重写等于把测试网拆了重建
- 重写风险 ≈ 重新踩一遍所有源协议坑，而收益只是 128 MB 内存 + 一个 job
- **改一个正在工作的协议层，是这类项目最容易"改完更差"的操作**

**替代方案（成本 1 天，收益 80%）**：不重写，只做三件事——① 升级 Node 20 → 24 对齐 `engines`；② 补 `tzdata` 让 `TZ` 真生效；③ 加一个 CI job 断言 `node -v` 与 `engines.node` 一致（机器守卫，同 TZ 守卫思路）。

---

### ❌ 明确劝退 2：迁到 Cloudflare Workers / D1 全托管

**看起来很美**：零运维、全球边缘、免费额度、连 Tunnel 都省了。

**为什么不做**：
- 70,000 行代码 + 2,208 例测试**全部作废**，等于从零开始
- 这个项目最大的资产不是架构，是**测试基线 + 三次事故换来的运维经验**（TZ 事故、备份假成功、蓝绿窗口）。迁平台会把这两样一起丢掉
- Workers 不支持长连接流式代理（音频场景的核心），D1 是 SQLite 语义，MyBatis-Plus 生态全废
- **"为了新而新"是颠覆性改动最常见的死法**

---

### 结论：如果只做一件事

**做候选 1。** 它不改变架构、不动数据、不碰播放路径（避开已两次回滚的雷区），却把项目未来所有改动的成本砍一半。
候选 2 留到"带宽或多用户成为真实问题"再启动——那时候做它才有意义，现在做只是提前承担风险。

---

## 15. P0 执行记录（2026-10-26 当天已完成项）

| # | 动作 | 结果 | 验证证据 |
|---|---|---|---|
| 1 | **生产关闭 sourcemap + 删除已部署的 25 个 `.map`** | `vibemusic-web/vite.config.js` `sourcemap: 'hidden'` → `false`（附注释说明 `hidden` 为什么无效）；线上 `rm` 25 个文件（先打包到 `/root/vibemusic-map-backup-20261026-1543.tar.gz`，416K / 25 条目可回滚） | dist **2.8M → 1.2M**；源站 `/assets/ChatView-C5KrxvEu.js.map` → **404**；**公网复验** `https://vibe.cyk666.top/assets/*.map` → **404**，而 `/` 与 `/assets/index-DWax95Jj.js` 仍 **200** |
| 2 | **加 HSTS** | `security.conf` 加 `Strict-Transport-Security: max-age=31536000; includeSubDomains`；**并在 5 个自带 `add_header` 的 location 里逐一补**（避开 nginx `add_header` 不继承的坑） | `nginx -t` 通过；reload 后源站实测 `/`、`/index.html`、`/manifest.json`、`/assets/*.js`、`/api/songs/history` **全部带 HSTS**；**公网复验** `https://vibe.cyk666.top` 响应头含 HSTS |
| 3 | **限流状态码 503 → 429** | `security.conf` 加 `limit_req_status 429; limit_conn_status 429;` | 配置已生效（`nginx -t` 通过、reload 完成） |
| 4 | **journal 限额** | `/etc/systemd/journald.conf.d/99-size.conf`：`SystemMaxUse=200M`、`SystemMaxFileSize=50M`；重启 journald | 配置落盘并生效（当前归档 232 MB，后续按上限滚动淘汰） |
| 5 | **swap 告警阈值 80% → 30%** | `health-alert.sh:SWAP_MAX=30`（原值等于永不告警） | `bash -n` 语法通过；`grep` 确认新值 |

**改动前的备份（全部可回滚）**：
- `/root/security.conf.bak-20261026-1543`
- `/root/vibemusic-site.bak-20261026-1543`
- `/root/health-alert.sh.bak-20261026-1543`
- `/root/vibemusic-map-backup-20261026-1543.tar.gz`

**尚未执行（需单独排期，因涉及停机/重启）**：MySQL 8.0.27 → 8.4 LTS、系统补丁 + 重启、docker 日志轮转（需重建容器）、Spring Boot 4.0 → 4.1 迁移。

### 15.1 接手续做记录（2026-10-26 17:00 前后）

| # | 动作 | 结果 | 验证证据 |
|---|---|---|---|
| P0-4 | **MySQL 专用账号 + 收回 `root@'%'`** | 新建 `vibemusic@'%'`，仅授权 `vibemusic.*` 的 DML/DDL（Flyway 需要）；backend 以 `DB_USERNAME=vibemusic` 蓝绿发布 | 容器内 `DB_USERNAME=vibemusic`；`processlist` 显示 vibemusic 连接；`mysqldump` 探针 `dump_ok`；`mysql.user` 仅剩 `root@localhost` |
| P0-9 | **修 `blog.service`** | `systemctl start` + `enable`（此前 failed 1 个多月，端口 3100 无监听） | `active (running)`、`enabled`、`curl 127.0.0.1:3100` → 200 |
| P0-5 | 核对：MySQL 已是 **8.4.13**（8.0.27 已升级） | 无需重复执行 | `SELECT VERSION()` = 8.4.13 |
| P0-7 | 核对：容器日志轮转已生效 | `max-size=20m / max-file=3` | `docker inspect` LogConfig |
| P0-6 | **系统补丁生效（重启）** | `reboot` 完成，重启前确认四容器 `unless-stopped`、nginx/docker enabled | 重启后 `reboot-required` 消失；四容器全部 healthy（backend 60s）；`search=200`、HSTS 在、`.map=404`、JVM 时区 CST |

**回滚点**：backend `rollback-20261026-165854`；DB 专用账号口令保存在运维凭据文件（600 权限、不入库，路径见 RUNBOOK）。

**至此 P0 清单 1-10 全部完成。**

### 15.2 P1 低风险项执行记录（2026-10-26 17:26 起）

| # | 动作 | 结果 | 验证证据 |
|---|---|---|---|
| P1-13 | **nginx 日志补 `$request_time`** | `nginx.conf` 新增 `main_timed` 格式（`rt=`/`urt=`），全局 access_log 切换；改前备份 `/root/nginx.conf.bak-20261026172648` | `nginx -t` 通过、reload 成功；实测日志尾部 `rt=0.000 urt=-` |
| P1-15 | **musicapi 时区真生效** | alpine 无 tzdata，`apk` 源在本网络 TLS 不可达；改为镜像内置 `tzdata/Asia/Shanghai`（561B）+ `ENV TZ=Asia/Shanghai` | 新镜像 `vibemusic-musicapi:f433009` 上线；容器 `date` = **CST**；后端可达 `musicapi:3000/health` |
| P1-18 | **标注影子 nginx 配置** | `nginx/nginx.conf` 头部注明仅 compose demo/full 与 CI 使用，生产反代是宿主机 `/etc/nginx` | 文件头三行说明；引用关系见 `scripts/reconcile-cloud.sh` |
| P1-11 | **移除无采集方的 Prometheus** | 删 `micrometer-registry-prometheus` 与 `/actuator/prometheus` 暴露；保留 health/metrics/info（prod 仅 health） | `mvn -o verify` 828 全绿；部署 `1f34017` 后 `/actuator/prometheus` → **404**、`/actuator/health` → 200、搜索 200 |

**发布备注**：本次蓝绿时 available 仅 350MB（<400 门槛，blog 恢复后内存更紧），按脚本提示用 `--force --health-timeout 420`；healthy 66s，回滚镜像 `rollback-20261026-173816`。**建议后续把 blog 从常驻服务改为按需启动**（当前无任何 nginx/cloudflared 路由指向 3100，常驻约 25-128MB）。

**仍未做（P1）**：P1-12 业务告警（需定 ServerChan 事件口径）、P1-14 依赖扫描进 CI（dependabot/trivy）、P1-16 恢复演练（需临时库）、P1-17 拆 MainActivity（1-2 天）、P1-19 `/opt/vibeMusic` 入 git（含密钥，需先脱敏）。

### 15.3 P1-14 依赖扫描（已做一半）

- 新增 `.github/dependabot.yml`：web / musicapi（npm）、backend（maven）、两个 Dockerfile、GitHub Actions，
  每周一 09:00 Asia/Shanghai 开 PR，不自动合并；仅 GitHub 生效（Gitee 镜像不支持）。
- **未做**：trivy 镜像扫描与 `npm audit` 硬门禁——需先跑一轮基线，确认现有告警不会直接卡死 CI，再决定阈值。
