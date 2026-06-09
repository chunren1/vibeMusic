# vibeMusic 🎵

全栈音乐学习项目，模拟网易云音乐核心体验。不依赖第三方破解，使用自有 VIP 账号合法获取高品质音乐。

## 技术架构

```
┌─────────────────────────────────────────────────┐
│                   Frontend                       │
│           Vue 3 + Vite + Axios                   │
│               (localhost:5173)                    │
│         + Capacitor Android APK                  │
└─────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│               Spring Boot Backend                 │
│         Java 17 + JPA + Redis + MySQL            │
│               (localhost:8080)                    │
└─────────────────────────────────────────────────┘
                        │
            ┌───────────┴───────────┐
            ▼                       ▼
┌─────────────────────┐   ┌─────────────────────┐
│     musicapi         │   │    MySQL + Redis    │
│  Express.js 聚合网关  │   │   数据持久化+缓存    │
│  (localhost:3000)    │   │                     │
│                     │   │                     │
│  网易云API  QQ音乐API │   └─────────────────────┘
└─────────────────────┘
```

## 核心功能

| 功能 | 说明 |
|------|------|
| 👤 用户中心 | JWT 登录/注册，弹窗式无跳转，个人主页编辑资料（昵称/性别/生日/头像），详情页展示完整信息 |
| 🔍 多平台搜索 | 聚合网易云 + QQ音乐结果，去重打分排序，分源筛选，400ms 防抖实时搜索 |
| ▶️ VIP音质播放 | 自有 VIP Cookie，exhigh / hires 品质 |
| ❤️ 收藏管理 | 乐观更新，按用户隔离，401 自动弹窗登录 |
| 📋 歌单管理 | 创建/删除/添加歌曲，完整 CRUD，所有权校验 |
| 🕐 最近播放 | 播放历史，自动保留最近 300 条，用户隔离 |
| 📱 移动端适配 | 同项目路由分流 `/m`，独立 UI 组件，共享 API/Store，自动设备检测跳转 |
| 🎵 歌词页 | 播放中封面 + 四行滚动歌词 + 进度条 + 切歌/模式/收藏/下载/歌单 |
| 📜 播放队列 | 自动入列，底部弹出式列表，去重管理 |
| 🤖 Android APK | Capacitor 打包，HTTPS 公网接口，安装即用 |
| 🌐 内网穿透 | natapp + cpolar 双方案，公网可访问 |
| 🛡️ Cookie 监控 | Python 脚本，Windows 任务计划，Server酱微信告警 |
| 💾 RustFS 离线缓存 | 歌曲下载到对象存储，API 挂了也能播放已缓存歌曲，stream 直读兜底 |
| 🎨 响应式 UI | 桌面侧栏 + 移动底部TabBar，暗色/亮色双主题 |

## 目录结构

```
vibeMusic/
├── vibemusic-web/          # Vue 3 前端
│   ├── src/
│   │   ├── views/          # 桌面视图 + mobile/ 移动视图
│   │   ├── ProfileView.vue       # 个人主页 (背景图+可编辑表单)
│   │   ├── ProfileDetailView.vue # 个人详情页
│   │   └── ...
│   │   ├── components/     # PlayerBar, PlaylistPopup, LoginModal, LyricsView + mobile/
│   │   ├── composables/    # useIsMobile (设备检测)
│   │   ├── stores/         # Pinia: auth (JWT + 弹窗状态), player (播放状态管理中心)
│   │   └── api/            # Axios 封装 + 接口 (auth.js, song.js, request.js)
│   ├── android/            # Capacitor Android 项目
│   └── capacitor.config.json
├── vibeMusic-backend/      # Spring Boot 后端
│   └── src/main/java/com/vibemusic/
│       ├── controller/     # Auth, Song, Favorite, Playlist, Download
│       ├── service/        # UserService, SongService, NeteaseApiService, StorageService, DownloadService
│       ├── entity/         # User, Song, UserFavorite, Playlist, PlayHistory
│       ├── security/       # JwtAuthFilter, CustomUserDetails
│       ├── dto/            # SongDTO
│       ├── config/         # Security, Cors, Redis, NeteaseApi 配置
│       └── mapper/         # MyBatis-Plus Mapper
├── scripts/                # 运维工具
│   ├── start-tunnel.bat    # cpolar 自动重连脚本
│   ├── start-cpolar-tunnel.bat
│   └── start-cloudflare-tunnel.bat
├── musicapi/               # Node.js API 聚合网关 (端口 3000)
│   ├── server.js           # 聚合搜索 + 独立平台搜索 + 质量过滤
│   └── config.js           # QQ Cookie 配置
├── natapp/                 # natapp 内网穿透
│   └── run_natapp.bat
└── .cloudflared/            # Cloudflare Tunnel 配置
```

## 快速开始

### 环境要求

- Java 17+
- Node.js 18+
- MySQL 8.0+
- Redis 7+
- Maven 3.8+
- Android SDK (构建 APK 需要)

### 1. 启动 API 聚合网关

```bash
cd musicapi
npm install
node server.js
# → http://localhost:3000
```

### 2. 启动后端

```bash
cd vibeMusic-backend
mvn spring-boot:run
# → http://localhost:8080
```

### 3. 启动前端 (开发)

```bash
cd vibemusic-web
npm install
npm run dev
# → http://localhost:5173
```

### 4. 数据库初始化

```bash
mysql -u root -p < vibeMusic-backend/sql/init.sql
```

### 5. 构建 Android APK

```bash
cd vibemusic-web
npm run build
npx cap sync android
cd android && gradlew clean assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

## API 端点

### 后端 (Spring Boot, port 8080)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/auth/me` | GET | 当前用户信息 |
| `/api/auth/change-password` | POST | 修改密码 |
| `/api/songs/search` | GET | 搜索歌曲 |
| `/api/songs/play` | GET | 播放URL + 记录历史 |
| `/api/songs/stream` | GET | 音频流代理 |
| `/api/songs/history` | GET | 最近播放列表 |
| `/api/songs/banner` | GET | 首页轮播推荐 |
| `/api/songs/random` | GET | 随机推荐 |
| `/api/favorites/toggle` | POST | 切换收藏 |
| `/api/favorites/list` | GET | 收藏列表 |
| `/api/playlists/*` | CRUD | 歌单管理 |
| `/api/download/{sourceId}` | POST | 下载歌曲到RustFS |
| `/api/download/file/{id}` | GET | 下载歌曲文件（文件名：歌手 - 歌曲名.mp3） |
| `/api/download/check/{id}` | GET | 检查歌曲是否已缓存 |

### API 网关 (Express, port 3000)

| 端点 | 说明 |
|------|------|
| `/search` | 多平台聚合搜索 |
| `/cloudsearch` | 网易云单平台搜索 |
| `/song/url/v1` | 网易云播放URL |
| `/song/url/qq` | QQ音乐播放URL |

## 聚合搜索算法

```
最终得分 = 排名分 × 平台权重 + 跨平台加成

- 网易云权重: 1.0
- QQ音乐权重: 0.9
- 同名歌曲加成: +0.3
- 去重键: 歌曲名|歌手名
```

## Pinia Player Store（播放状态管理中心）

- **单一数据源**: `stores/player.js` 集中管理 `Audio` 元素、播放队列、播放模式、切歌、进度、音量
- **桌面/移动共享**: 所有组件共享同一套播放逻辑，消除状态分散
- **localStorage 持久化**: 队列、当前歌曲、播放进度、音量、播放模式自动保存
- **统一事件派发**: `song-change` 事件由 store 统一派发，各组件监听即可
- **全局兼容**: `window.vibeAudio` / `window.vibeQueue` / `window.vibePlay` 等向后兼容

## 移动端适配

- **同项目路由分流**: `/m` 路径移动端专属
- **自动跳转**: 检测 UserAgent 自动切换
- **底部栏**: MTabBar + MBottomPlayer
- **播放队列**: MQueuePopup 底部弹出

## Android APK 说明

- 使用 Capacitor 打包 Vue 为原生 Android 应用
- API 地址通过 `src/api/request.js` 的 `API_HOST` 统一配置
- 生产环境使用 HTTPS 公网地址（natapp/cpolar）
- 已配置允许 HTTP 明文流量（Android 9+ 需要）
- 音频流/下载均使用完整绝对 URL

### 打包命令

```bash
cd vibemusic-web
npm run build && npx cap sync android
cd android && gradlew clean assembleDebug
```

## 缓存策略

| 缓存 | 位置 | TTL | 说明 |
|------|------|-----|------|
| 搜索结果 | Redis | 1h | Key: `song:search:v2:{keyword}` |
| 播放历史 | MySQL | 最多300条 | 自动删除旧记录 |
| 歌曲文件 | RustFS | 永久 | 已下载歌曲缓存到对象存储 |

## RustFS 离线兜底

当 API 聚合网关（musicapi）不可用时，已下载到 RustFS 的歌曲仍可正常播放：

```
播放请求 → ① RustFS缓存? → 直接返回（不调API）
         → ② API可用? → 获取在线URL
         → ③ DB历史URL → 兜底
         → ④ stream端点 → RustFS直读兜底
```

- DB 中始终存**不过期的直接 URL**（`endpoint/bucket/objectName`），不存预签名 URL
- `stream` 端点优先从 RustFS 直接读取，远程代理为降级方案
- 下载文件名格式：`歌手 - 歌曲名.mp3`

## 关键配置

### 网易云 VIP Cookie

在 `vibeMusic-backend/src/main/resources/application.yml` 中配置 `netease.api.cookie`，主密钥为 `MUSIC_U`。

### QQ Cookie

在 `musicapi/config.js` 中配置 QQ 音乐的 Cookie 信息，约 7-14 天过期需更新。

### 前端公网地址

修改 `vibemusic-web/src/api/request.js` 中的 `API_HOST` 常量。

### RustFS 对象存储

在 `vibeMusic-backend/src/main/resources/application.yml` 中配置 `storage.rustfs.*`：
- `endpoint`: RustFS 服务地址（默认 `http://127.0.0.1:9000`）
- `bucket-name`: 存储桶名（默认 `vibemusic`）
- `access-key` / `secret-key`: 访问凭证

## 注意事项

- 修改 Java 后端文件后需**重启后端服务**
- 修改 `musicapi/` 文件后需**重启 Node.js 服务**
- 前端 Vue 文件由 Vite 热更新，无需手动重启
- 网易云/QQ Cookie 需定期更新
- 构建 APK 前确保 `API_HOST` 指向正确的公网地址
- Android 9+ 需要 `networkSecurityConfig` 允许 HTTP
