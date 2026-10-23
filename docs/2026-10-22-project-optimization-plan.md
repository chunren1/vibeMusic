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

**已知遗留**：冷启动 161 s 仍意味着每次发版 2.6 分钟不可用（P1-1 才解决）；`SERVER_TOMCAT_THREADS_MAX=50` 由云端 compose 环境变量覆盖 yml 默认值 40，两者一致于"max=50"。

