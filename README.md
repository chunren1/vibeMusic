# vibeMusic

自建全栈音乐平台，支持网易云 + QQ 音乐双源聚合搜索与高品质播放。不依赖第三方破解，使用自有 VIP 账号合法获取音乐资源。

## 技术架构

```
┌──────────────────────────────────────────────┐
│         Nginx (port 80) 统一入口              │
│   静态文件 serve + API 反向代理 + SPA fallback │
└──────────────────────┬───────────────────────┘
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
┌──────────────────┐    ┌──────────────────────────┐
│  Vue 3 + Vite     │    │  Spring Boot 4 + Java 17  │
│  构建产物 (dist/)  │    │  MyBatis-Plus + JWT       │
│  + Capacitor APK  │    │  (localhost:8080)         │
└──────────────────┘    └──────────┬───────────────┘
                                   │
                     ┌─────────────┴─────────────┐
                     ▼                           ▼
            ┌─────────────────┐        ┌────────────────────────┐
            │    musicapi      │        │  MySQL + Redis + ES    │
            │  Express.js 网关  │        │  数据持久化 + 三级缓存  │
            │  (localhost:3000)│        │  ES:9201 IK 分词搜索   │
            │ 网易云 + QQ音乐   │        └────────────────────────┘
            │ Cookie 统一管理   │
            └─────────────────┘
```

## 目录结构

```
vibeMusic/
├── .github/workflows/
│   └── test.yml                    # GitHub Actions CI 自动化测试
├── .gitignore                      # 排除 target/node_modules/Docker数据/日志等
├── package.json                    # 工作区根配置（统一 npm scripts）
├── docker-compose.yml              # Docker 编排 (Nginx + MySQL + Redis + MinIO + ES)
├── nginx/
│   └── nginx.conf                  # Nginx 统一入口 + 反向代理配置
├── .env.example                    # 环境变量示例
├── docker-data/                    # Docker 数据持久化目录
│   ├── mysql/data/                 # MySQL 8.0 数据
│   ├── redis/data/                 # Redis 数据 + AOF
│   ├── elasticsearch/              # ES 索引 + IK 插件
│   ├── rustfs/data/                # MinIO 对象存储
│   └── nginx/logs/                 # Nginx 访问 & 错误日志
├── musicapi/                       # Node.js 音乐 API 聚合网关 (端口 3000)
│   ├── Dockerfile                  # 生产环境容器化部署
│   ├── server.js                   # 聚合搜索 + 音质分级 + Cookie 注入 + 监控 + 日志
│   ├── config.js                   # 网易云 + QQ 音乐 Cookie 统一管理中心
│   └── logs/                       # 分类日志
├── vibeMusic-backend/              # Spring Boot 后端 (端口 8080)
│   ├── Dockerfile                  # 生产环境容器化部署
│   ├── sql/init.sql                # 数据库完整初始化脚本（6张表 + 默认管理员）
│   ├── uploads/avatars/            # 用户上传的头像和背景图
│   └── src/
│       ├── main/java/com/vibemusic/
│       │   ├── controller/         # Auth, Song, Favorite, Playlist, Download, Recommend, Proxy
│       │   ├── service/            # 14 个服务类（SongService/SongSearch/SongPlay 已拆分）
│       │   ├── entity/             # User, Song, Playlist, PlaylistSong, UserFavorite, PlayHistory
│       │   ├── dto/                # SongDTO, RecommendResult
│       │   ├── config/             # Security, Cors, AudioQualityTier, NeteaseApi, Web
│       │   └── security/           # JwtAuthFilter, CustomUserDetails
│       └── test/java/com/vibemusic/ # 测试代码
│           ├── BaseTest.java       # 测试基类 (H2 内存数据库)
│           ├── TransactionalServiceTest.java  # 事务型测试基类
│           ├── controller/         # AuthControllerTest, SongControllerTest (MockMvc)
│           └── service/            # 5 个 Service 单元测试类
├── vibemusic-web/                  # Vue 3 前端 (端口 5173)
│   └── src/
│       ├── views/                  # 桌面端 (/) + 移动端 (/m) 视图
│       ├── components/             # PlayerBar, LyricsView, LoginModal + mobile/ 组件
│       │   └── __tests__/          # PlayerBar 组件单测 (20 cases)
│       ├── stores/                 # Pinia: auth, player, favorite, recommend
│       │   └── __tests__/          # PlayerStore 单测 (21 cases)
│       ├── composables/            # useAudioBackground, useClickOutside, useIsMobile, useToast, useVirtualList
├── test-setup.js                # 测试全局配置 (Mock Audio/composables/API)
│       ├── directives/             # v-lazy-img 图片懒加载指令
│       ├── router/                 # 路由定义（桌面/移动自动分流）
│       └── api/                    # Axios 封装 + 接口定义
├── scripts/                        # 运维工具
│   ├── ops.cjs                     # 跨平台运维面板（npm run ops）
│   ├── k6-test.js                  # K6 压力测试脚本 (50 VU 搜索+播放+收藏)
│   ├── docker.ps1                  # Docker 管理脚本
│   └── ops/                        # Python 运维脚本
├── NOTICE.md                        # 法律风险声明
└── TEST_REPORT.md                  # 全链路测试报告
```

## 核心功能

| 功能 | 说明 |
|------|------|
| 🔍 多平台搜索 | 网易云 + QQ 音乐聚合，4策略降级链(cloudsearch/search × 带Cookie/无Cookie)，8级指数退避重试，信息指纹去重，封面 HTTP→HTTPS 自动升级 |
| 🎯 个性化推荐 | 播放历史歌手权重聚合 + RustFS 缓存优先 + Redis 缓存 (user 6h / guest 10min) + 缓存污染自动清理 |
| 🏷️ 音质 SLA 分级 | LOCAL→HIRES→EXHIGH→HIGHER→STANDARD→FALLBACK 六级，逐级降级 + 统计 |
| ▶️ VIP 音质播放 | 自有 VIP Cookie，HIRES / EXHIGH / HIGHER / STANDARD 品质 |
| 🔐 Cookie 统一管理 | 网易云 + QQ Cookie 集中在 musicapi/config.js，后端零持有，启动自动监控 |
| ❤️ 收藏管理 | Pinia Store 全局同步，乐观更新 + 回滚，10 个组件统一接入 |
| 📋 歌单管理 | 创建/删除/添加歌曲，完整 CRUD，所有权校验 |
| 🕐 最近播放 | 播放历史，自动保留近 300 条，用户隔离 |
| 📱 移动端适配 | 路由分流 `/m`，独立 UI 组件，共享 API/Store，封面 HTTP→HTTPS 升级，避免混合内容拦截 |
| 🎵 歌词页 | 封面旋转 + 歌词同步滚动 + 进度条 + 收藏/下载/模式切换 |
| 📜 播放队列 | 自动入列，底部弹出，去重管理，刷新恢复播放进度 |
| 🤖 Android APK | Capacitor 打包，公网接口，安装即用 |
| 🌐 内网穿透 | cpolar 隧道 + Cloudflare 备用方案 |
| 🛡️ Cookie 监控 | 随 API 启动自动检查 + 每小时巡检，日志写入 musicapi/logs/ |
| 💾 RustFS 离线缓存 | 用户主动下载→存入对象存储，播放优先直读（零 API 调用），上传前去重 |
| 🎚️ 进度条拖拽 | 桌面端 mouse/move/up + 移动端 touch/move/end，实时时间气泡 + 拇指放大动画 |
| 🎨 响应式 UI | 桌面侧栏 + 移动底部 TabBar，统一暗色主题 |
| 🖼️ SVG 图标系统 | 全局 SvgIcon 组件，17 个内联 symbol，color/currentColor 自适应主题，spin 动画 |
| 🎨 简约风图标 | 全站 emoji 图标替换为 SVG：搜索/收藏/下载/加入歌单/移除等 |
| 👤 用户头像 | 首页 + TopBar 真实头像展示，无头像回退文字 |
| 🖼️ 背景图 | 个人页全宽背景图，无图时绿色渐变兜底 |
| 🔁 幂等防护 | X-Request-Id + Redis 5min 去重，防止快速连点重复请求 |
| 🗄️ Flyway 版本控制 | 数据库 schema 像代码一样版本化 (V1__init.sql)，baseline-on-migrate |
| 🐛 Sentry 错误监控 | 前端 JS 崩溃自动上报 (VITE_SENTRY_DSN)，Android/iOS 全平台覆盖 |
| 📊 K6 压力测试 | scripts/k6-test.js，50 VU 模拟搜索→播放→收藏全链路 |
| 🛡️ 安全合规 | NOTICE.md 法律声明；Cookie/密钥全量 gitignore；binary/jar 仓库清理 |

## 快速开始

### 环境要求

- Java 17+
- Node.js 20+
- Docker Desktop

### 1. 安装依赖

```bash
npm install              # 安装 concurrently（工作区编排）
npm run install:all      # 安装 musicapi + 前端全部依赖
```

### 2. 启动中间件

```bash
npm run docker:dev        # 开发模式：仅起中间件 (MySQL + Redis + ES + MinIO + Nginx)
# 生产模式启动全栈（含 backend + musicapi）：
npm run docker:up
# MinIO 管理: http://localhost:9001 (rustfsadmin/rustfsadmin)
# 数据库自动初始化，默认管理员: admin / 123456
```

### 生产部署

```bash
npm run build             # 构建前端到 dist/
npm run docker:up         # 启动所有 Docker 服务（含 Nginx 80 端口）
# 访问 http://localhost 即进入生产模式
```

Nginx 处理：
- `/` — SPA 路由 fallback → `index.html`
- `/assets/*` — Vite 产物，长缓存（1年）
- `/api/*` — 反向代理 → Spring Boot (8080)，透传 JWT Authorization
- `/api/songs/stream` — 音频流，支持 Range 断点续传，关闭缓冲
- `/uploads/*` — 用户上传静态资源

### 安全配置
- **JWT httpOnly Cookie**：登录/注册时后端设置 `VIBE_TOKEN` Cookie（HttpOnly + SameSite=Strict），前端不再读写 localStorage，防止 XSS 窃取
- **认证降级**：JWT Filter 优先读 Authorization Header，降级读 Cookie
- **会话恢复**：前端 onMount 调用 `/auth/me` 从 Cookie 恢复登录态
- **幂等防护**：写请求自动添加 X-Request-Id（UUID v4 兼容降级），Redis 5min 去重
- **密码加密**：BCrypt (`$2b$10$`) 存储，明文永不出现在数据库中

### 3. 启动后端

```bash
cd vibeMusic-backend
mvn spring-boot:run
```

### 4. 启动前端

```bash
npm run dev              # concurrently 同时启动 musicapi(3000) + 前端(5173)
```

### 5. 内网穿透（可选）

```bash
npm run tunnel           # 启动 cpolar http 5173（需先安装 cpolar）
```

## npm 命令一览

| 命令 | 功能 |
|------|------|
| `npm run dev` | 并发启动 musicapi + 前端 |
| `npm run dev:api` | 单独启动 musicapi |
| `npm run dev:web` | 单独启动前端 |
| `npm run test` | 全量测试（后端 42 + 前端 41） |
| `npm run test:backend` | 后端 Maven 测试 |
| `npm run test:frontend` | 前端 Vitest 测试 |
| `npm run docker:dev` | 开发模式：启动中间件 (不拉 backend/musicapi) |
| `npm run docker:up` | 生产模式：全栈部署 (--build 重建镜像) |
| `npm run docker:down` | 停止所有 Docker 服务 |
| `npm run docker:restart` | 重建并重启 (--build) |
| `npm run docker:status` | 查看容器状态 |
| `npm run docker:logs` | 查看容器日志 |
| `npm run docker:build` | 全量重建镜像 (--no-cache) |
| `npm run build` | 构建前端生产包 |
| `npm run deploy` | 构建前端 + 全栈部署 |
| `npm run deploy:web` | 构建前端 + 热重载 Nginx |
| `npm run nginx:reload` | 热重载 Nginx 配置 |
| `npm run ops` | 运维面板 |
| `npm run tunnel` | 启动 cpolar 内网穿透 |
| `npm run install:all` | 安装全部依赖 |
| `k6 run scripts/k6-test.js` | 压力测试 (50 VU 并发) |

## API 端点

### 后端 (Spring Boot, port 8080)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录，返回 JWT |
| `/api/auth/me` | GET | 当前用户信息（含 avatar/bgImage/gender/birthday） |
| `/api/auth/change-password` | POST | 修改密码 |
| `/api/auth/profile` | PUT | 更新个人资料 |
| `/api/auth/avatar` | POST | 上传头像（multipart, ≤2MB） |
| `/api/auth/bg-image` | POST | 上传背景图 |
| `/api/songs/search` | GET | 聚合搜索（三级缓存：Redis→ES→API） |
| `/api/songs/es-health` | GET | ES 健康检查（集群状态 + 可用性） |
| `/api/auth/me` | GET | 用户信息（含 avatar/bgImage） |
| `/api/auth/bg-image` | POST | 上传背景图（个人页全宽展示） |
| `/api/songs/play` | GET | 记录播放历史 + 返回 RustFS 缓存状态 |
| `/api/songs/stream` | GET | 音频流代理（支持 Range/RustFS 直读） |
| `/api/songs/random` | GET | 随机推荐 |
| `/api/songs/banner` | GET | 首页轮播推荐 |
| `/api/recommend/personalized` | GET | 个性化推荐（播放历史聚合 + Redis 缓存） |
| `/api/favorites/toggle` | POST | 收藏切换（乐观更新 + 幂等防护） |
| `/api/favorites/list` | GET | 收藏列表 |
| `/api/favorites/ids` | GET | 收藏 ID 集合（全局状态同步） |
| `/api/playlists/list` | GET | 我的歌单列表 |
| `/api/playlists/create` | POST | 创建歌单 |
| `/api/playlists/songs` | GET | 歌单歌曲 |
| `/api/playlists/add-song` | POST | 添加歌曲到歌单 |
| `/api/playlists/remove-song` | DELETE | 从歌单移除 |
| `/api/playlists/delete` | DELETE | 删除歌单 |
| `/api/playlists/detail` | GET | 平台歌单详情（网易云/QQ） |
| `/api/playlists/recommend` | GET | 推荐歌单（30个随机取6） |
| `/api/download/{sourceId}` | POST | 下载歌曲到 RustFS |
| `/api/download/check/{sourceId}` | GET | 检查歌曲是否已下载 |
| `/api/download/file/{sourceId}` | GET | RustFS 文件流下载 |
| `/api/songs/history` | GET | 播放历史（去重 + 概率清理） |
| `/api/songs/lyric` | GET | 歌词代理（网易云 + QQ） |
| `/api/image-proxy` | GET | 图片代理（白名单网易云CDN） |
| `/api/auth/logout` | POST | 退出登录（清除 httpOnly Cookie） |
| `/api/assistant/chat` | POST | AI 音乐助手（SiliconFlow Qwen） |
| `/api/monitor/cache-stats` | GET | 缓存命中率统计 |

### musicapi (Express, port 3000)

| 端点 | 说明 |
|------|------|
| `/search` | 多平台聚合搜索 |
| `/lyric` | 歌词查询 |
| `/netease/search` | 网易云搜索（4策略降级 + HTTP→HTTPS封面升级） |
| `/qq/search` | QQ 音乐搜索 |
| `/cloudsearch` | 网易云单平台搜索 |
| `/song/url/v1` | 网易云播放 URL |
| `/song/url/qq` | QQ 音乐播放 URL |
| `/personalized` | 网易云个性化推荐 |
| `/netease/*` | 网易云通用代理（任意 NeteaseCloudMusicApi 方法） |
| `/qq/*` | QQ 音乐通用代理 |
| `/qq/playlist` | QQ 音乐歌单详情 |
| `/song/detail` | 歌曲详情 |
| `/cookie-status` | Cookie 存活状态 |
| `/health` | 健康检查（Cookie 状态 + 缓存 + uptime） |

## 个性化推荐引擎

```
播放行为 → play_history 表 → 歌手兴趣权重聚合 → 优先匹配 RustFS 已缓存歌曲
                                                    ↓
未登录/无历史 → getRandomSongs("热歌") 降级 ← Redis 缓存 (user:6h / guest:10min)
```

- 播放后异步清除推荐缓存，确保推荐随行为更新
- 换一批传 `refresh=true` 跳过 Redis 缓存重新生成
- 异常降级：推荐失败 → `getRandomSongs()` 兜底，Redis 异常 → 跳过缓存
- **缓存污染自动清理**：检测到全平台覆盖 → 自动删除 + 重建（解决 API 宕机时缓存毒化）

## 搜索三级缓存 (Redis → ES → API)

```
请求 → ① Redis v4 前缀 (TTL 1h/30s)       ← 最快
     → ② ES 8.18 search_cache (IK 分词)    ← 毫秒级
     → ③ musicapi 实时聚合 (网易云+QQ)      ← 兜底
              ↓
     写入: ES bulk (ndjson) + Redis 同步缓存
```
- **ES 增强搜索**：IK 分词 + 高亮标记 + 平台聚合统计
- **定时清理**：每 6 小时删除 6 小时前的 ES 缓存数据
- **缓存污染检测**：Redis 单平台缓存自动删除 + 重搜
- **版本隔离**：前缀 v4 自然淘汰旧缓存

### 容错设计
```
ES 不可用 → `AtomicBoolean available` 标记 → 自动跳过 ES 层，直接降级到 musicapi
ES 写入失败 → WARN 日志（不抛异常），搜索结果正常返回，用户无感知
ES 重启    → 数据卷持久化，索引数据不丢失，健康检查 `isAvailable()` 自动重检
```
- **健康检查**：`GET /api/songs/es-health` 返回集群状态 + 可用性
- **分层日志**：`[CACHE-LAYER]` / `[ES-LAYER]` / `[API-LAYER]` 标记每次搜索命中层 + 耗时

## 音质 SLA 分级 (AudioQualityTier)

```
LOCAL (RustFS 直出) → HIRES (96kHz/24bit) → EXHIGH (48kHz)
  → HIGHER (320kbps) → STANDARD (128kbps) → FALLBACK (最终兜底)
```

- 六级音质枚举，逐级降级：高音质不可用自动尝试下一级
- 网易云全试听 → QQ 音乐跨平台降级 → DB 历史 URL 兜底
- 每次降级记录 `degradationCount` 统计
- 前端 PlayerBar / MPlayerView 实时显示音质标签

## Cookie 统一管理中心

```
musicapi/config.js
├── netease: "MUSIC_U=..."
└── qq: { uin, qqmusic_key, psrf_* ... }
         │
    server.js 启动注入
         │
    ┌─────┴─────┐
    ▼            ▼
withNeteaseCookie()  qqMusic.setCookie()
```

- 所有 Cookie 集中一处，后端 `application.yml` / `NeteaseApiConfig` 已移除 cookie 字段
- API 启动时自动执行 Cookie 存活检查，之后每小时一次
- `GET /cookie-status` 查询实时状态

## Pinia Favorite Store (收藏状态全局同步)

- **单一数据源**: `stores/favorite.js` 集中管理收藏 ID 集合（reactive Set）
- **全局实时同步**: 任何页面收藏/取消收藏，其他页面即时响应式更新
- **乐观更新 + 回滚**: 先更新 UI，API 成功后以后端为准
- **跨 10 个组件统一**: 桌面端 6 个 + 移动端 4 个全部从 Store 读写

## Pinia Player Store (播放状态管理中心)

- 全局单例 Audio 元素，跨页面共享播放状态
- 播放进度 5 秒定时保存 + `beforeunload` 同步持久化
- 刷新后 `loadedmetadata` 事件恢复播放位置
- 队列/模式/音量全部持久化到 localStorage

## RustFS 离线兜底

**仅用户主动下载时存入 RustFS，播放不自动下载。**

```
播放请求 → ① RustFS 缓存? → 直接返回（零 API 调用，最高 SLA）
         → ② API 获取在线 URL → 音质逐级降级
         → ③ DB 历史 URL → 兜底
         → ④ stream 远程代理
```

- 支持 HTTP Range 请求，拖拽进度条秒级响应
- 上传前去重：`StorageService.exists()` + `source_id UNIQUE`
- 文件名格式：`歌手 - 歌曲名.mp3`

## 运维体系

### 日常命令

```bash
npm run ops             # 跨平台运维面板
npm run backup:db       # 数据库备份 (mysqldump + gzip，保留 7 天)
npm run health          # 容器健康检查 (每 5 分钟定时运行)
npm run tunnel          # 启动 cpolar 内网穿透
npm run deploy:full     # 一键部署 (测试 → 构建 → Docker 部署)
```

### CI/CD 流水线

| 流水线 | 触发条件 | 流程 |
|--------|----------|------|
| `test.yml` | push/PR to main | 后端 JUnit + 前端 Vitest → 自动跑 63 条测试 |
| `deploy.yml` | 手动触发 / 推送 tag(v*) | 构建 JAR + dist → Docker 镜像 → 部署 → 健康检查 |

### 自动化运维

```bash
# 建议设置 Windows 任务计划：
# 每天 4:00   → npm run backup:db     (数据库备份)
# 每 5 分钟   → npm run health        (健康监控)

# Docker 日志轮转 (自动)
# docker-compose.yml 已配置：
#   max-size: 50MB  (单文件上限)
#   max-file: 3     (保留 3 个轮转文件)
#   适用服务: nginx / backend / musicapi / elasticsearch
```

### 备份恢复

```bash
# 备份
npm run backup:db           # → docker-data/backups/vibemusic_20260616_040000.sql.gz

# 恢复
gunzip vibemusic_20260616_040000.sql.gz
docker exec -i vibemusic-mysql mysql -uroot -p123456 vibemusic < vibemusic_20260616_040000.sql
```

### 日志分类 (musicapi/logs/)

| 日志文件 | 内容 |
|----------|------|
| `access.log` | HTTP 请求记录 |
| `api-errors.log` | API 调用异常 |
| `cookie-monitor.log` | Cookie 存活检查 |
| `degradation.log` | 音质降级事件 |

## 审计项
- **ES 宕机降级**：`isAvailable()` 自动降级跳过 ES 层

---

## 测试体系

```
全链路 83 条测试
├── 后端 42 条 (JUnit 5 + MockMvc + H2 内存数据库)
│   ├── 单元测试 25+2 条 (2 条因 MySQL ON DUPLICATE KEY 语法 H2 不支持而跳过)
│   │   ├── UserServiceTest        (9)  注册/登录/修改密码/更新资料
│   │   ├── SongServiceTest         (3+2) 歌曲查询/H2兼容跳过
│   │   ├── FavoriteServiceTest     (6)  收藏/取消/列表/去重
│   │   ├── PlayHistoryServiceTest  (4)  播放记录/去重/上限
│   │   └── PlayHistoryCleanupServiceTest (2) 定时清理
│   └── 集成测试 15 条 (MockMvc)
│       ├── AuthControllerTest      (4)  注册校验/未登录拦截
│       └── SongControllerTest     (11)  搜索/播放/流/歌词/健康检查
├── 前端 41 条 (Vitest + jsdom)
│   ├── PlayerStore Test           (21) 队列操作/切歌/模式/持久化
│   └── PlayerBar Test             (20) 渲染/面板/控制/音量
└── CI 自动化 (GitHub Actions)
    └── push/PR → 自动跑后端 + 前端测试
```

### 运行测试

```bash
npm test                    # 全部测试
npm run test:backend        # 仅后端 (mvn test)
npm run test:frontend       # 仅前端 (vitest run)
cd vibemusic-web && npm run test:watch   # 前端监听模式
```

详细报告见 `TEST_REPORT.md`。

---

## 代码审查改进记录

### 2026-06-22 MySQL 性能优化

#### 🔍 慢查询监控
| 改进项 | 说明 |
|--------|------|
| MySQL slow_query_log | 开启慢查询日志，`long_query_time=1`，未使用索引的查询一并记录 |
| 日志落盘 | 输出到 `/var/lib/mysql/slow.log`，配合 Docker 日志驱动轮转 |

#### 📊 索引优化
| 改进项 | 说明 |
|--------|------|
| 删除冗余索引 x3 | `playlist_song.idx_playlist_id` / `user_favorite.idx_user_id` / `play_history.idx_user_id` 均被联合索引前缀覆盖 |
| 新增索引 x2 | `song.idx_name(50)` 歌曲名搜索 + `song.idx_created_at` 排序查询，Flyway V2 迁移 |
| Flyway V2__optimize.sql | 新增数据库迁移脚本，随后端启动自动执行 |

#### 🧹 SQL 优化
| 改进项 | 说明 |
|--------|------|
| PlaylistMapper 去重 | 移除 Java @Select 注解中与 XML 完全重复的 SQL，统一在 XML 维护 |
| 索引依赖标注 | XML 注释说明 `idx_user_created` + `idx_pl_added` 索引对关联子查询的加速原理 |

#### ⚡ I/O 性能优化
| 改进项 | 说明 |
|--------|------|
| DownloadService 事务拆分 | HTTP/文件 I/O 移出事务外（30s+），DB 持久化在短事务内（<50ms） |
| RecommendService statObject → DB | `markOfflineStatus()` 从 N 次 MinIO statObject 改为 1 次 DB 批量查询 |
| 播放 RustFS 缓存 | `SongPlayService.isCachedInRustFS()` Redis 缓存 exists 结果 (TTL 10min) |
| AI 助手 SSE 流式 | 新增 `POST /api/assistant/stream`，逐 token 推送减少线程阻塞 |
| Stream buffer 升级 | `StreamUtils` 8KB → 64KB 提升吞吐量 |
| Redis pool 优化 | lettuce max-active 8→20, min-idle 0→2 |

#### 💾 API 调用缓存
| 改进项 | 说明 |
|--------|------|
| 歌词缓存 | `SongController.lyric` Redis TTL 365天，一次拉取永久复用 |
| 首页轮播缓存 | `SongController.banner` TTL 2h |
| 歌单详情缓存 | `PlaylistController.detail` TTL 6h |
| 推荐歌单缓存 | `PlaylistController.recommend` 缓存原始 30 个精选 (TTL 3h)，每次随机取 6 |
| API 死代码清理 | `NeteaseApiService` 删除 4 个无调用者的方法 + 废弃的 `downloadSong(byte[])` |

#### 🧪 前端组件测试
| 改进项 | 说明 |
|--------|------|
| 测试 setup | `src/test-setup.js` 全局 Mock (Audio/composables/API/ResizeObserver/SvgIcon) |
| PlayerBar 组件测试 | 20 条用例：渲染/播放列表面板展开收起/播放控制/音量/时间格式化 |
| 前端测试总数 | 21 → 41 条 |

#### 🎯 UI 交互优化
| 改进项 | 说明 |
|--------|------|
| 播放列表外部收起 | `useClickOutside` 组合式函数，点击面板外部自动关闭 |
| 应用范围 | `PlayerBar.vue` + `LyricsView.vue` 播放队列面板均支持，组件卸载时自动移除监听 |

#### 🤖 AI 对话优化
| 改进项 | 说明 |
|--------|------|
| DeepSeek V4 模型 | AI 模型切换为 `deepseek-ai/DeepSeek-V4-Flash`（原 Qwen3.5-4B） |
| 智能关键词提取 | `searchSongs()` 3 级降级：提取关键词 → 原始消息 → "热门歌曲" |
| System prompt 增强 | 情绪/风格匹配规则（开心→轻快、伤感→治愈等） |
| 流式简化 | 移除 SSE 复杂度，同步 POST + 思考动画，体验稳定可靠 |

#### 🛡️ 安全与工程化
| 改进项 | 说明 |
|--------|------|
| CORS 限制 | 从 `*` 限定为 localhost + 自定义域名 |
| Actuator 安全 | 仅暴露 `/actuator/health`，隐藏敏感端点 |
| 异常 traceId | `GlobalExceptionHandler` 返回 traceId 便于排障 |
| 事务 self-invocation 修复 | `DownloadService` 改用 `TransactionTemplate`，避免代理失效 |
| HttpHeaders 复用 | `NeteaseApiService` 静态常量复用，减少对象分配 |
| CI/CD 部署流水线 | `.github/workflows/deploy.yml` tag 触发自动测试+构建 |
| musicapi keep-alive | Express server 连接复用 65s |

#### 📱 移动端适配
| 改进项 | 说明 |
|--------|------|
| AI 聊天输入栏 | `100vh` → `100dvh` + `position: sticky` + `padding-bottom` |
| 底部播放条遮挡 | 输入栏始终固定在可视区域底部 |

---

### 2026-06-16 第二轮优化 (后端 9 项 + 前端 2 项)

#### 🔴 高优先级
| 改进项 | 说明 |
|--------|------|
| HTTP 连接池 | 新增 `RestTemplateConfig`，Apache HttpClient5 连接池 (100/20)，`RestClient.Builder` 统一注入 |
| 流式下载防 OOM | `NeteaseApiService.downloadSongToFile()` 写入临时文件，`StorageService.uploadStream()` 流式上传 |
| PlaylistService 异常规范 | `RuntimeException` → `BusinessException(404/403)`，配合 `GlobalExceptionHandler` 返回正确 4xx |
| PlayHistoryService 去重 | 连续同歌只 UPDATE played_at，节省存储 |
| PlayHistoryService 概率清理 | `deleteOldByUserId` 每 10 次触发 1 次，DELETE 开销降 90% |
| LIMIT 安全边界 | 3 处 `.last("LIMIT " + count)` 加 `Math.max(1, Math.min(count, MAX))` 防御 |

#### 🟡 中优先级
| 改进项 | 说明 |
|--------|------|
| ES 初始化噪音消除 | `ensureIndex()` 改用 HEAD 请求 + `toBodilessEntity()`，彻底消除 Netty 响应体释放竞态 |
| ES bulk 统一 WebClient | `saveAll()` 替代 HttpURLConnection ndjson 写入，纳入连接池管理 |
| UserService 精准更新 | `updateAvatar/updateBgImage` 改为 `lambdaUpdate().eq().set()` 单字段更新 |
| PlaylistService INSERT IGNORE | 删 SELECT COUNT，改为 `catch DuplicateKeyException` 配合唯一索引 |
| SongSearchService Redis 优化 | 命中后消除二次 `getSearchCache()` 调用，一次读取直接分页 |
| SongPlayService 音质降级超时 | 新增 8s DEADLINE 检查，防止多级降级叠加阻塞 |
| GlobalExceptionHandler 状态码 | `@ResponseStatus` → `response.setStatus(ex.getCode())` 动态 HTTP 状态码 |

#### 🟢 低优先级
| 改进项 | 说明 |
|--------|------|
| SecurityConfig 路径清理 | 删除不存在的 `/api/song/**` pattern |
| AuthController 代码去重 | `buildUserData()` / `buildUserDataFromEntity()` 合并统一方法 |
| StreamUtils 工具类 | 提取 `StreamUtils.copy()` 消除 3 处重复流拷贝 |
| PlaylistService DTO | 新增 `record PlaylistDTO` 替代裸 HashMap |
| ESCleanupTask 间隔 | `@Scheduled` 从每小时 → 每 6 小时 |
| user_favorite 索引 | 新增 `idx_user_fav_created(user_id, created_at)` 复合索引 |
| 前端搜索竞态修复 | `song.js` 加 `AbortController` 自动取消旧请求 |
| 前端播放异常日志 | 3 处 `audio.play().catch(() => {})` 加 `console.warn` + 状态回退 |

#### 🚀 前端性能优化
| 改进项 | 说明 |
|--------|------|
| Nginx Gzip 优化 | 补全 `text/html`/`font/woff2`，`comp_level 6→4`，`min_length 256` |
| Nginx upstream 连接池 | `upstream backend { keepalive 32 }`，消除每次 API 请求 TCP 握手 |
| Nginx 并发提升 | `worker_connections 1024→2048` + `multi_accept on` |
| Nginx 缓存策略 | `/uploads/` 缓存 `7d→1d + must-revalidate`，头像更换即时生效 |
| Vite target 升级 | `es2015→es2020`，bundle 缩小约 15% |
| Vite chunk 拆分 | `vue-core` / `pinia` / `axios` 独立 chunk，并行下载 |

---

### 2026-06-19 第三轮优化 (工程化增强 + 安全合规)

#### 🏗️ 工程化增强
| 改进项 | 说明 |
|--------|------|
| Flyway 数据库版本控制 | `V1__init.sql` → `db/migration/`，`baseline-on-migrate` 兼容已有 DB |
| K6 压力测试 | `scripts/k6-test.js`，50 VU 全链路（搜索→歌词→播放→收藏→推荐）|
| 收藏幂等性 | `IdempotentGuard` (X-Request-Id + Redis 5min 去重)，前端 `request.js` 自动 UUID |
| Sentry 前端错误监控 | `main.js` 条件加载 `@sentry/vue`，`VITE_SENTRY_DSN` 环境变量注入 |
| ES 健康检查优化 | `GET /` → `HEAD /` + `toBodilessEntity`，彻底消除 Reactor `onErrorDropped` 红色堆栈 |

#### 🛡️ 安全合规
| 改进项 | 说明 |
|--------|------|
| NOTICE.md | 法律风险声明（仅供学习/禁止商用/24h 删除数据）|
| Cookie 保护 | `musicapi/config.js` → `.gitignore`（QQ/网易云 Cookie 不再提交）|
| 凭据保护 | `.env.docker` / `.env.example` / `.env.production` → `.gitignore` |
| JWT 占位符 | `application.yml` 默认值改为开发占位符，生产强制环境变量 |
| 二进制清理 | `.cloudflared/` / ES IK 插件 jar / Android gradle-wrapper.jar → `.gitignore` |

---

### 2026-06-22/23 第五轮优化（移动端深度修复 + 全链路 HTTPS 升级 + AI 助手适配）

#### 🔴 移动端严重问题修复
| 改进项 | 说明 |
|--------|------|
| CORS 修复 | cors 白名单缺少 `*.cpolar.cn`（用户隧道子域名），手机 HTTPS 访问被拦截 403 |
| HTTP→HTTPS 全链路升级 | 6 处 API 响应中封面 URL 从 `http://` 自动替换为 `https://`，防止手机混合内容拦截白屏 |
| 歌单封面修复 | MPlaylistsView 背景图 → `<img>` 标签，兼容手机 WebView；MPlaylistView 重写为 hero 布局（封面+标题+描述+播放按钮） |
| AI 助手移动端适配 | MChatView 重写：输入栏 `height: calc(100dvh - 116px)` 解决被播放栏+标签栏遮挡；AI 图标 emoji → SVG 音符；用户消息新增真实头像 |

#### 🔒 安全与输入校验
| 改进项 | 说明 |
|--------|------|
| AuthController 输入校验 | 登录 `username` 加 `trim()` + 30 字长度限制 |
| SameSite Cookie 跨容器兼容 | `cookie.setAttribute("SameSite")` → `response.addHeader("Set-Cookie", ...)` 手动构建 |
| AssistantController 输入校验 | `/chat` 和 `/stream` 加 2000 字长度上限，防止超长文本导致 OOM |

#### 🐛 逻辑 Bug 修复
| 改进项 | 说明 |
|--------|------|
| DownloadController null 安全 | `artist` 参数 key 存在但值为 null 时兜底；`song.getArtist()` 为 null 跳过前缀 |
| PlaylistView Toast 参数 | `window.toast?.('success', '消息')` 参数顺序颠倒 → 修正为 `('消息', 'success')` |
| HomeView 内存泄漏 | Banner `setInterval` 未在组件卸载时清除 → `onUnmounted(() => stopBanner())` |
| HomeView favStore 调用 | `fetchFavIds()` 从模块顶层移入 `onMounted`，避免 Pinia 未就绪 |

#### 📊 数据库优化
| 改进项 | 说明 |
|--------|------|
| PlayHistory 清理 SQL | `DELETE...NOT IN (双重子查询)` → `ROW_NUMBER() OVER() + DELETE JOIN` 窗口函数 |
| V3__add_indexes.sql | 新增 `idx_artist(100)` 索引 + `url` 字段扩容 2048 |
| PlaylistMapper 封面查询 | SQL `REPLACE(cover_url, 'http://', 'https://')` 在查询层统一升级协议 |

#### 🎨 前端体验优化
| 改进项 | 说明 |
|--------|------|
| AI 助手图标 | 🎵 emoji → 精美 SVG 音符（紫色渐变），桌面+移动端统一 |
| 用户消息头像 | 桌面+移动端 AI 对话均显示真实头像 `auth.avatarSrc` |
| HomeView 静默错误 | `loadBanners().catch(() => {})` → 加 `console.warn` 日志 |

#### 🎛️ 批量管理与交互优化
| 改进项 | 说明 |
|--------|------|
| 收藏批量管理 | MLikesView：管理模式 + 多选复选框 + 批量取消收藏底栏（毛玻璃风格） |
| 歌单批量管理 | MPlaylistsView：管理模式 + 卡片复选框 + 批量删除底栏 |
| 批量删除端点 | 后端新增 `POST /api/favorites/remove-batch` + `POST /api/playlists/delete-batch` |
| 歌单去重 | `PlaylistService.addSong()` 新增 `exists()` 预检 + DB 唯一索引双重保障 |
| 歌词页"＋"→加入歌单 | MPlayerView：原打开播放队列 → 歌单选择器弹窗，支持去重反馈 |
| AI 对话停止输出 | MChatView + ChatView：`AbortController` + 红色方形停止按钮，点击立即中断生成 |
| 批量管理 UI 统一 | 红色实底按钮 → 透明描边风格，复选框 icon → stroke 对勾，底栏 → 毛玻璃，统一暗色主题 |

#### ⚡ Claude Code 审查优化（性能关键项）
| 改进项 | 说明 |
|--------|------|
| JWT 用户 Redis 缓存 | `JwtAuthenticationFilter` 每次请求查 DB → Redis JSON 缓存 (TTL 5min)，命中率 ~99% |
| RecommendService N+1 | 排序 Comparator 中 `checkCached()` 逐条查 DB → 先调 `markOfflineStatus()` 批量标记 |
| 批量删除歌单优化 | for 循环 N×2 SQL → `selectBatchIds` 验权 + `delete(in ids)` 批量 2 次 SQL |
| keep-alive 限制 | MobileShell 6 视图全缓存 → max=4，低端手机减少内存占用 |

#### 🔧 搜索与 API 标准化
| 改进项 | 说明 |
|--------|------|
| 搜索分页标准化 | 新增 `SearchResult` DTO：`{list, total, hasMore, source}`，替代裸返回 `List` |
| 前端适配 | TopBar/MSearchView/HomeView 接入新分页结构，`hasMore` 精确判断 |
| AI 助手限流 | `RateLimitService` Redis INCR 滑动窗口，每用户每分钟限 10 次 |
| musicapi 评分优化 | QQ 搜索加 `t=0` 单曲参数 + 热度归一化对齐 + URL 缓存独立 |

#### 🎛️ 桌面端批量管理
| 改进项 | 说明 |
|--------|------|
| 歌单批量删除 | PlaylistsView：管理复选框 + 绿色选中边框 + 批量删除底栏 |
| 收藏批量移除 | LikesView：管理复选框 + 批量取消收藏 + 同步 Pinia store |
| 最近播放批量清除 | RecentView：前端过滤移除，即时生效 |

#### 🐛 Bug 修复
| 改进项 | 说明 |
|--------|------|
| PlayHistory 播放时间丢失 | `FieldFill.INSERT` → `INSERT_UPDATE`，`updateFill()` 补填 `playedAt` |

---

### 2026-06-19 第四轮优化（上线前全面审查 + 安全加固）

#### 🔒 安全加固
| 改进项 | 说明 |
|--------|------|
| JWT httpOnly Cookie | 后端 Set-Cookie VIBE_TOKEN (HttpOnly+SameSite)，前端移除 localStorage 读写，防 XSS |
| BusinessException | 15 处 `RuntimeException` → `BusinessException(code, msg)`，HTTP 状态码精确化 |
| @Transactional | UserService 读-改-写方法添加事务注解，防止并发条件覆盖 |
| NPE 防护 | AssistantController/PlaylistController/ProxyController 添加 null 安全 |
| Nginx upstream | `host.docker.internal` → `backend:8080`（兼容 Linux Docker） |
| ES 配置修复 | `elasticsearch` 从 netease 子属性移至 `spring.elasticsearch` |
| docker-compose 密码 | `DB_PASSWORD: 123456` → `${DB_PASSWORD:-123456}` |
| .env.example | 真实凭据 → 占位符，文件从 .gitignore 移除（可提交模板） |

#### 🎨 用户体验
| 改进项 | 说明 |
|--------|------|
| Toast 组件 | 新建 ToastMessage.vue + useToast.js，替换 12 处 alert() |
| 歌单详情全宽 | PlaylistDetailView 深色全宽布局，专辑列 + 收藏/队列按钮 |
| 推荐歌单随机 | personalizedPlaylists(30) → shuffle 取 6，实现"换一批" |
| 收藏状态同步 | MRecentView 从本地 favIds → 全局 useFavoriteStore |
| 移动端歌单 | 新建 MPlaylistView.vue + 路由守卫修复 |

#### 🛠️ 基础设施
| 改进项 | 说明 |
|--------|------|
| musicapi /health 去重 | 删除重复定义，统一详细版格式 |
| Flyway 脚本清理 | V1__init.sql 移除 CREATE DATABASE/USE |
| Security 补全 | /api/image-proxy + /api/download 加入 permitAll |
| musicapi Dockerfile | wget → node 内建 http 健康检查 |
| 日志轮转全覆盖 | mysql/redis/rustfs 添加 logging max-size/max-file |
| SQL 迁移 | Flyway 脚本移除 CREATE DATABASE / USE |

---

### 2026-06-16 第一轮优化
| 类别 | 改进项 | 说明 |
|------|--------|------|
| 🧹 代码质量 | SongService 拆分 | 拆为 SongService(持久化) + SongSearchService(搜索) + SongPlayService(播放) |
| 🧹 代码质量 | copyStream 提取 | SongController 3 处重复流拷贝统一为私有方法 |
| 🧹 代码质量 | RestClient 替代 | stream() 远程代理 HttpURLConnection → RestClient（连接池） |
| 🔒 安全 | 凭据环境变量化 | JWT_SECRET/DB_PASSWORD/MinIO 密钥改为 ${VAR:default} |
| 🗑️ 维护 | play_history 定时清理 | 每天 3 点删除 7 天前记录 |
| 🗑️ 维护 | RedisConfig 简化 | 删除自定义 Bean，全局只用 StringRedisTemplate |
| 🐛 修复 | Result.error() code | 500 → 400（23 处业务校验调用） |
| 🐛 修复 | 缺参异常处理 | 新增 MissingServletRequestParameterException 处理器 |
| 🐛 修复 | Docker 健康检查 | wget(Alpine 不兼容) → curl；补充 actuator 依赖 |
| 🐛 修复 | 音频流重试 | Connection reset 时重新获取 URL 再试一次 |
| 📦 运维 | .gitignore | 新建，排除 target/node_modules/Docker 数据/日志 |
| 📦 运维 | docker:dev 隔离 | 去 nginx→backend 硬依赖，避免拉全链 |
| 📦 运维 | npm scripts 简化 | docker:* 命令去 ps1 中转，直接调 docker-compose |

## License

MIT
