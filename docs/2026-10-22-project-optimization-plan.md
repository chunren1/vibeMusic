# vibeMusic 全栈优化方案（2026-10-22）

> 依据：5 路只读代码审计（后端 Java / Node 网关 / Vue 前端 / Android / 交付与基础设施）+ 生产环境实测基线 + 官方仓库版本核对。
> 原则：**"最好"不等于"技术栈最新"，而是"故障能发现、变更能回滚、成本可预期"。** 任何换语言/换框架的提案，必须先回答"省下什么、换来什么"，答不上来就不做。

---

## 0. 生产基线（做任何决策的前提）

| 项 | 实测值 | 含义 |
|---|---|---|
| 云主机 | 阿里云 2 vCPU / **1.6 GiB** / 40G 盘（19G 已用） | 内存是稀缺资源，不是 CPU |
| Swap | **已用 527 MB** / 4 GB | 已经发生换页，内存处于紧平衡 |
| backend (Java) | **356.4 MiB** / 768 MiB 限（46%） | 单进程吃掉整机 45% |
| musicapi (Node) | 86.5 MiB / 128 MiB 限（68%） | 已接近上限 |
| mysql / redis | 72 MiB / 10.5 MiB | 健康 |
| 日请求量 | 860（其中 `/stream` 150） | 吞吐完全不是瓶颈 |
| 公网暴露面 | 仅 22；80/443 由 cloudflared 隧道接入 | 安全面已经优于多数个人项目 |
| 后端质量 | 601 用例，JaCoCo **77.5% 行 / 65.2% 分支**，门禁 60% | 质量底子好，门禁偏低 |

**结论：本项目的主要矛盾是「1.6G 机器的资源纪律」＋「CI 到生产之间的门禁缺失」，不是语言性能。**

---

## 1. 五个模块的体检结论（各一句话）

| 模块 | 规模 | 一句话结论 |
|---|---|---|
| Java 后端 | 85 文件 / 9,955 行主代码 / 52 端点 | 架构与测试是五个模块里最健康的；问题是**超时预算反向**、**线程与容器资源不匹配**、**贵端点匿名无限流**、两个 God class |
| Node 网关 | 11 模块 / 3.1k 行（`search.js` 单文件 1,220 行） | 功能正确但缺**统一上游客户端**；一个 4 年停更依赖（`qq-music-api`）是唯一确定的供应链风险；运行时已 EOL |
| Vue 前端 | 73 文件 / 14.7k 行（Vue 3.5 + Vite 8 + Pinia） | 构建健康（首屏 81 KB gz）；问题是 **Sentry 是死代码**、**typecheck 门形同虚设**、**移动端后台播放断链**、1,643 行死代码 |
| Android | 56 Kotlin 文件 / 943 用例 | 合规与测试底子好；问题是 **MainActivity 3,780 行 121 个 remember**、**Media3 落后 7 个 minor 且缺 WAKE_LOCK**、**验签只打印不判定** |
| 交付/基础设施 | 2 条 workflow / 6 容器 | `deploy.yml` **不做部署**；27 条告警规则**没有投递通道**；备份脚本有**假成功**缺陷；云上 compose 与仓库不一致 |

---

## 2. 语言与框架决策（直接回答"有什么可以改"）

| 决策项 | 现状 | 结论 | 理由（量化） | 成本 |
|---|---|---|---|---|
| **Java 后端整体** | Spring Boot **4.0.6** / `java.version=17` | **不改语言，不"升 3.x"** | 4.0.6 已是最新主线；Jakarta 迁移已完成（`javax.servlet` 残留 0）。把 4.x 降回 3.x 是负收益 | 0 |
| **JDK** | 源码 17，测试实际跑 21 | **升 25 LTS + 开虚拟线程** | `/stream` 每听众占 1 平台线程，200 线程 × ~1MB ≈ 200MB native，在 768MB 容器里是硬顶；虚拟线程把栈降到 KB 级，并发从"线程数"变成"上游容量"约束 | 3 人日 |
| **Go** | 无 | **只用于「流代理」+「运维 CLI」** | 不是为性能，是为内存：Go 版流代理 RSS 约 20–40MB，可替 Java 分担最重的 I/O 路径；禁止扩张到搜索/聚合 | 3–4 人日（试点） |
| **Node** | `node:20.19-alpine`（**2026-04-30 已 EOL**） | **升 24 + 补 engines** | 无安全补丁的运行时；lru-cache 11 已支持 22/24 | 0.5 人日 |
| **Express** | 4.18 | **暂不升 5** | 收益仅"async 错误自动转发"，成本是通配路由语法（`/netease/*`→`/*splat`）与 query parser 变更 | 等 4.x EOL 再做 |
| **Fastify** | — | **不换** | 实测峰值 70 req/min，吞吐不是瓶颈 | 0 |
| **axios → undici** | axios 无 abort、无 `maxContentLength` | **合并进「统一上游客户端」一起做** | 只为一个目标：超时/abort/体积上限进同一层 | 1.5 人日（含在 P1-3） |
| **`qq-music-api`** | 1.1.2，**最后发布 2022-07-09** | **删除，30 行直连替代** | 拖入第二份 express 4.16.4 + axios 0.21.4 + jade + fs@0.0.1-security，2.6MB，只服务 1 个端点 | 0.5 人日 |
| **`NeteaseCloudMusicApi`** | 4.32.0，2026-05 仍在发版 | **保留 + 外包超时** | 价值在 weapi/eapi 加密，自研 ≥5 人日且随上游变 | 0.5 人日 |
| **Vue** | 3.5 + Vite 8，首屏 81 KB gz | **不迁移、不上 Nuxt/SSR** | 登录型播放器，SEO 收益近 0；Nuxt 迁移 15+ 人日 | 0 |
| **TypeScript（前端）** | `strict:false`、`strictNullChecks:false`、无 `.ts` 源文件 | **只开 `strictNullChecks` + api/stores 增量转 .ts** | 全量重写是伪需求；先让空值能报错 | 5–8 人日 |
| **TypeScript（网关）** | 纯 JS | **JSDoc + `tsc --checkJs`，不做全量 TS** | 5 平台 mapper 输出形状不一致、历史上"数组参数直接 .trim() → 500"都是类型可拦的整类缺陷 | 2 人日 |
| **Media3 / ExoPlayer** | 1.5.1 | **升 1.12.x** | 1.9.0 卡死检测（StuckPlayerException）、1.9.1 通知图标移出主线程、1.10.0 FGS 陈旧 Intent 修复——直接对上 vivo 通知键与熄屏断流两个悬案 | 3 人日 |
| **Compose / Kotlin / AGP** | Kotlin 2.0.20 / AGP 8.5.2 / BOM 2024.10 | **两段式升级**（先 AGP 8.13 + Gradle 8.13，再 AGP 9） | 直接跳 AGP 9 迁移量过大；两段式风险可控 | 3–5 人日 |
| **Hilt / KMP / 多 module** | 13 个手写单例 / 单 module | **都不做** | 56 文件规模，Hilt 只增构建时间；无 iOS 第二目标；瓶颈是状态不是编译 | 0 |
| **k8s / Helm** | — | **不做** | kubelet+控制面 1G+，会把这台 1.6G 机器上现有服务挤爆；当前问题不是编排弹性，是配置没有真源 | 0 |
| **Terraform** | — | **不做；改用"纳管 + 每日对账"** | 单机无弹性，TF 状态管理收益≈0；真正缺的是"云上配置进仓库 + 漂移告警" | 2 人日（对账） |

---

## 3. P0：止血（两周，约 9 人日）

> 判定标准：正在造成不可逆风险或系统性失效，且修复成本 < 2 人日/项。

### P0-1 超时预算对齐（后端，1.5 人日）
- **证据**：`RestTemplateConfig.java:39` 响应超时 **45s**，而搜索总预算 4s（`SongSearchService.java:175`）、取链 8s（`SongPlayService.java:263`）；`NeteaseApiConfig.java:20` 的 10s 配置**从未被使用**；`Future.cancel` 无法中断已发出的 HTTP。
- **后果**：一个慢上游能占住 search 线程 45s，池子只有个位数 → 这是 503 的机制性成因。
- **动作**：全局 connect 3s / response 5s；各平台按需覆盖；接入 `NeteaseApiConfig`。
- **验收**：注入 30s 慢上游，搜索 P99 < 4s 且无 503。

### P0-2 线程与容器资源重排（后端，1 人日）
- **证据**：容器 512MB/1 CPU（`docker-compose.yml:16-17`），Tomcat 用默认 200 线程（无任何 `server.tomcat` 配置）；search 池 20/40、Hikari 20、getUrl 池 3（`ThreadPoolConfig.java:21-36`）。
- **动作**：Tomcat `max-threads=40`、`accept-count=50`；search 8/12/32；Hikari 8–10；getUrl 池 3→8。
- **验收**：RSS 不升、`http_server_requests` 的 active 线程峰值 < 60。

### P0-3 贵端点限流（后端，1.5 人日）
- **证据**：`SecurityConfig.java:57-59` 对 `/api/songs/**` `permitAll`；`RateLimitService` 唯一调用点是 AI（`AssistantController.java:109`）。
- **后果**：搜索一次扇出 5 平台、`/stream` 可被当免费音频代理，是唯一的"单请求放大 5 倍成本"入口。
- **动作**：IP + 设备令牌桶：search 20/min、stream 60/min；超限返回 429 + `Retry-After`。
- **验收**：ab 压测 100 rps 时上游调用量线性受控，本地 CPU 不被拖死。

### P0-4 告警真正送达（基础设施，1 人日）
- **证据**：27 条规则存在（`docker-data/prometheus/alert-rules.yml`），但 `prometheus.yml:12-15` 的 `alerting:` **整段注释**，无 alertmanager → 告警只写日志。
- **动作**：起 alertmanager（或最小 webhook receiver 推到手机）+ 把 `SiteDown` 探测移出笔记本（现监控全跑在本机，笔记本一关全部失效）。
- **验收**：人为停 backend，**60 秒内**手机收到告警。

### P0-5 备份从"有脚本"变成"能恢复"（基础设施，1.5 人日）
- **证据**：① `scripts/backup-mysql.sh:5` 只有 `set -eu`，**无 pipefail** → mysqldump 失败时 gzip 仍退 0，截断的 dump 能通过 `-s`/`gzip -t`；② `docker-compose.yml:353,419` 的 `flock -w 3600 $$LOCK_FILE` **缺 `-c`** → 守卫恒通过；③ 异地拉取**没有 cron**（`crontab -l` 为空），现存仅 1 份 10-19 的副本。
- **动作**：加 `pipefail` + 校验 dump 尾部 `Dump completed`；改 `flock -w 3600 -c '...'`；装异地 cron；按 `docs/RESTORE-DRILL.md` **真跑一次恢复**并记录耗时。
- **验收**：恢复演练成功，RTO < 30 分钟，README 写明演练日期。

### P0-6 发版产物校验（1 人日）
- **证据**：App `release.yml:77-95` 只 `apksigner verify --print-certs | head -5`——**打印不比对**，用错证书照样发布；前端 dist 发布**无 md5 校验**（后端有）。
- **动作**：App 断言证书 SHA-256 == 固定值（另加 `lint` 门、versionCode 单调、targetSdk 校验）；前端发布复用后端模式（build → scp → md5 对 → `nginx -t` → reload）。
- **验收**：故意用错证书/改坏 dist，CI/脚本必须失败。

### P0-7 Sentry 真接入或删除（前端，0.5 人日）
- **证据**：`src/main.js:15` 读 `VITE_SENTRY_DSN`，而 `.env.production:8` 整行被注释；`dist/assets` 内 **0 个 sentry chunk** → 生产崩溃 0 可见性。
- **动作**：配 DSN + `release=$GIT_SHA` + `environment` + `@sentry/vite-plugin` 上传 sourcemap；若决定不接，删依赖别留死代码。
- **验收**：手动 throw 一次，Sentry 30 秒内可见、sourcemap 可读。

### P0-8 网关探针与限流键（网关，1 人日）
- **证据**：`cookie-probe.js:41,57` 裸调无超时的库（`util/request.js` 全文件无 timeout）→ 上游挂起则 15 分钟巡检永久卡死、cookie 状态不再更新；`rate-limiters.js:4-10` 在容器内后端经 172.x 调用、`trust proxy=false` → **100/min 退化为全局吞吐上限**（实测峰值 70 req/min，再高即 429 悬崖）。
- **动作**：探针包 `withTimeout + AbortSignal`；限流改用可信身份头（后端注入 userId/traceId），`/health`、`/metrics` 跳过。
- **验收**：模拟上游 hang，巡检仍在 20s 内完成；压到 150 r/min 不出现误伤 429。

---

## 4. P1：结构性改造（1–2 个月）

| 编号 | 事项 | 关键证据 | 动作 | 人日 |
|---|---|---|---|---|
| P1-1 | **JDK 25 + 虚拟线程** | 测试已在 JDK 21 跑；`/stream` 每听众 1 平台线程 | `Dockerfile:4` 换 base；`spring.threads.virtual.enabled=true`（仅请求线程）；JaCoCo 0.8.12→新版；自建池保留作 bulkhead | 3 |
| P1-2 | **云上配置纳管 + 每日对账** | 云上 compose/nginx 与仓库不一致（缺 nginx、多两个 exporter） | 云上文件逐字节入仓；`scripts/reconcile-cloud.sh` 挂 cron，漂移即推送；**禁止在云上编辑** | 3 |
| P1-3 | **网关统一上游客户端** | 15 处裸 `axios.get`、10 处重复 `validateStatus`、无 abort、无 `maxContentLength`、无 single-flight | 抽 `src/upstream.js`（超时/abort/体积上限/重试/降级日志/single-flight/single-flight） | 2.5 |
| P1-4 | **网关依赖与运行时** | `qq-music-api` 2022-07-09 停更；`node:20.19` EOL | 删依赖改 30 行直连；升 node 24；补 `engines` | 1 |
| P1-5 | **前端后台播放断链** | `useAudioBackground` 仅 `PlayerBar.vue:66` / `MPlayerView.vue:87` 调用，而移动端 `App.vue:53` 渲染 `MobileShell` | 提到 App/MobileShell 单例初始化；`playSongFromApi` await 后加 token 守卫 | 1.5 |
| P1-6 | **前端类型与死代码** | `tsconfig.check.json:9-11` 三个 strict=false；7 个 mobile 视图 0 引用（1,643 行）；`window.vibeFavIds` 绕过 pinia | 开 `strictNullChecks`；删死代码或移 `legacy/`；window 通道收口为 composable | 4 |
| P1-7 | **Android Media3 升级 + 唤醒锁** | media3 1.5.1；`AndroidManifest` **无 `WAKE_LOCK`** 且未 `setWakeMode` | 升 1.12.x（先只升版本跑通 943 例）；补权限 + `C.WAKE_MODE_LOCAL`；真机回归通知键 | 3 |
| P1-8 | **后端 God class 拆分** | `SongSearchService` 1020 行 / `SongPlayService` 672 / `RecommendService` 557 / `AssistantController` 490 | 按「平台适配 / 熔断 / 合并排序」「降级链 / 探测」「SSE 编排」拆；601 例是安全网 | 6 |
| P1-9 | **traceId 贯穿全链路** | `ThreadPoolConfig` 无 TaskDecorator；异步日志无法关联；上游异常只打 message 无堆栈 | 加 `MdcTaskDecorator`；`log.warn(..., e)`；向 musicapi 透传 `X-Trace-Id` | 0.5 |
| P1-10 | **契约测试** | 后端无 WireMock/Testcontainers；网关无上游契约；App 与后端契约靠手工 | 建 `contract/` + golden 用例，CI 跑；夜间跑一次真上游契约（打 tag 时） | 3 |
| P1-11 | **覆盖率与门禁对齐实测** | 后端门禁 60% vs 实测 77.5%；前端 `--coverage` 零阈值；网关 `npm test` 不存在 | 后端 70/60；前端 40/35 + include 加 views；网关加 `test` 脚本并修 `logger.js:52` 未 unref 的定时器 | 1.5 |
| P1-12 | **镜像不可变 + 云端不 build** | 业务镜像 `:latest`；`scripts/deploy-backend.sh:24` 在 1.6G 云上跑 `mvn package` | CI 产镜像推到 ACR/GHCR，tag=git sha；云端只 pull；回滚=改 tag | 2 |

---

## 5. P2：长期收益（按需排期）

- **Go 流代理试点**（3–4 人日）：`contract/` + 影子流量 + nginx 加权切流，Java 永远可回落；成功标志是 RSS < 50MB、错误率不高于 Java。
- **Go 运维 CLI**（2 人日）：备份校验/健康巡检/日志聚合，不进流量路径，零风险。
- **Android Baseline Profile**（2 人日）：冷启动典型收益 15–30%，需本地真机生成后入库。
- **网关 `checkJs`**（2 人日）：JSDoc + `tsc --noEmit --checkJs`，拦 mapper 形状不一致。
- **Flyway 校验**（1 人日）：`flyway repair` 固 checksum 后开 validate；CI 对空库跑一次 migrate。
- **清理无效配置**：Caffeine 声明但 0 个 `@Cacheable`（`CacheConfig.java:24-31`）；`media3-ui` 依赖零使用；两套 Prometheus 配置二选一。
- **指标语义修正**：`api_saved_rate` 与 `redis_hit_rate` 同值；补 per-platform 耗时/超时率/熔断状态。
- **前端 a11y**：64 个可点 `div` 无 role/tabindex、159 个 button 仅 7 个 aria-label、`user-scalable=no`。
- **入参校验**：21 个 `@RequestBody Map` 端点无 Bean Validation，先改歌单/收藏 8 个为 record + `@Valid`。

---

## 6. 明确「不做」清单（负收益或无收益）

| 项 | 为什么不做 |
|---|---|
| 把后端重写成 Go / 任何语言 | 9,955 行业务代码里大部分是上游协议与排序的隐性知识，重写=重踩；收益无法量化 |
| Spring Boot 4.x 降回 3.x | 现有 4.0.6 是最新主线，Jakarta 迁移已完成；这是降级 |
| k8s / Helm / Service Mesh | 2C/1.6G 单机，控制面自身吃 1G+；当前问题是配置没有真源 |
| Terraform | 单机无弹性，状态管理收益≈0；用"纳管 + 对账"替代 |
| Nuxt / SSR | 登录型播放器，SEO 收益≈0，迁移 15+ 人日 |
| Fastify 替换 Express | 实测峰值 70 req/min，吞吐不是瓶颈 |
| KMP / Hilt / 多 module（Android） | 56 文件、无 iOS 目标；只增构建时间 |
| 全量 TS 重写（前端/网关） | 伪需求；增量 + 严格空值即可覆盖主要缺陷类别 |
| 全自动 CD（自动部署到生产） | 在"配置真源 + 可回滚镜像"到位前，自动化只会把漂移自动化 |

---

## 7. 里程碑与验收

| 里程碑 | 内容 | 工期 | 验收口径（必须可测） |
|---|---|---|---|
| **M1 止血** | P0-1 … P0-8 | 2 周 | 停服 60s 内收到告警；恢复演练 < 30min；慢上游下搜索 P99 < 4s 且无 503；错证书发版必失败；Sentry 收到一条真实错误 |
| **M2 结构** | P1-1/2/3/4/7/11 | 4 周 | JDK25 下 RSS 不升且 swap 归零趋势；网关 429 误伤为 0；云端可"改 tag 回滚"< 5min；Android Media3 升级后 943 例全绿 + 真机通知键回归 |
| **M3 收敛** | P1-5/6/8/9/10/12 + P2 Go 试点 | 6 周 | 后台播放移动端可锁屏控制；死代码清零；契约测试覆盖 3 个核心端点；Go 流代理影子流量一致率 100%、RSS < 50MB |
| **M4 稳态** | 剩余 P2 | 按需 | 覆盖率门禁达标且连续 4 周不靠 `@Disabled`；Flyway validate 开启 |

**目标态指标（相对今日）**

| 指标 | 今天 | 目标 |
|---|---|---|
| 告警送达 | 0（无通道） | < 60s |
| 回滚耗时 | 手工，≥15min | < 5min（改镜像 tag） |
| 备份可恢复性 | 未验证 | 演练通过，RTO < 30min |
| 生产前端崩溃可见率 | 0% | 100% |
| 贵端点限流 | 无 | search 20/min、stream 60/min |
| 慢上游线程占用 | 最长 45s | ≤ 5s |
| backend 内存 | 356MB / swap 527MB | 不升，swap 趋零 |
| 发版产物校验 | App 无、前端无 | 三者全有 |

---

## 8. 风险与回滚

- 每项都遵循「先加门禁、再改代码」：P0-6 的产物校验必须早于 P1 任何发版动作。
- JDK 25 / Media3 / AGP 升级都属于"只升版本、不改逻辑"的第一步，943 / 601 / 前端测试是现成安全网；失败即回退版本号。
- 云上任何改动前先跑对账脚本，改动留 `/opt/vibeMusic/backups/`（**不要再用 `/tmp`，重启即丢**）。
- Go 试点失败可整服务摘除，nginx 一行切回 Java，无数据迁移。

---

## 9. 执行期实测补充（2026-10-22 夜间，M1 进行中）

执行 P0 时测到三个原方案未预估的事实，已按严重度并入后续里程碑：

| 事实 | 实测值 | 影响 | 归入 |
|---|---|---|---|
| **后端冷启动 156.8 秒** | `Started VibeMusicBackendApplication in 156.798 seconds` | 每次发版/重启 = **2.6 分钟不可用**；1.6G 机器 swap 527MB，类加载大量换页 | P1-1（JDK 25 + AppCDS + 堆上限/GC 日志收敛），并在 P1-12（云端不 build）后改为滚动替换 |
| **监控栈已停摆 3 周** | `monitor-prometheus` / `monitor-grafana` 均 `Exited (0) 3 weeks ago` | 27 条告警规则**三周来一次都没被评估过**——不是"没有出口"，是"入口也没跑" | 已由服务器侧 cron 看门狗临时兜底（健康/容器/磁盘/swap）；Prometheus 回迁云端列入 P1-2 |
| **备份恢复实测** | 8/8 表行数一致，导入 **1046 ms**，dump 70,914 B | RTO ≈ 1s 导入 + 3min 冷启动 | 已写入 P0-5 验收记录 |
| **停服告警实测** | stop → 手机收到 **55 秒** | 满足 <60s 目标 | P0-4 验收记录 |

### 9.1 冷启动专项（新增，优先级等同 P1-1）

按收益排序，逐条验证后再上线：

1. **AppCDS / 类数据共享**：把 156s 里占比最大的类加载换页消除，预期 30–50%（需 JDK 25 与一次训练运行）。
2. **`-XX:MaxRAMPercentage` 复核**：当前 60%（768M 容器 → 460M 堆），叠加 metaspace 160M + 直接内存 64M 已接近上限，swap 抖动由此产生。先测 50% 是否降低换页。
3. **`-Xlog:class+load` 关闭或降级**：现在每次启动写 classload 日志到 `/app/uploads/jfr/`，属额外 I/O；改为 `class+unload` 或采样。
4. **发版方式改造**：镜像不可变 + `docker compose up -d` 滚动替换（P1-12），把"停 2.6 分钟"变成"新容器 ready 后再切流量"。

验收口径：`Started ... in` 日志 < 60s，且启动期间 `vmstat` 的 `si/so` 显著下降。

---

## 10. P0 执行状态（2026-10-23，每项附真实证据）

| 编号 | 状态 | 关键证据 |
|---|---|---|
| P0-1 超时预算 | ✅ 完成 | `RestTemplateConfig` 4 个用途客户端（connect 3s / 默认 5s / 搜索 3s / 流 30s / AI 60s）；删死配置 `NeteaseApiConfig.timeout`；`mvn verify` 617 tests 绿 |
| P0-2 资源重排 | ✅ 完成 | Tomcat 40/50/200；搜索池 8-12-32、取链 8/8/16、预热 1/1/2；Hikari 8/2；`ThreadPoolConfigTest` 12 例绿（我独立复跑）；运行时 actuator 读数 `tomcat.threads.config.max=40`、`jdbc.connections.max=8` |
| P0-3 贵端点限流 | ✅ 完成（线上实测） | `RateLimitFilter`（只拦 `/api/songs/search` 20/min、`/api/songs/stream` 60/min）+ `RequestIdentity`（user > device > X-Real-IP > remoteAddr）+ 429 带 `Retry-After`；新增 14 例测试；**线上实测第 21 次起 429，`Retry-After: 40`，信封 `{code:429}`，Redis 键 `ratelimit:search:dev:<id>=25`** |
| P0-4 可用性告警 | ✅ 完成 | 云端 cron 看门狗；`docker stop` → **55 秒**手机收到 🔴，拉起后收到 🟢 |
| P0-5 备份可恢复 | ✅ 完成 | `pipefail` + 尾部 `Dump completed` + 体积下限；恢复演练 8/8 表、导入 1046 ms；异地 cron 09:40 |
| P0-6a App 验签 | ✅ 完成 | 证书 SHA-256 断言（fail-closed，固化指纹兜底）+ lint 门 + versionCode 单调；943 tests 绿 |
| P0-6b 前端发布 | ✅ 完成 | `scripts/deploy-frontend.sh`（默认 dry-run）+ `docs/deploy-frontend.md` |
| P0-7 错误上报 | ✅ 完成（线上实测） | 前端第一方 `clientErrorReporter.js`（174 tests 绿、dist sentry 归零）；后端 `POST /api/client-errors`：**正常 204 / 空 message 400 / 20KB JSON 413 / 第 31 次 429**，日志 `[CLIENT-ERROR]` 与 `client.error.reports{kind}` 计数器实测可查 |
| P0-8 网关加固 | ✅ 完成 | 探针 8s 硬超时 + 巡检看门狗；限流按身份分桶；`/health|/metrics|/reload` 跳过；167 tests 绿 |
| 云上收敛 | ✅ 完成 | 只剩 backend/musicapi/mysql/redis；磁盘 19G→15G；监控改本机按需（`restart: "no"`，起→200→down→零残留实测） |
| 外部探活 | ✅ 已上线运行 | `.github/workflows/uptime-check.yml` 已推送并 `workflow_dispatch` 实跑 **success**；Server酱 secret 实测可推送（首跑真的收到 🔴）。**能力边界**：GitHub runner 在 Azure，CF 对数据中心 IP 一律 managed challenge（403 + "Just a moment..."，0.1s，换 UA 无效），源站 80/443 只放行 CF 回源 → 探活只能发现 DNS 失效 / CF 边缘不可达 / 源站整体故障（522/523/524），源站内部组件由服务器 cron 看门狗每分钟盯 |

### 10.1 P0-3 设计取舍（避免后续被"优化"回坑）

1. **计数落 Redis，不落 JVM 内存**：复用 `RateLimitService` 的原子 `INCR + EXPIRE` Lua 脚本。JVM 内 map 方案要么无界（内存泄漏）、要么要自己写淘汰（回收逻辑出错就漏额度），Redis 现成且跨实例一致。
2. **fail-open 而非 fail-closed**：Redis 抖动时放行并打 WARN。限流是可用性组件，不能因为它故障就把听歌/搜索全打成 429；IP 维度还有 nginx `limit_req` 兜底。
3. **身份优先级 user > device > X-Real-IP > remoteAddr**：设备 ID 让"换 WiFi 的同一台手机"计数连续；IP 是最后的兜底。
4. **入站 `X-User-Id` 一律不采信**：出站方向后端自己写这个头给网关，入站方向它同样可被客户端伪造，采信等于"换头即换额度"。仅已认证用户 ID 来自 SecurityContext（不可伪造）。
5. **过滤器只在 Security 链里跑**：`FilterRegistrationBean.setEnabled(false)` 关掉 Boot 的自动注册，否则同一请求计数两次、且第一次执行时 SecurityContext 为空导致身份退化。
6. **只拦 GET**：搜索/流都是 GET 语义，416/OPTIONS 预检不计数。

## 10.2 线上部署实测（2026-10-23 10:07–10:20，阿里云 ECS）

部署方式：本机 `docker build` → `docker save | gzip | ssh docker load` → `compose up -d --no-build`（**不在 1.6G 云上跑 mvn**）。

| 项目 | 实测值 |
|---|---|
| 新镜像 | backend `583MB` / musicapi `467MB`（load 后大小），回滚标签 `*:rollback-20261022` |
| 后端冷启动 | **161.0 s**（基线 156.8 s，差异在噪声范围；`Started VibeMusicBackendApplication in 161.0 seconds`） |
| 健康 | `{"groups":["liveness","readiness"],"status":"UP"}` |
| 新代码指纹 | 容器内 jar 的 `application.yml` 含 `rate-limit:` 段；`RateLimitFilter/RequestIdentity/RateLimitProperties` 三个 class 均在 jar 内；`SecurityConfig.class` 与本地 md5 一致 |
| 限流 | 固定身份连打 25 次 search → `200×20` 后 `429×5`，`Retry-After: 40`，body `{"code":429,"message":"请求过于频繁，请稍后再试","data":null}`；Redis `ratelimit:search:dev:probe-fixed-001 = 25` |
| 错误上报 | 正常 `204`；空 message `400`；20KB JSON `413`（`{"code":413,"message":"上报内容过大"}`）；同身份第 31 次 `429`；`ratelimit:clienterr:dev:err-probe-002` 落键 |
| 网关额度 | 容器内 `rate-limiters.js`：global `300` / search `120` / url `240` |
| 前端 | `deploy-frontend.sh --apply` 发布成功；线上 html 引用新 `index-CeGBCmeH.js`（200），dist 含 `client-errors` 特征、0 个 sentry 残留 |
| 4 容器 | backend / musicapi / mysql / redis 全 healthy |

**部署过程中被实测抓出的两个真问题（都已修）**：

1. **前端发版误判回滚**：首跑切换与 reload 都成功、`/` 与 css 均 200，但取主 js 时 curl 报 `TLS connect error: unexpected eof while reading`（000），脚本按失败回滚。字节侧早已校验过（md5 + 解包文件数 + index.html sha256），属网络抖动 → 改为 `fetch_code/fetch_body`：**只有 000/空体重试一次，HTTP 错误码不重试**（不削弱门禁）。
2. **验证脚本自身 bug**：首次 25 次连打全 200，一度以为限流失效；实际是脚本把 `X-Device-Id` 写成 `deploy-verify-$(date +%s)`，25 次请求跨了好几秒 → 每次都换身份、各自一个桶。改成固定设备头后立刻复现 429。**教训：验证限流必须用稳定身份，否则测的是"新用户"而不是"同一用户"。**

**已知遗留**：冷启动 161 s 仍意味着每次发版 2.6 分钟不可用（P1-1 才解决）。

> 更正（2026-10-23）：原文称 `SERVER_TOMCAT_THREADS_MAX=50` 与 yml 一致于 max=50 是**错的**——yml 读的占位符是 `${TOMCAT_MAX_THREADS:40}`，环境变量名不匹配 → 一直静默生效 40。已在 `docker-compose.demo.yml` 改成 `TOMCAT_MAX_THREADS: 50`（下次部署生效），并加注释说明名字必须对齐。


---

## 11. P1 执行状态（2026-10-23 上午，每项附真实证据）

### 11.1 冷启动：161.0 s → **47.9 s**（-70%）

**取证先行**（对 10:07 那版容器日志逐段拆）：banner→Tomcat **46.3 s**（期间 `-Xlog:class+load` 每启动写 19,525 行/3 MB）、Tomcat→Hikari 18.4 s（MySQL 单连接 6.6 s）、上下文收尾 **70.8 s**（预热在主线程 refresh 期间就开跑、`StorageService` 启动即连已下线的 MinIO）。

**四处改动**：

| # | 改动 | 效果 |
|---|---|---|
| 1 | 预热从 `@PostConstruct` → `@EventListener(ApplicationReadyEvent)` + `app.prewarm.initial-delay-seconds`（默认 20s）+ `app.prewarm.enabled` | 启动路径不再与 17 个关键词×5 路上游抢 CPU；收尾段 70.8 s → ~52 s |
| 2 | `StorageService.init()` 只构建客户端，桶检查改 `ensureBucket()` 懒执行（只试一次） | 启动路径零 MinIO 网络调用（生产已无 MinIO） |
| 3 | `JAVA_OPTS` 摘掉 `-Xlog:class+load`、`-XX:NativeMemoryTracking=detail`、`-XX:MetaspaceSize=48m`，改 `${JVM_DIAG_OPTS:-}` 可选注入 | banner→Tomcat 46.3 s → 44.2 s（**只省 2 秒**，说明主因不是日志） |
| 4 | **`cpus: 0.5` → `1.5`**（`docker-compose.demo.yml`） | **决定性因素**：banner→Tomcat 44.2 s → 约 10 s，总启动 136 s → 47.9 s |

**为什么第 4 项才是主因（实测数据）**：0.5 核配额下，生产容器 `cpu.stat` 为 `nr_throttled 2546 / nr_periods 13355`（**19% 周期被限流**，限流累计 297 s > 实际 CPU 150 s）；启动期 `nr_throttled 1792 / 2087`（**86% 周期被限流**）。同期宿主机 load average 仅 0.45（2 vCPU 基本空闲）——瓶颈是配额不是机器。调到 1.5 核后，同一镜像冷启动 `Started VibeMusicBackendApplication in 47.885 seconds`。

**副作用与取舍**：CPU 配额是上限不是预留（宿主 2 核，mysql 0.3 / redis 0.3 / musicapi 0.2 均很轻），调高只影响峰值时谁先被调度；内存配额未动（仍 768 M）。

### 11.2 零停机部署（蓝绿，已实弹通过）

`scripts/deploy-backend.sh`（8 步，默认 dry-run，`--apply` 才真动）：本机构建 → `docker save|gzip|ssh load`（**比层 + Config 内容，不比 image ID**——跨 Docker 版本 load 会规范化 config，ID 必然变，29.1.3→29.7.2 实测踩到）→ 起 `vibemusic-backend-next` 到空闲端口（8080/8082/8083/8084 轮换，**8081 被宿主 nginx 占用**）→ 等 healthy → 写 upstream + 首次幂等迁移 nginx + `nginx -t` + reload → 验证流量真的改道 → 停旧容器、next 转正。

| 项目 | 实测 |
|---|---|
| 切换期可用性 | 云上探针每 0.5 s 打一次 nginx 回源口，**1,596 次采样全部 200**（零停机） |
| 新容器 healthy 耗时 | **53 s**（这期间旧容器一直在服务） |
| 首次 nginx 迁移 | 双备份到 `/root/.vibemusic-bluegreen/*.bak-<ts>`，`nginx -t` 通过后 reload；失败自动还原且不 reload |
| 回滚点 | `vibemusic-backend:rollback-<ts>` + 配置备份，回滚同样零停机 |
| 内存闸门 | `available < 400 MB` 直接拒绝（蓝绿窗口两个 JVM 并存） |

**第二次蓝绿（p2 镜像，同一天 11:32–11:34）**：端口从 8082 轮换回 **8080**，切换期探针 244 次全 200，新容器 healthy 67 s，冷启动 `Started in 48.2 seconds`；运行中 jar 的 md5 与本地 `mvn verify` 产物一致（`04e3197…`），字节级确认线上跑的就是门禁验过的那份。

**第三次/第四次蓝绿（p3、p4，2026-10-23 11:59 / 12:26）**：p3 端口 8080→8082、探针 258 次全 200、healthy 66 s；p4 端口 8082→8080、探针 **265 次全 200**、healthy **55 s**、冷启动 `Started in 48.9 s`。p4 的容器 jar md5 与本地 `mvn verify` 产物一致（`401ccb1…`），并首次通过 `--set-env TOMCAT_MAX_THREADS=50` 让 Tomcat 线程数按预期生效（此前环境变量名与 yml 占位符不匹配，一直是 40）。

**两次踩坑（脚本已加固）**：

1. **镜像 ID 不能当校验依据**：跨 Docker 版本 `load` 会规范化 config，ID 必变（29.1.3→29.7.2 实测）。改成「RootFS 层逐层 diff_id + Config 摘要」。
2. **`docker inspect -f '{{range ...}}'` 输出末尾会多一个空行** → `mapfile` 末元素恒为空串 → 重建的启动命令变成 `sh '' -c ...` → 容器死循环重启，日志只有 `sh: can't open ''`。已用 `sed '/^$/d'` 过滤，并加「RestartCount > 3 立即失败」的护栏（不再盲等 7 分钟）。

**配套**：`scripts/health-alert.sh` 的探活端口改为动态解析（`HEALTH_PORT` → upstream conf 的 `server 127.0.0.1:<port>` → 8080 回退），否则切换后每分钟误报。

### 11.3 MinIO：从「硬依赖」改成「开关 + 优雅降级」

云上只保留 4 个核心容器（backend / musicapi / mysql / redis），MinIO 已下线，但代码曾硬依赖它 → `GET /api/download/file/{id}` 线上 **404**、`POST /api/download/{id}` 必然失败。

| 改动 | 说明 |
|---|---|
| `storage.enabled`（默认 **false**） | 关闭时 `StorageService` 零网络调用：`exists()` 直接 false，读写抛 503；`isAvailable()` 供调用方判断 |
| 60 s 短期熔断 | 开启但连不上时，60 s 内直接判不可用，避免每个请求吃一次连接超时 |
| `AudioProxyService` | 把 `StreamController.streamFromRemote` 收敛成**唯一实现**，播放（`asAttachment=false`）与下载（`true`）共用，消除孪生副本 |
| 下载降级 | 存储不可用/对象不存在 → 上游直连流式转发（带 Range 与 `Content-Disposition: attachment`），不再 404 |
| `check` 语义 | 改为「现在能否下载」= 已缓存 **或** 存储不可用（走直连） |
| 本地演示 | `docker-compose.yml` 显式 `STORAGE_ENABLED: "true"`（完整栈带 MinIO 才开缓存），demo 版显式 `"false"` |

**线上实测**：`/api/download/file/347230` → `200`、13,042,460 B、`Content-Type: audio/mpeg`、`Content-Disposition: attachment; filename*=UTF-8''347230.mp3`；`/api/download/check/347230` → `{"code":200,"data":true}`；`/api/songs/stream?sourceId=347230`（Range 0-65535）→ `206` + `Content-Range: bytes 0-65535/13042460`。

### 11.4 非核心依赖降级盘点（结论 + 已修）

全量盘点后，「非核心服务挂掉会把核心功能打成 5xx」的位置共 6 处，已修：

| 位置 | 原行为 | 现行为 |
|---|---|---|
| `IdempotentGuard.tryAcquire` | Redis 异常未捕获 → 收藏/取消收藏 **503** | catch → 放行（fail-open，warn 60 s 限流打印） |
| `RateLimitService.tryAcquire` | 无 try/catch → AI 接口 **503** | catch → 放行 |
| `PlaylistController.detail` | 无 catch → 歌单详情 **500** | catch → `Result.error(503, "歌单服务暂不可用，请稍后重试")` |
| `spring.data.redis` 无 `connect-timeout` | Boot 4 不注入 SocketOptions → Lettuce 默认 **10 s** 建连 | 显式 `connect-timeout: 2000ms` |
| `AI_API_KEY` 默认 `change-me` | 占位值让 `isBlank()` 失效 → **每个 AI 请求真的外呼 DeepSeek 吃 401** | 默认空；`change-me`/`changeme` 视为未配置，两个入口（同步/流式）都短路且零外呼 |
| 本地完整栈未设 `STORAGE_ENABLED` | 本地演示的 MinIO 缓存**从未生效** | 显式开启 |

**已确认能优雅降级、无需改造**：搜索/取链/推荐/歌词/banner（musicapi 整体挂掉时返回 `200` + 空结果 + 重试建议，逐源降权 + QQ 5 min 熔断）、Redis 缓存与 Token 黑名单（本地表兜底）、限流过滤器（fail-open）、封面代理（8 s 超时 → 502）。

**启动路径外部调用复查**：`@PostConstruct` / `ApplicationRunner` / `CommandLineRunner` 全量核查，除 Flyway（MySQL，fail-fast 属预期）外**无第三处**对外网络调用。

### 11.5 traceId 贯穿全链路（P1-9，已上线）

**问题**：`TraceIdFilter` 只给请求线程 `MDC.put("traceId")`，而搜索/预热/取链三条链路都提交到 `ThreadPoolConfig` 的线程池 → worker 线程没有 MDC，logback 的 `[%X{traceId}]` 打空值，异步日志无法与请求关联；上游异常大量只打 `getMessage()`，没有堆栈；出站调 musicapi 不带 trace，网关日志无法对齐。

| 改动 | 说明 |
|---|---|
| 新增 `MdcTaskDecorator` | 提交时取 MDC 快照 → worker 执行前覆盖 → `finally` **精确恢复**任务开始前状态（不是无脑 clear，避免污染池内上下文）；无状态、三池共用 |
| `ThreadPoolConfig` | `initialize()` **之前**装配装饰器（之后再 set 不生效） |
| 新增 `TraceHeaderSupport` | MDC 有合法 traceId 才写 `X-Trace-Id`；空值/超长/含 CRLF 一律丢弃（header 注入防护） |
| `NeteaseApiService` | 接进唯一造头入口 `buildHeaders`，签名与 cookie 逻辑不变 |
| `SongSearchService` / `SongPlayService` / `AiToolService` / `NeteaseApiService` | 上游失败 catch 补异常对象；逐首歌解析、超时、聚合结论等高频/无信息量位置按频次豁免（否则一次搜索秒级刷屏） |
| `musicapi` | 访问日志按需追加 `[trace=...]`（无该头时格式与原样一致）；字符集/长度校验与限流计数键口径一致，防日志注入 |

**线上实测（2026-10-23 12:04，冷搜索必然穿缓存）**：请求头带 `X-Trace-Id: miss120406` →

```
后端: [http-nio-8080-exec-8] [miss120406] [CACHE-LAYER] Redis 未命中 ...
网关卡: GET /netease/search?... [trace=miss120406]
        GET /qq/search?...      [trace=miss120406]
        GET /kugou/search?...   [trace=miss120406]
        GET /bili/search?...    [trace=miss120406]
        GET /migu/search?...    [trace=miss120406]   ← 5 条全是扇出线程发出的
```

这 5 个请求由 `searchExecutor` 的 worker 线程构造并发出，头部能带上 traceId 即证明「装饰器 → MDC → 出站头」整链生效（不是请求线程代发）。响应头同样回显 `X-Trace-Id`。

**musicapi 替换代价（记录，不是缺陷）**：musicapi 启动约 6 秒，替换是「停旧→起新」，实测 `host:3000/health` 每 100ms 采样出现 **66 次失败 ≈ 6.6 s**；期间后端搜索按设计降级为「200 + 空结果 + 重试建议」，不出现 5xx。宿主 `127.0.0.1:3000` 被 cookie 巡检脚本与一个 testweb 反代引用，所以新容器必须保留同端口映射，不能只走 docker 网段。

### 11.6 AI 请求总预算（P1 快速项，已完成）

**问题**：`AI_RESPONSE_TIMEOUT = 60s`，Function Calling 最多 3 次 LLM 调用 → 单请求最坏 180s，客户端早断开而容器线程与连接仍被占；流式 `WebClient` 没有 `responseTimeout`，上游不回首包时无限挂起。

| 路径 | 改前最坏 | 改后最坏 |
|---|---|---|
| `/chat` 同步 | 3×60s = **180s**（工具调用还不受外层约束） | **45s 硬顶**：第 1 次 `min(60s, 35s→45s)`，续轮逐次收紧 `min(60s, 剩余)`；工具调用 `min(10s, 剩余)`；剩余 ≤0 不再发起调用 |
| `/stream` SSE | 无首包超时，**无限挂**（SseEmitter 60s 只能 cancel 外层，中断不了阻塞读） | 首包 ≤20s、总时长 ≤45s（`timeout(min(20s,剩余))` + `takeUntilOther`），终态只发一次 `done`/`error` |

实现：`AiCallBudget`（纯逻辑、时钟可注入）+ `AiToolCallGuard` + 请求级 `RequestConfig` 覆盖读超时（**复用同一连接池**，不动全局 60s）；`ai.total-budget-ms` / `ai.stream-first-byte-timeout-ms` 可覆盖。SSE 事件格式（`token`/`done`/`error`）未变，前端零改动。测试 11 例，含「永不回首包」与「每次必返回 tool_calls」的 JDK HttpServer stub（零新依赖），断言第 3 次 LLM 调用绝不发起。

### 11.7 搜索侧韧性：网关整体熔断 + Redis 单飞三态（已完成）

**问题 A**：musicapi 进程整体挂掉时，5 个平台同时连接拒绝/超时，每次搜索 5 线程 × 最多 3s 空转。按平台的 QQ 熔断管不了「整个网关不可用」。
**问题 B**：`tryLock` 返回 `null` 时无法区分「别人持锁」和「Redis 挂了」，Redis 全挂时每次搜索白等 150+300+600+1200 = **2250ms** 退避，而每轮重读必然失败。

修法：
- `UpstreamBreaker`（纯内存、时钟可注入）：**只对传输层失败计数**（连接拒绝/超时/DNS/5xx），4xx 与「200 空结果」算成功；连续 10 次（≈ 2 次全平台失败）开路 30s，开路期跳过全部 5 个远程分支；半开只放 1 个探针，探针 10s 未回传即放弃（防永久半开）。
- `LockResult` 三态：`ACQUIRED` / `BUSY` / `UNAVAILABLE`；`UNAVAILABLE` 直接直查，不再退避空等。

**一个必须记住的事实**：搜索路径（`search → doApiSearch`）**本来就没有 DB/本地兜底分支**——`songMapper` 只用于 `getRandomSongs()`。所以「开路时保住 DB 结果」这个前提不存在；开路返回的是与「五平台全失败」完全一致的收尾（空列表 + retry 提示、不写空哨兵）。后续若要加关键词 DB 兜底，必须放在熔断判断**之前**。

### 11.8 移动端后台播放断链（P1-5，已上线）

**症状**：手机上从首页/搜索页点播后切后台或锁屏，播放链路断掉——没有锁屏控件、进度不再走、播完不自动切歌。

**根因（两处）**：

1. `useAudioBackground`（MediaSession / Worker 心跳 / 三层结束检测 / 前后台快照）只被 `PlayerBar.vue`（桌面常驻）与 `MPlayerView.vue`（移动端**仅播放页**）调用。移动端所有 `/m/*` 页面都挂在 `MobileShell` 下，但 `MobileShell` 自己不初始化它 → 用户不在播放页时，整条后台链路根本不存在。
2. `playSongFromApi` 在 `await apiPlaySong(...)` 后没有校验「这次请求是否仍是最新」，快速连点两首时先发后到的请求会把后点的歌覆盖（放着 B 显示 A）。

**修法**：

- `useAudioBackground` 改为**模块级单例**：首次调用创建全部状态与监听，后续调用返回同一 `api`，不重复注册；引用计数归零才 teardown。`App.vue` 根组件调用一次（常驻 → 计数恒 ≥1），`PlayerBar`/`MPlayerView` 原调用保留并复用同一实例。
- Worker 引用计数修正：本实例每次 `acquireWorker()` 记账、teardown **等量** `releaseWorker()`（原实现只 release 一次 → `workerRefs` 永不归零，最后一个持有者卸载也不 `terminate`）。
- `startWorkerTimer` 的回调从单槽位改成 Set，避免播放页与底部播放条互相顶掉进度回调。
- `playSongFromApi` 加单调递增 `_songPlaySeq`，await 回来先校验序号，过期请求直接丢弃（成功/失败分支都判）。

**验证（线上移动视口 390×844 + iPhone UA，Playwright 单实例）**：11 项全过——播放前就已注册 **4 个 MediaSession 动作**（旧实现此时为 0，这是最关键的判别证据）、`visibilitychange` 已注册；点播后进入 `/m/player` 出声且 metadata 标题/歌手正确；退回 `/m` 首页仍在播、`removeEventListener` 计数为 0、动作注册数不涨（单例未重复注册）；模拟 `hidden` 3 秒进度前进 3.01 s，恢复可见继续播。单测 180 例全绿（新增 6 例）。

### 11.9 仍待办（按优先级）

1. ~~AI 超时预算~~ → 见 §11.6（已完成）
2. ~~musicapi 整体熔断~~、~~Redis 单飞空等~~ → 见 §11.7（已完成）
4. ~~配置漂移清理~~（已完成 2026-10-23）：删 `ES_URI` 死配置（Java 零绑定）；`SERVER_TOMCAT_THREADS_MAX` → `TOMCAT_MAX_THREADS`（名字对齐 yml 占位符，下次部署由 40 生效为 50）；`docker-compose.full.yml` 的 `AI_API_KEY` 默认值改空（与 demo/主 compose 一致，占位值不再触发外呼）。
5. ~~响应头重复~~（已完成 2026-10-23）：`/api/`、`/uploads/` 两个反代 location 加 `proxy_hide_header`，并把 `proxy_pass` 改为 upstream 名；`X-Frame-Options` 由「DENY + SAMEORIGIN 冲突」变单值。
6. ~~前端覆盖率阈值~~（2026-10-23 完成）：`include` 补 `src/views/**`，按实测基线落阈值（删死代码后 33/22/20/37），CI 的 `--coverage` 即时生效。

---

## 12. 2026-10-23 补做记录（CodeBuddy，均在 main 上可回滚）

### 12.1 网关收尾与安全加固（P1-3）

| 提交 | 内容 | 证据 |
|---|---|---|
| `40793c6` | 统一上游客户端收口后的二阶 SSRF 防护：`isPrivateHost` + `KIND.BLOCKED` + `allowPrivate`（仅测试用）；`routes.js` 候选地址日志改 `describeUrl`（只 host+pathname） | 网关 178/178 |
| `d215e64` | 补三处绕过：IPv6 映射/兼容写法（`::ffff:7f00:1`、`::7f00:1`）、末尾点域名与元数据域名、重定向逐跳校验（axios `beforeRedirect`，`blocked` 不重试） | 179/179；安全扫描复扫 0 finding，门禁 pass |

### 12.2 网关依赖与运行时（P1-4）

| 提交 | 内容 | 证据 |
|---|---|---|
| `4a54f8b` | 删除停更的 `qq-music-api`（2022-07-09），`/qq/playlist` 直连 `c.y.qq.com` 同参数接口；去掉 `qqMusic.setCookie` 注入；lock 连带移除 1298 行传递依赖 | 180/180；新增 `/qq/playlist` 字段映射用例 |
| `8953996` | `node:20.19-alpine` → `node:24-alpine`，CI/deploy 的 `node-version` 与 `engines` 对齐 24 | 本地 Node 24.19 下 180/180 |

### 12.3 前端门禁与死代码（P1-6 / P1-11）

| 提交 | 内容 | 证据 |
|---|---|---|
| `e8d4d1b` + `f35ef14` | `coverage.include` 补 `views`；删除 7 个零引用 `mobile/M*View.vue`（1643 行）；阈值由 28/18/17/32 抬到 **33/22/20/37** | 删前 33.25/22.04/20.05/37.12 → 阈值按实测钉住；180 tests、`vue-tsc` 通过 |

### 12.4 Android 播放唤醒（P1-7 的第一步）

- `vibemusic-native` 提交 `1ce8087`：`ExoPlayer.Builder.setWakeMode(C.WAKE_MODE_LOCAL)` + Manifest 加 `WAKE_LOCK`；`testDebugUnitTest` BUILD SUCCESSFUL。
- **Media3 1.5.1 → 1.12.x 暂缓**：跨 7 个 minor，且通知栏播放键问题未结案，升级后无法归因。等真机 logcat 抓到根因，或至少 1.5.1→1.8→1.12 分两次走。

### 12.5 对原方案的修正（执行时采纳）

1. **P1-1 先 spike 再决策**：P0-2 缩池与虚拟线程方向相反，且冷启动主因是 CPU 配额（0.5→1.5 核）不是 JVM 参数；直接开虚拟线程会互相掩盖。
2. **P1-8 先做行为快照**：拆 `SongSearchService` 前先固化平台合并顺序/去重/降级的 golden 输出，601 例单测挡不住隐性知识丢失。
3. **P1-2/P1-12 顺序**：先只读对账（已具备 `scripts/reconcile-cloud.sh` + `deploy/production/`），再切镜像不可变；否则对账会把部署临时物当漂移。
4. **证据分级**：每项标注 L1 单测 / L2 本机 / L3 线上指纹+行为；`TOMCAT_MAX_THREADS` 名字不匹配导致"改了没生效"的教训要求关键配置启动即打印。
5. **P1-11 阈值不拍数**：前端 40/35 是目标不是现状；当前基线 33/22/20/37。

### 12.6 仍未做（需单独排期）

- ~~P1-1 JDK 25 + 虚拟线程 spike~~ → 已完成并**决定不开**，见 §13.2
- P1-6 余下：`strictNullChecks` 开启（预计多文件）、`window.vibeFavIds` 收口
- ~~P1-8 `SongSearchService` 拆分~~ → 已完成，见 §13.1；`SongPlayService`(485) / `AssistantController`(470) / `RecommendService`(424) 仍待拆
- P1-10 契约测试（`contract/` + WireMock/Testcontainers）
- P1-12 镜像 tag=git sha + 云端只 pull（蓝绿与内容校验已具备）
- 线上部署：本轮所有提交尚未发布到生产

---

## 13. 2026-10-25 续做记录（CI 门禁真相 / P1-1 spike / P1-8 第一二刀）

### 13.1 P1-8：`SongSearchService` 拆分（`9cb4dbb`）

829 纯 LOC → **498**，按 PLAN 指定的三条轴拆出三个包内类，测试一行未改：

| 新类 | 纯 LOC | 职责 |
|---|---|---|
| `PlatformSearchAdapter` | 172 | 五平台单次搜索、解析、超时等待、失败上报；不碰缓存/单飞/分页 |
| `QqCircuitBreaker` | 121 | QQ 熔断状态机；进程内状态 + Redis 跨实例持久化，异常只降级不抛 |
| `SongDtoParser` | 91 | 网关结果 → `SongDTO` 纯解析、封面 image-proxy 改写、别名/热度宽容转换 |

facade 只留搜索策略（缓存 → 单飞 → 扇出 → 合并 → 分页）+ 动态源权重 + 预热 + 随机推荐。
为了保住「行为快照」：`shouldSkipQq` / `recordQqSuccess` / `recordQqFailure` / `recordQqOutcomeOnce` /
`getQqWithTimeout` / `parseAlias` / `isBiliCdnCover` / `remainingSec` 在 facade 保留委托入口，
既有 719 例断言零修改全部通过；超时预算常量值不变（SEARCH 4s / QQ 2s）。
同时删掉迁移后无调用方的 5 个旧重载。

**证据（L1）**：`mvn -o verify` EXIT=0，surefire testcase 口径 **719 / 0 失败 / 0 错误 / 1 跳过**，JaCoCo 门禁 pass，5m25s。

### 13.2 P1-1 虚拟线程：spike 完成后决定不开

本机同 jar 跑 `spring.threads.virtual.enabled=false/true` 三轮对照（临时 mysql/redis + 真实 musicapi，线程数取 `/proc/<pid>/status`）：

| 场景 | off | on |
|---|---|---|
| 搜索 60 并发 20s | 998.7 rps / p50 47 / p95 106 / 线程 Δ40 | 1320.3 rps / p50 39 / p95 88 / Δ10 |
| 搜索 200 并发 15s | 761.9 rps / p50 237 / **p95 433 / p99 736** | 938.5 rps / p50 176 / **p95 552 / p99 905** |
| `/stream` 60 长连接挂 15s | 线程 Δ43 | 线程 Δ20 |

结论：吞吐增益只出现在 CPU 密集的缓存路径，200 并发下尾延迟反而劣化（p95 +27% / p99 +23%）；
`/stream` 线程增长只从 Δ43 降到 Δ20，说明还有非 Servlet 线程（上游 HTTP 客户端池）在占用，
开 VT 解决不了 P1-1 想解决的「每听众 1 平台线程」；真正硬闸仍是 Hikari 8 + 2 vCPU，VT 不改变；
RSS 多 20~120MB，1.6GB 机器不划算。→ 保持关闭，RUNBOOK 已记录复测方法。

### 13.3 CI 门禁真相（重要）

2026-10-23 那批 33 个提交**从未被 CI 验证过**：只推了 Gitee，GitHub remote 停在 `a19f540`，
而 GitHub Actions 不跑 Gitee 推送。补推后立刻两红，均已修复：

| 红项 | 根因 | 修法 |
|---|---|---|
| 前端覆盖率门禁 | `f35ef14` 把 lines 钉到 37（注释称实测 37.12），HEAD 本机与 CI 实测均 **36.95**，门禁从落地起就是红的 | 不降阈值，补 `recommend` store 测试（此前零测试）→ 真实 **37.27%**（`6e9e496`） |
| api-test 推荐重叠 88% | 非代码回归（`RecommendRules` 逐行等价抽取）；本地复现 **0%**，CI 上 runner 出口 IP 被上游风控、可换的曲不足 | 加候选池守卫（并集 <12 跳过）（`5f01935`） |
| 同上**第二次红**（run 40139733274） | 守卫不够：该轮并集 17 首（>12 所以放行），但只剩一路平台回同一批曲，15 个两两组合里 2↔3 达 88% —— 属前提缺失而非回归 | 改两档断言（`api-test/test_search.py`）：**始终**断言「三批并集 > 单批」（守住「换一批毫无变化」这个真回归），**仅当并集 ≥ 3×单批**（理论上三批才可能互不相交）才断言两两重叠 <50%，否则 skip 并打印诊断 |

另补 `backend-jdk25` job（`mvn test`，不含 JaCoCo）并清掉残留 `--test-force-exit`（`39593cb`），
真 CI 上 7/7 success。**以后「推 Gitee」不等于跑过 CI，必须推 GitHub。**

### 13.4 P1-2：云上对账从「永远红」变成可用门禁

只读对账跑出 **13/21 项差异**，逐条查过后发现其中没有一项是「云上被手改而仓库不知道」，
反而全是仓库领先或生产根本不用的影子文件。于是给 `scripts/reconcile-cloud.sh` 加预期差异分类：

- 脚本 3b 节维护 `label|原因` 表（12 项），命中即标 `预期差异`，打印原因但不计失败；
- **表外**的任何差异才算真漂移 → 退出码 1；
- 实测：`10/22 一致 + 12 项预期差异 + 0 真漂移`，退出码 **0**（此前恒为 1，等于没人会看）。

同时挖出两条一直被当成事实的错误认知，已写进 `docs/cloud-source-of-truth.md` §5.1/§5.2：

1. **生产 app 容器不是 compose 起的**：`vibemusic-backend` / `vibemusic-musicapi` 没有任何
   `com.docker.compose.*` 标签（0 个），是 `scripts/deploy-backend.sh` 里的 `docker run` 蓝绿起的；
   compose 只托管 mysql / redis。云上那份 `docker-compose.demo.yml` 停在 2026-10-19，
   `cpus: 0.5` 与线上实测 `NanoCpus=1.5e9`/`Memory=768M` 不一致——**它只描述 mysql/redis，不是 app 真源**。
2. **云上 cron 是 3 条不是 2 条**：多了 `* * * * * health-alert.sh`（每分钟告警），而仓库
   `deploy/production/crontab.txt` 缺这条、且 backup 行写成 `/bin/sh`（云上是 `/bin/bash`），
   对账因此长期假红。已按 `crontab -l` 回同步，并将 `scripts/health-alert.sh` 纳入对账清单（两边字节一致）。

> 补记（2026-10-25 二次修订）：`test_personalized_refresh_overlap` 的候选池守卫只看并集绝对值
> 不够——并集 17 首仍可能因为「只剩一路平台」而让相邻两批 88% 重叠。现改为两档：并集 > 单批
> 始终断言（轮换彻底失效时必红），并集 ≥ 3×单批 才断言两两 <50%。生产实测并集 24 = 3×8，
> 强断言照常执行；CI 风控轮次会 skip 并打印三批条数/并集大小，便于下次一眼分清回归与风控。

### 13.5 P1-12：镜像不可变 + 云端只 pull（GHCR）

原方案要「CI 产镜像推到 registry，云端只 pull」。落地时先验了前提，再动代码：

**前提实测（云端 `101.132.167.178`，只读 curl + 3MB 小镜像）**：`ghcr.io/v2/` 401（正常，未认证）、
`ghcr.io/linuxcontainers/alpine:3.20` **匿名拉取成功**（blob CDN 可达），Docker Hub `registry-1.docker.io`
反而不通（6s 超时）→ 走 GHCR 可行，走 Docker Hub 不可行。

**改动**：

- `ci.yml` 的 `Docker images` job：job 级 `permissions: packages: write`；两个镜像 `docker build`
  加 `org.opencontainers.image.revision=$GITHUB_SHA` label；push/tag 事件追加 GHCR 发布，
  tag **只用完整 commit sha**（不推 `latest`，避免 tag 不动而内容变），推完 `docker manifest inspect` 回查。
- `scripts/deploy-backend.sh` 新增 `--from-registry` / `--registry`：跳过本机构建与 300MB tar 传输，
  云端 `docker pull` 后**断言三道前置**（镜像可拉、`revision` label == 期望提交、`Architecture=amd64`），
  任一不符就停在 3/8（容器与 nginx 一动没动）。回滚 = `--image <历史sha>`，同样只 pull。
- 默认路径（本机构建 + save/load）保持不变，CI 没跑或失败时可退回；`docs/deploy-backend.md` 补了三路径取舍表。

**为什么不能只信 tag**：tag 是可变指针（人可以重推同名 tag）。因此把「内容 ↔ 提交」绑定落在镜像 label 上，
部署时比对 label 而不是比对 tag 名字 —— 与 §12「不信报告信字节」同一原则。

### 13.6 P1-6 收尾：前端 strictNullChecks 全量开启（346 → 0）

PLAN 原估「2–3 人日」。先做错误聚类再动手，发现估时口径不对：

**真实构成**：346 个错误里 323 条 TS2339 全部以 `does not exist on type 'never'` 结尾——
根因是 **52 个未标注类型的 `ref([])` / `ref(null)`** 在 strict 下塌缩（`ref(null)` 收窄后残留
类型是 `never` 而非 `null`，所以不是 346 个独立问题，是 52 个声明）。其中
`stores/auth.js:8` 一行 `Ref<UserInfo|null>` 就吃掉 41 条（含下游 5 个文件）。

**所以做法是「先落类型、后翻开关」**：

1. `src/vibe-models.d.ts`：5 个 ambient interface，字段直接取自报错清单（报错本身就是完整字段表）；
   `tsconfig.check.json` 的 `include` 补 `src/**/*.d.ts`（原先 `.d.ts` 只靠 `request.js` 的
   `/// <reference>` 进场）。
2. 37 处 ref 补 `@type {import('vue').Ref<T>}` —— **纯注释，零运行时**，因此在开关仍为 false 时可独立合入，无红窗。
3. 23 条残留逐条真修（不压错误）：两处 `getContext('2d')` 后 `if (!ctx) return`、模板
   `line.text || ''`、`localStorage.getItem() ?? 'null'`、头像首字母 `|| '?'`、
   4 处 `PlaylistPopup` 的 `v-if` 补 `&& playlistTargetSong`。
4. 最后翻 `strictNullChecks: true`（**只翻这一个**，`strict` / `noImplicitAny` 不动）。

**一处方案勘误**：`recommend.js:44` / `HomeView:161` / `MSearchView:74` 原本打算用 `?? ''` 修，
实测修不掉——null 来自形参默认值（`function searchSongs(..., platform = null)`），类型被推成
`null | undefined`，传 `string` 照样报错。正解是给 API 形参补 `@param {string|null}`（纯类型），
3 处调用点因此**零运行时改动**，比原方案少 3 处语义变更。

**为什么不按文件灰度**：实测 `include` 只放单个 `.vue` 仍会因 import 图把 `stores/*.js` 拉进
program（ESM 全连通），子集灰度不成立；`// @ts-nocheck` 关的是整个文件的所有检查（含现有 0 错
基线的真实检查），属拿已知债换未知债。**结论：全量开，总耗时 3–5 小时，不是人日级。**

**证据**：`npm run typecheck` 0 error、`npm run lint` 0、`npm test` 188 passed / 21 files；
全仓 `@ts-nocheck` / `@ts-ignore` / `@ts-expect-error` / `as any` 仍为 **0 处**。
`window.vibeFavIds` 已确认全仓零引用（可勾掉）。

**顺带暴露的问题（未修，需单独立项）**：`LyricLine.time` 在 LyricsView 走 `parseFloat`（当字符串）
而在 MPlayerView 直接比数字；`Playlist.creator` 实际是对象而非 id；`UserInfo.gender` 有
`string` / `number` 两种用法。本轮用 `string|number` 联合 + `Number()/String()` 等价包装抹平，
**运行时行为不变**，但数据形状本身该统一。
