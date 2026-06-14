# vibeMusic

全栈音乐学习项目，模拟网易云音乐核心体验。不依赖第三方破解，使用自有 VIP 账号合法获取高品质音乐。

## 技术架构

```
┌──────────────────────────────────────────────┐
│               Vue 3 + Vite + Pinia             │
│          (localhost:5173) + Capacitor APK      │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│           Spring Boot 4 + Java 21              │
│   MyBatis-Plus + JWT + Redis + MySQL + ES     │
│              (localhost:8080)                  │
└──────────────────────┬───────────────────────┘
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
┌─────────────────┐        ┌────────────────────────┐
│    musicapi      │        │  MySQL + Redis + ES    │
│  Express.js 网关  │        │  数据持久化 + 三级缓存  │
│  (localhost:3000)│        │  ES:9201 IK 分词搜索   │
│ 网易云 + QQ音乐   │        └────────────────────────┘
│ Cookie 统一管理   │
│ 音质SLA分级      │
│ Cookie 自动监控   │
└─────────────────┘
```

## 目录结构

```
vibeMusic/
├── package.json                  # 工作区根配置（统一 npm scripts）
├── docker-compose.yml            # Docker 中间件编排 (MySQL + Redis + MinIO + ES)
├── .env.docker                   # Docker 环境变量
├── docker-data/                  # Docker 数据持久化目录
│   ├── mysql/data/               # MySQL 8.0 数据
│   ├── redis/data/               # Redis 数据 + AOF
│   └── rustfs/data/              # MinIO 对象存储
├── musicapi/                     # Node.js 音乐 API 聚合网关 (端口 3000)
│   ├── server.js                 # 聚合搜索 + 音质分级 + Cookie 注入 + 监控 + 日志
│   ├── config.js                 # 网易云 + QQ 音乐 Cookie 统一管理中心
│   └── logs/                     # 分类日志 (access / api-errors / cookie-monitor / degradation)
├── vibeMusic-backend/            # Spring Boot 后端 (端口 8080)
│   ├── sql/init.sql              # 数据库完整初始化脚本（6张表 + 默认管理员）
│   ├── uploads/avatars/          # 用户上传的头像和背景图
│   └── src/main/java/com/vibemusic/
│       ├── controller/           # Auth, Song, Favorite, Playlist, Download, Recommend, Proxy
│       ├── service/              # User, Song, Recommend, Storage, Download, NeteaseApi, Cache
│       ├── entity/               # User, Song, Playlist, PlaylistSong, UserFavorite, PlayHistory
│       ├── dto/                  # SongDTO, RecommendResult
│       ├── config/               # Security, Cors, Redis, AudioQualityTier, NeteaseApi, Web
│       └── security/             # JwtAuthFilter, CustomUserDetails
├── vibemusic-web/                # Vue 3 前端 (端口 5173)
│   └── src/
│       ├── views/                # 桌面端 (/) + 移动端 (/m) 视图
│       ├── components/           # PlayerBar, LyricsView, LoginModal + mobile/ 组件
│       ├── stores/               # Pinia: auth, player, favorite, recommend
│       ├── composables/          # useAudioBackground, useVirtualList, useIsMobile
│       ├── directives/           # v-lazy-img 图片懒加载指令
│       ├── router/               # 路由定义（桌面/移动自动分流）
│       └── api/                  # Axios 封装 + 接口定义
└── scripts/                      # 运维工具
    ├── ops.cjs                   # 跨平台运维面板（npm run ops）
    └── ops/                      # Python 运维脚本
        ├── cookie-monitor.py     # Cookie 定时巡检
        ├── check-cookies.py      # Cookie 快速检测
        └── setup-scheduler.ps1   # Windows 任务计划
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
| 🎨 响应式 UI | 桌面侧栏 + 移动底部 TabBar，暗色/亮色双主题 |
| 🖼️ SVG 图标系统 | 全局 SvgIcon 组件，17 个内联 symbol，color/currentColor 自适应主题，spin 动画 |
| 🎨 简约风图标 | 全站 emoji 图标替换为 SVG：搜索/收藏/下载/加入歌单/移除等 |
| 👤 用户头像 | 首页 + TopBar 真实头像展示，无头像回退文字 |
| 🖼️ 背景图 | 个人页全宽背景图，无图时绿色渐变兜底 |

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
npm run docker:up        # 启动 MySQL + Redis + MinIO
# MinIO 管理: http://localhost:9001 (rustfsadmin/rustfsadmin)
# 数据库自动初始化，默认管理员: admin / 123456
```

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
| `npm run docker:up` | 启动 Docker 中间件 |
| `npm run docker:down` | 停止 Docker 中间件 |
| `npm run docker:restart` | 重启 Docker 中间件 |
| `npm run docker:status` | 查看中间件状态 |
| `npm run build` | 构建前端生产包 |
| `npm run ops` | 运维面板（日志/状态检查/Cookie 检测） |
| `npm run tunnel` | 启动 cpolar 内网穿透 |
| `npm run install:all` | 安装全部依赖 |

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
| `/api/favorites` | GET/POST/DELETE | 收藏管理 |
| `/api/playlists` | GET/POST/PUT/DELETE | 歌单 CRUD |
| `/api/playlists/{id}/songs` | POST/DELETE | 歌单歌曲添加/移除 |
| `/api/download/check/{id}` | GET | 检查歌曲是否已下载 |
| `/api/download` | POST | 下载歌曲到 RustFS |
| `/api/songs/history` | GET | 播放历史 |
| `/api/image-proxy` | GET | 图片代理（HTTP封面→HTTPS隧道，白名单网易云CDN） |

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
| `/cookie-status` | Cookie 存活状态 |
| `/health` | 健康检查 + Cookie 状态 |

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
- **定时清理**：每小时删除 1 小时前的 ES 缓存数据
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

```bash
npm run ops     # 跨平台运维面板（日志/状态/Cookie 检查/停止服务）
npm run tunnel  # 启动 cpolar 内网穿透
```

### 日志分类 (musicapi/logs/)

| 日志文件 | 内容 |
|----------|------|
| `access.log` | HTTP 请求记录 |
| `api-errors.log` | API 调用异常 |
| `cookie-monitor.log` | Cookie 存活检查 |
| `degradation.log` | 音质降级事件 |

## License

MIT
