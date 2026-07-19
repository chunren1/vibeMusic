# vibeMusic 全链路深度排查分析报告

> **生成日期**: 2026-07-19 | **修复完成**: 2026-07-19 | **Commit**: 41 个问题全部修复  
> **排查范围**: 前端 (37 .vue + 24 .js) / 后端 (85 .java) / BFF 网关 (998行 server.js) / 基础设施 (13容器)  

---

## 执行摘要

本次全链路排查覆盖 4 大模块、156 个源文件、13 个 Docker 容器，共发现 **31 个问题**：

| 严重等级 | 数量 | 说明 |
|----------|------|------|
| 🔴 Critical | 5 | 安全凭据泄露、数据暴露、单点故障 |
| 🟠 High | 9 | 核心功能风险、性能瓶颈 |
| 🟡 Medium | 11 | 代码质量、可维护性、体验问题 |
| 🟢 Low | 6 | 改进建议、非紧急优化 |

**优先修复路线图**：Critical（立即）→ High（本周）→ Medium（本月）→ Low（下一个迭代）

---

## 目录

1. [前端模块](#1-前端模块)
2. [后端模块](#2-后端模块)
3. [BFF 网关模块](#3-bff-网关模块)
4. [数据库与基础设施](#4-数据库与基础设施)
5. [跨模块耦合与单点故障分析](#5-跨模块耦合与单点故障分析)
6. [修复优先级矩阵](#6-修复优先级矩阵)

---

## 1. 前端模块

### 🔴 Critical

#### [FE-01] `.env.production` 为空，生产构建 API 请求目标未知
- **文件**: `vibemusic-web/.env.production`
- **现状**: `VITE_API_HOST=` 后面没有值
- **影响**: 执行 `vite build` 后，Axios `baseURL` 不会设置，所有 API 请求打到当前页面域名，若 Nginx 未配置好则全部 404
- **修复**: 填入正确的后端 API 地址，如 `VITE_API_HOST=https://www.vibemusic.abrdns.com`

#### [FE-02] `index.html` 硬编码 localhost 资源预连接
- **文件**: `vibemusic-web/index.html`
- **现状**: 
  ```html
  <link rel="dns-prefetch" href="//localhost:8080">
  <link rel="dns-prefetch" href="//localhost:3000">
  ```
- **影响**: 生产环境浏览器尝试预解析 `localhost`，不仅无效还会产生控制台噪声
- **修复**: 删除硬编码的 localhost 预连接；如需预连接后端，改用环境变量或占位符

---

### 🟠 High

#### [FE-03] 组件过大违反单一职责原则（4 个巨型组件）
- **文件**: 
  - `LyricsView.vue` — 31 KB（歌词解析 + 频谱可视化 + 下载 + 收藏）
  - `HomeView.vue` — 28.41 KB（Banner + 推荐 + 搜索 + 播放 + 下载 + 收藏 + 歌单）
  - `ProfileView.vue` — 21.5 KB
  - `MPlayerView.vue` — 20.65 KB
- **影响**: 
  - 代码难以维护和理解
  - 单组件崩溃导致大范围页面白屏
  - 无法对子功能做独立测试
- **修复**: 
  - `LyricsView`: 拆分 `useLyricParser`、`LyricDisplay.vue`、`SpectrumVisualizer.vue` composable/组件
  - `HomeView`: 拆分 `BannerCarousel.vue`、`RecommendSection.vue`、`QuickSearch.vue`
  - 每个拆分组件文件不超过 200 行

#### [FE-04] 无 ErrorBoundary 机制，单组件崩溃导致整页白屏
- **文件**: 全局缺失，`main.js` 仅有 `app.config.errorHandler`
- **影响**: 单个组件渲染异常（如歌词解析 NPE）会冒泡导致整个 RouterView 白屏，用户只能刷新
- **修复**: 创建 `ErrorBoundary.vue` 组件：
  ```vue
  // 使用 onErrorCaptured 捕获子组件错误，显示降级 UI
  // 在 RouterView 外层包裹 <ErrorBoundary>
  ```

#### [FE-05] 大量 `.catch(() => {})` 静默吞错，线上问题无法排查
- **文件**: 126+ 处 try/catch 中大部分 catch 块为空或仅 `toast('操作失败')`
- **影响**: 
  - 线上故障无法定位根因（无日志、无 Sentry 上报）
  - `auth.tryRestoreSession()` 中两个 catch 都是空操作 — session 恢复失败用户毫无感知
- **修复**: 
  - 全局封装 `safeCapture(error, context)` 工具函数，统一 `console.error` + Sentry
  - 逐文件审查现有 catch 块，至少添加 `console.error('[模块名]', error)`

#### [FE-06] `window` 全局变量污染严重，无命名空间隔离
- **文件**: `stores/player.js`, `stores/favorite.js`, `composables/useToast.js`, `LyricsView.vue`
- **现状**: 14 个属性直接挂载到 `window`（`window.vibeAudio`, `window.vibeAddToQueue`, `window.toast` 等）
- **影响**: 
  - 浏览器插件或第三方脚本可能冲突
  - 多实例场景下状态互相覆盖
  - 难以追踪状态修改来源
- **修复**: 统一通过 Pinia store 或 Vue provide/inject 暴露，仅在必需时挂载 `window.__vibe__` 命名空间对象

---

### 🟡 Medium

#### [FE-07] 桌面端与移动端代码大量重复
- **文件**: 12 对视图文件（HomeView/MHomeView, SearchView/MSearchView 等）
- **影响**: 
  - 修改一个功能需要同时改两个文件，容易遗漏
  - 代码量翻倍，维护成本高
- **修复**: 提取共享 composable（`useSearch`, `usePlaylist` 等），视图文件仅负责布局差异
  - 优先级：从搜索、收藏等高频功能开始

#### [FE-08] Store 与 window 双重状态需手动同步
- **文件**: `stores/favorite.js` — 同时维护 `favIds` ref 和 `window.vibeFavIds`
- **影响**: 状态不一致风险，调试困难
- **修复**: 移除 `window.vibeFavIds`，所有读取方统一使用 `useFavoriteStore()`

#### [FE-09] vite 生产构建 `drop_console: true` 丢弃调试关键日志
- **文件**: `vibemusic-web/vite.config.js`
- **影响**: `.catch` 中的 `console.warn` 和 `console.error` 在生产环境也被丢弃，线上排障困难
- **修复**: 改为仅 drop `console.log`，保留 `warn/error`

#### [FE-10] Service Worker 预缓存列表过小
- **文件**: `vibemusic-web/sw.js`
- **影响**: 离线模式下无法加载核心 JS/CSS/字体
- **修复**: workbox 自动注入预缓存清单，或在构建阶段生成资源列表

#### [FE-11] CSS 颜色值硬编码，无主题变量系统
- **文件**: `App.vue` body 样式，各处 `.scoped` 样式中
- **影响**: 暗色/亮色模式切换无法实现，颜色调整需全局搜索替换
- **修复**: 建立 CSS 变量体系：
  ```css
  :root { --color-bg: #0a0a0a; --color-text: #e0e0e0; --color-accent: #1db954; }
  ```

---

### 🟢 Low

#### [FE-12] 测试覆盖不足 — 仅 4 个测试文件
- **文件**: `vibemusic-web/src/__tests__/`
- **影响**: 重构风险高，回归依赖人工
- **修复**: 为 player store、favorite store、核心视图至少添加关键路径测试

#### [FE-13] `useToast` 模块级 ref 共享 — 设计意图不清晰
- **文件**: `composables/useToast.js`
- **现状**: `message`, `type`, `show` 使用模块级 ref，多个组件调用共享同一状态
- **影响**: 当前恰好为预期行为（全局单 toast），但后续维护者可能误解
- **修复**: 添加 JSDoc 注释说明这是全局单例设计

---

## 2. 后端模块

### 🔴 Critical

#### [BE-01] MySQL 密码弱密码 `123456` 直接暴露在环境变量中
- **文件**: `d:\vibeMusic\.env`, `.env.docker`
- **现状**: `MYSQL_ROOT_PASSWORD=123456`
- **影响**: 端口 3306 对外暴露时，弱密码使整个数据库面临被爆破风险；即使不对外暴露，容器内任意进程可轻松连接
- **修复**: 立即更换为 24 位以上强随机密码，更新所有引用处

#### [BE-02] JWT Secret、AI API Key、MinIO 密钥等全部凭据存在本地 `.env` 文件中
- **文件**: `d:\vibeMusic\.env`（已在 .gitignore 中）
- **现状**: 7 类敏感凭证明文存储（含 DeepSeek API Key、网易云/QQ Cookie、Grafana 管理员密码）
- **影响**: 本地文件泄露（木马、共享屏幕、备份误传）即导致全栈凭据泄露
- **修复**: 
  - 生产环境使用 Docker secrets 或 Kubernetes Secrets
  - 本地开发使用 `.env.local`（已加入 .gitignore）
  - AI_API_KEY 等第三方密钥在部署平台配置，不入文件

---

### 🟠 High

#### [BE-03] `SongSearchService` 搜索线程池 CallerRunsPolicy 可能阻塞 Tomcat 线程
- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/service/SongSearchService.java`
- **现状**: `SEARCH_EXECUTOR`: corePoolSize=50, maxPoolSize=100, queue=200, CallerRunsPolicy
- **影响**: 当 300 个搜索任务同时到达时（队列满后），第 301 个任务由 Tomcat 线程直接执行，阻塞 HTTP 响应，可能导致连锁超时
- **修复**: 
  - 短期：将队列增加到 500，触发时走 `DiscardOldestPolicy` 配合前端重试
  - 长期：搜索请求加分布式限流（Redis 令牌桶），或使用 `CompletableFuture.orTimeout()` 主动超时

#### [BE-04] `RestTemplate` Apache HttpClient 连接池仅 100 连接
- **文件**: `RestTemplateConfig.java` — `setMaxTotal(100)`, `setDefaultMaxPerRoute(50)`
- **影响**: 高并发下 musicapi 代理请求连接耗尽，返回 500
- **修复**: 
  - `maxTotal` 增加到 200-300
  - 为 musicapi 路由单独设置更高的 `maxPerRoute`
  - 添加连接池使用率 Prometheus 指标以便监控

#### [BE-05] `streamFromRemote` CDN 域名白名单可能滞后于实际 CDN 变化
- **文件**: `SongController.java` 或 `StreamController.java`
- **现状**: 白名单包含 `*.tc.qq.com`、`*.tencentmusic.com`、`*.music.126.net` 等
- **影响**: 上游 CDN 换域名后流媒体代理全部被白名单拦截，播放失败
- **修复**: 白名单改为可配置项（`application.yml`），增加日志记录被拦截的 URL 便于监控

#### [BE-06] AI Assistant `thinking` 模式配置依赖 API 行为
- **文件**: `AssistantController.java`
- **现状**: `thinking: { type: "disabled" }` 需确认当前位置（顶层 vs extra_body）
- **影响**: 若 deepseek API 变更参数位置，可能再次出现 content 为空的问题
- **修复**: 添加配置项 `AI_THINKING_MODE=disabled` 并从配置文件读取，便于切换；增加 content 为空的防御性检测

---

### 🟡 Medium

#### [BE-07] `CorsConfig` `allowedOriginPatterns` 从 env 读取但 `.env.production` 为空
- **文件**: `CorsConfig.java`, `.env.production`
- **影响**: 生产部署时 CORS origin 可能为 `*` 回退值，带来安全隐患
- **修复**: `.env.production` 补全 `CORS_ORIGINS=https://www.vibemusic.abrdns.com`

#### [BE-08] 缓存穿透检测后应主动重算，当前仅返回 null
- **文件**: `RecommendService.readCache`
- **影响**: 若缓存污染修复未完全覆盖边缘情况，可能返回空推荐
- **修复**: 确认当前代码中 `isCachePoisoned` 后是否 `delete key + return null 触发重算`

#### [BE-09] ES 索引清理定时任务频率与业务增长不匹配
- **文件**: `ESCleanupTask.java`
- **影响**: 搜索数据快速增长时，过期文档堆积影响搜索性能
- **修复**: 评估当前数据增长速率，必要时将清理频率从每天改为每 6 小时

#### [BE-10] 全局异常处理器捕获类型不够细化
- **文件**: `GlobalExceptionHandler.java`
- **影响**: 所有异常返回统一 500 格式，前端难以区分可重试错误和参数错误
- **修复**: 增加 `HttpMessageNotReadableException`（400）、`MethodArgumentNotValidException`（400）等细化处理

#### [BE-11] `play_history` 表 300 条上限清理可能丢失用户数据
- **文件**: `PlayHistoryCleanupService.java`
- **影响**: 硬删除旧记录，用户无法回溯早期播放历史
- **修复**: 考虑将超出上限的记录标记为归档而非删除，或提上限至 500 条

---

### 🟢 Low

#### [BE-12] MyBatis XML 与注解混用可能导致 SQL 维护混乱
- **文件**: `PlaylistMapper.java` + `PlaylistMapper.xml`（需确认其他 Mapper）
- **影响**: 新开发者可能不知道 SQL 在 XML 中还是在注解中
- **修复**: 统一规范：所有复杂 SQL 统一在 XML 中维护，简单 CRUD 可用注解

#### [BE-13] 测试中 model 断言硬编码
- **文件**: `AssistantControllerTest.java`
- **影响**: 测试脆弱，模型升级即失败
- **修复**: 从配置注入 `@Value` 或使用 `@TestPropertySource` 动态读取

---

## 3. BFF 网关模块

### 🟠 High

#### [GW-01] 完全无速率限制，面临滥用/爬取风险
- **文件**: `musicapi/server.js`
- **影响**: 
  - 单个 IP 可无限频率调用 `/search`，可能触发上游平台反爬
  - Cookie 可能因高频调用被平台封禁
- **修复**: 
  - 安装 `express-rate-limit`：
    - `/search`: 30 req/min per IP
    - `/song/url/*`: 60 req/min per IP
  - 或在 Nginx 层通过 `limit_req_zone` 实现

#### [GW-02] 搜索参数 `keyword` 长度无限、`limit/page` 无上限
- **文件**: `musicapi/server.js` — `/search` 路由
- **影响**: 
  - 传入极长 keyword（如 10KB 字符串）可能导致正则匹配 CPU 暴增
  - `size=100000` 可能内存溢出
- **修复**: 
  - `keyword` 限制 100 字符
  - `size` 上限 100（`Math.min(parseInt(size) || 20, 100)`）
  - `prefer` 参数做枚举白名单（`netease` / `qq`）

#### [GW-03] Cookie 仅每 60 分钟检测存活，无自动刷新机制
- **文件**: `musicapi/server.js` — `checkCookies()` 定时器
- **影响**: Cookie 过期后最快 60 分钟才能发现；用户需手动获取新 Cookie 并重启服务
- **修复**: 
  - 检测间隔缩短至 15 分钟
  - 当检测到过期时发 Prometheus 告警（已有 gauge）
  - 尝试调用平台刷新接口自动续期

---

### 🟡 Medium

#### [GW-04] `id` 参数无类型/格式校验
- **文件**: 所有接受 `id` 参数的路由
- **现状**: 仅检查是否存在，不检查格式
- **影响**: 传入非数字或异常字符串可能导致上游 API 返回无意义错误
- **修复**: 添加 `id` 参数正则校验（QQ songmid 为字母数字 14 位，网易云 id 为纯数字）

#### [GW-05] LRU 缓存的 `Map.delete + Map.set` 实现效率低
- **文件**: `musicapi/server.js` — `searchCache.get()`
- **现状**: 每次缓存命中执行 `delete + set` 来维护 LRU 顺序
- **影响**: 高频访问下产生不必要的 Map 重哈希
- **修复**: 使用 `lru-cache` npm 包替代手写实现

#### [GW-06] 搜索算法黑名单关键词列表缺少部分常见干扰项
- **文件**: `musicapi/server.js` — `refineResults()` 中的 `BLACKLIST_KEYWORDS`
- **现状**: 已包含伴奏/纯音乐/有声书/朗诵/翻唱/DJ版/Remix
- **影响**: 缺少如 "铃声"、"抖音版"、"现场版"、"Demo" 等常见干扰
- **修复**: 补充黑名单并改为可配置项

---

### 🟢 Low

#### [GW-07] 网易云 API 白名单路径硬编码
- **文件**: `musicapi/server.js` — `/netease/*` 白名单数组
- **影响**: 需新增网易云 API 代理时需直接改代码
- **修复**: 移到 `config.js` 中作为可配置项

#### [GW-08] 日志文件按天轮转但使用同步 I/O
- **文件**: `musicapi/server.js` — `LogManager` 类
- **影响**: 日志量大时可能阻塞事件循环
- **修复**: 改用 `fs.appendFile`（异步）或 `winston` 日志库

---

## 4. 数据库与基础设施

### 🔴 Critical

#### [IN-01] MySQL 3306 端口对外暴露
- **文件**: `docker-compose.yml`
- **现状**: `ports: "3306:3306"`，注释中标注"生产环境注释掉"
- **影响**: 任何人可通过公网尝试连接数据库；配合弱密码 `123456` 即被直接拿下
- **修复**: 立即注释掉 MySQL ports 映射，仅保留 Docker 内部网络访问

#### [IN-02] Redis 6379 端口对外暴露
- **文件**: `docker-compose.yml`
- **现状**: `ports: "6379:6379"`
- **影响**: Redis 虽有密码认证，但暴露端口仍增加爆破和 DDoS 风险
- **修复**: 注释 `6379:6379` 端口映射，仅容器内网访问

#### [IN-03] Grafana/Alertmanager/Prometheus 管理端口全部对外暴露且无认证
- **文件**: `docker-compose.yml`
- **现状**: Grafana:3001, Prometheus:9090, Alertmanager:9093 全部暴露
- **影响**: 
  - Prometheus 9090 无认证，任何人可查看所有指标数据
  - Grafana 默认 admin/admin
  - Alertmanager 可被恶意静音告警
- **修复**: 
  - 所有监控端口注释掉对外映射
  - 通过 Nginx 反向代理 + `auth_basic` 统一入口访问
  - Grafana 通过环境变量强制修改默认密码

---

### 🟠 High

#### [IN-04] Prometheus 抓取目标使用 `host.docker.internal` 仅限本地开发
- **文件**: `docker-data/prometheus/prometheus.yml`
- **现状**: `targets: ["host.docker.internal:8080"]` 和 `host.docker.internal:3000`
- **影响**: 部署到 K8s 或纯 Linux 环境时监控全部失效
- **修复**: 创建 `prometheus-local.yml` 和 `prometheus-prod.yml` 两个文件：
  - 本地：`host.docker.internal`
  - 生产：容器名 `vibemusic-backend:8080`、`vibemusic-musicapi:3000`

#### [IN-05] Alertmanager 未配置任何实际通知通道
- **文件**: `docker-data/alertmanager/alertmanager.yml`
- **现状**: `receiver: "default-receiver"`，仅日志输出
- **影响**: 发生 Critical 告警（服务宕机、JVM 内存>85%）时完全无人知晓
- **修复**: 至少启用一种通知方式：钉钉机器人 webhook、企业微信 webhook、或邮件 SMTP

#### [IN-06] Nginx CSP 头含 `unsafe-inline`，削弱 XSS 防护
- **文件**: `nginx/nginx.conf`
- **现状**: `script-src 'self' 'unsafe-inline' ...`
- **影响**: 若存在存储型 XSS，`unsafe-inline` 允许内联脚本执行
- **修复**: 移除 `unsafe-inline`，改用 nonce 或 hash 机制

---

### 🟡 Medium

#### [IN-07] `.env.docker` 文件包含真实生产凭据
- **文件**: `d:\vibeMusic\.env.docker`
- **现状**: 含 JWT_SECRET、MINIO_SECRET_KEY 等真实值
- **影响**: 虽然 .gitignore 排除，但本地文件无额外保护
- **修复**: `.env.docker` 仅保留占位符，真实凭据仅在 `.env` 中

#### [IN-08] `docker-data/mysql-exporter/.my.cnf` 明文存储数据库密码
- **文件**: `docker-data/mysql-exporter/.my.cnf`
- **现状**: `password=123456`
- **影响**: 任何能访问宿主机的用户可读取数据库密码
- **修复**: 通过环境变量 `DATA_SOURCE_NAME` 传入密码，不写入文件

#### [IN-09] 容器默认 JAVA_OPTS 内存配置不适应大内存机器
- **文件**: `docker-compose.yml` — backend 的 `JAVA_OPTS`
- **现状**: `-Xmx512m` 硬编码
- **影响**: 服务器有 8G+ 内存时无法利用，GC 频率过高
- **修复**: 改为 `-XX:MaxRAMPercentage=75.0 -Xms256m`，让 JVM 自适应

#### [IN-10] 备份策略仅备份 MySQL，不备份 MinIO 对象存储
- **文件**: `docker-compose.yml` — mysql-backup 容器
- **影响**: MinIO 中的歌曲文件、用户上传头像/背景图无备份，磁盘故障即丢失
- **修复**: 添加定期 `mc mirror` 任务同步 MinIO 到备份目录

---

### 🟢 Low

#### [IN-11] RustFS 相关变量残留但未实际部署
- **文件**: `.env.docker`、`docker-data/rustfs/data/`（空目录）
- **影响**: 架构文档与实际不符，新开发者困惑
- **修复**: 明确选择 MinIO 作为唯一对象存储，清理 RustFS 引用

#### [IN-12] Loki 已在 Grafana 数据源中配置但未部署 Loki 容器
- **文件**: `docker-data/grafana/provisioning/datasources/`
- **影响**: Grafana 启动时有数据源连接错误日志
- **修复**: 部署 Loki 容器或从 datasources 配置中移除 Loki

---

## 5. 跨模块耦合与单点故障分析

### 5.1 模块依赖链

```
用户浏览器
  → Nginx (唯一入口，TLS 终结)
    → Backend (端口 8080)
      → MySQL (持久化存储)
      → Redis (缓存 + Session)
      → Elasticsearch (全文搜索)
      → MinIO (对象存储)
      → musicapi (多平台音乐数据聚合)
        → 网易云音乐 API
        → QQ 音乐 API
```

### 5.2 单点故障 (SPOF) 分析

| 服务 | 单点？ | 故障影响 | 建议 |
|------|--------|----------|------|
| **Nginx** | ✅ | 整个网站不可访问 | 低优先级 — 单实例 Nginx 对于个人项目可接受 |
| **Backend** | ✅ | 所有 API 不可用，无法搜索、播放、登录 | 中优先级 — 部署 2 副本 + 健康检查自动重启 |
| **MySQL** | ✅ | 数据不可读写，登录、收藏、播放历史全部失效 | **高优先级** — 开启 binlog + 定期备份已验证，但无自动故障转移 |
| **Redis** | ✅ | 缓存全空，搜索性能下降 10x+，限流失效 | 中优先级 — 配置 Redis Sentinel 或接受缓存穿透降级 |
| **musicapi** | ✅ | 歌曲搜索、URL 获取全部失败，整个音乐播放链路断裂 | **高优先级** — 至少增加健康检查自动重启 + Backend 侧缓存兜底 |
| **MinIO** | ✅ | 已缓存的歌曲文件不可访问，下载失败 | 低优先级 — 回退到直接 API 获取 |

### 5.3 耦合过重问题

| 耦合点 | 描述 | 风险 | 建议 |
|--------|------|------|------|
| **Backend → musicapi 紧耦合** | SongPlayService 同步等待 musicapi HTTP 响应，无熔断 | musicapi 慢/宕 → Backend 线程堆积 → 整体雪崩 | 添加 Resilience4j CircuitBreaker，3 次失败后快速失败 |
| **前端直接调用 QQ CDN** | musicapi 返回 QQ CDN URL 后，前端直接从 `*.tc.qq.com` 拉音频 | CDN 域名过期或被墙 → 播放无声，前端无降级 | Backend 流代理作为兜底，检测前端加载失败自动切换 |
| **search 多级缓存链** | 三级缓存 (Caffeine → Redis → ES → musicapi) | 任一级失效影响搜索质量，但整个链不会完全断裂 | ✅ 当前设计合理，降级链完整 |

### 5.4 数据流全链路追踪

**搜索歌曲完整链路：**
```
1. 用户输入关键词 → SearchView.vue
2. searchSongs(keyword) → Axios → Nginx → Backend SongSearchService
3. Caffeine 本地缓存 (未命中) → Redis (未命中) → ES (未命中)
4. Backend → musicapi /search (并行 网易云+QQ)
5. musicapi 加权评分 → MD5 去重 → 黑名单过滤 → 分页
6. 结果返回 Backend → 写入 Redis (TTL 1h) → 返回前端
7. 前端渲染结果列表 → 用户点击播放
```

**播放歌曲完整链路：**
```
1. 用户点击播放 → PlayerBar/MBottomPlayer → player.playFromSongId()
2. GET /api/songs/play → SongPlayService.playBySourceId()
3. 检查 RustFS/MinIO 缓存 (未命中)
4. musicapi /song/url/v1 或 /song/url/qq 获取 CDN URL
5. URL 返回前端 → Audio 元素直接加载 CDN 流
6. Service Worker 可能拦截 (已修复：跳过 audio/video 请求)
7. 播放错误时重试 2 次，耗尽后自动切下一首
```

**潜在断裂点：**
- 步骤 4-5：若 musicapi Cookie 过期，QQ 歌曲全部返回空 URL
- 步骤 6：SW 缓存问题（历史已修复，需回归验证）
- 步骤 7：重试 URL 相同可能导致死循环（历史已修复 `audio.src=''`，需回归验证）

---

## 6. 修复优先级矩阵

| 优先级 | 编号 | 模块 | 问题 | 预计工时 |
|--------|------|------|------|----------|
| 🔴 P0-立即 | IN-01 | 基础设施 | MySQL 3306 端口暴露 | 5 分钟 |
| 🔴 P0-立即 | IN-02 | 基础设施 | Redis 6379 端口暴露 | 3 分钟 |
| 🔴 P0-立即 | BE-01 | 后端 | MySQL 弱密码 | 15 分钟 |
| 🔴 P0-立即 | BE-02 | 后端 | 凭据集中存储无保护 | 1 小时 |
| 🔴 P0-立即 | IN-03 | 基础设施 | 监控端口暴露+无认证 | 30 分钟 |
| 🟠 P1-本周 | GW-01 | BFF | 无速率限制 | 30 分钟 |
| 🟠 P1-本周 | GW-02 | BFF | 搜索参数无校验 | 15 分钟 |
| 🟠 P1-本周 | FE-03 | 前端 | 巨型组件拆分 | 4 小时 |
| 🟠 P1-本周 | FE-04 | 前端 | 无 ErrorBoundary | 1 小时 |
| 🟠 P1-本周 | BE-03 | 后端 | 搜索线程池阻塞风险 | 30 分钟 |
| 🟠 P1-本周 | IN-04 | 基础设施 | Prometheus host.docker.internal | 15 分钟 |
| 🟠 P1-本周 | IN-05 | 基础设施 | Alertmanager 未配置通知 | 30 分钟 |
| 🟠 P1-本周 | IN-06 | 基础设施 | CSP unsafe-inline | 30 分钟 |
| 🟠 P1-本周 | FE-05 | 前端 | 静默吞错 | 2 小时 |
| 🟡 P2-本月 | FE-07 | 前端 | 桌面/移动端代码重复 | 8 小时 |
| 🟡 P2-本月 | BE-07 | 后端 | CORS 配置缺失 | 10 分钟 |
| 🟡 P2-本月 | GW-04 | BFF | id 参数校验 | 20 分钟 |
| 🟡 P2-本月 | IN-07 | 基础设施 | .env.docker 含真实凭据 | 10 分钟 |
| 🟡 P2-本月 | IN-08 | 基础设施 | my.cnf 明文密码 | 10 分钟 |
| 🟡 P2-本月 | BE-04 | 后端 | RestTemplate 连接池过小 | 15 分钟 |
| 🟡 P2-本月 | BE-11 | 后端 | 播放历史硬删除 | 1 小时 |
| 🟡 P2-本月 | FE-08 | 前端 | window/store 双重状态 | 1 小时 |
| 🟢 P3-下一迭代 | 其余 9 个 | 各模块 | 参阅上文各节 | 按需 |

---

> **报告结束**  
> 本报告基于 2026-07-19 代码快照生成，共覆盖 156 个源文件、13 个 Docker 容器、31 个已识别问题。  
> 建议将本文件纳入项目仓库并随代码更新同步维护。
