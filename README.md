# vibeMusic 🎵

全栈音乐学习项目，模拟网易云音乐核心体验。不依赖第三方破解，使用自有 VIP 账号合法获取高品质音乐。

## 技术架构

```
┌─────────────────────────────────────────────────┐
│                   Frontend                       │
│           Vue 3 + Vite + Axios                   │
│               (localhost:5173)                    │
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
| 👤 用户认证 | JWT 登录/注册，BCrypt 加密，弹窗式无跳转，支持修改密码 |
| 🔍 多平台搜索 | 聚合网易云 + QQ音乐结果，去重打分排序，分源筛选，400ms 防抖实时搜索 |
| ▶️ VIP音质播放 | 自有 VIP Cookie，exhigh / hires 品质 |
| ❤️ 收藏管理 | 乐观更新，按用户隔离，401 自动弹窗登录 |
| 📋 歌单管理 | 创建/删除/添加歌曲，完整 CRUD，所有权校验 |
| 🕐 最近播放 | 播放历史，自动保留最近 300 条，用户隔离 |
| 📱 移动端适配 | 同项目路由分流 `/m`，独立 UI 组件，共享 API/Store，自动设备检测跳转 |
| 🎵 歌词页 | 播放中封面 + 四行滚动歌词 + 进度条 + 切歌/模式/收藏/下载/歌单 |
| 📜 播放队列 | 自动入列，底部弹出式列表，去重管理 |
| 🛡️ Cookie 监控 | Python 脚本，Windows 任务计划，Server酱微信告警 |
| 🎨 响应式 UI | 桌面侧栏 + 移动底部TabBar，暗色/亮色双主题 |

## 目录结构

```
vibeMusic/
├── vibemusic-web/          # Vue 3 前端
│   ├── src/
│   │   ├── views/          # 桌面视图 + mobile/ 移动视图
│   │   │   ├── HomeView, LikesView, PlaylistsView, RecentView, LoginView, ProfileView
│   │   │   └── mobile/     # MHomeView, MPlayerView, MSearchView, MLikesView, MRecentView, MPlaylistsView, MProfileView, MobileShell
│   │   ├── components/     # PlayerBar, PlaylistPopup, LoginModal, LyricsView + mobile/
│   │   │   └── mobile/     # MBottomPlayer, MTabBar, MQueuePopup
│   │   ├── composables/    # useIsMobile (设备检测)
│   │   ├── stores/         # Pinia: auth (JWT + 弹窗状态)
│   │   └── api/            # Axios 封装 + 接口定义 (song.js, request.js)
│   └── vite.config.js      # Vite 配置 + API 代理
├── vibeMusic-backend/      # Spring Boot 后端
│   └── src/main/java/com/vibemusic/
│       ├── controller/     # Auth, Song, Favorite, Playlist, Download
│       ├── service/        # UserService, SongService, NeteaseApiService, 缓存/历史
│       ├── entity/         # User, Song, UserFavorite, Playlist, PlayHistory
│       ├── security/       # JwtAuthFilter, CustomUserDetails
│       ├── dto/            # SongDTO
│       ├── config/         # Security, Cors, Redis, NeteaseApi 配置
│       └── mapper/         # MyBatis-Plus Mapper
├── scripts/                # 运维工具
│   ├── cookie-monitor.py   # Cookie 存活监控 (定时任务)
│   ├── start-tunnel.bat    # cpolar 自动重连脚本
│   └── requirements.txt    # Python 依赖
└── musicapi/               # Node.js API 聚合网关 (端口 3000)
    └── server.js           # 聚合搜索 + 独立平台搜索 + 质量过滤
```

## 快速开始

### 环境要求

- Java 17+
- Node.js 18+
- MySQL 8.0+
- Redis 7+
- Maven 3.8+

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
# 配置 application.properties 中的 MySQL/Redis 连接
mvn spring-boot:run
# → http://localhost:8080
```

### 3. 启动前端

```bash
cd vibemusic-web
npm install
npm run dev
# → http://localhost:5173
```

### 4. 数据库初始化

```bash
# 执行 SQL 建表脚本
mysql -u root -p < vibeMusic-backend/sql/update-tables.sql
```

## API 端点

### 后端 (Spring Boot, port 8080)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/auth/register` | POST | 用户注册（自动登录返回JWT） |
| `/api/auth/login` | POST | 用户登录（返回JWT） |
| `/api/auth/me` | GET | 获取当前用户信息 |
| `/api/auth/change-password` | POST | 修改密码（需登录） |
| `/api/songs/search` | GET | 搜索歌曲（支持 page/size/platform 参数） |
| `/api/songs/play` | GET | 获取播放URL + 记录历史 |
| `/api/songs/history` | GET | 最近播放列表 |
| `/api/songs/banner` | GET | 首页轮播推荐 |
| `/api/songs/random` | GET | 随机推荐 |
| `/api/favorites/toggle` | POST | 切换收藏（需登录） |
| `/api/favorites/list` | GET | 收藏列表（需登录） |
| `/api/playlists/list` | GET | 歌单列表（需登录） |
| `/api/playlists/create` | POST | 创建歌单（需登录） |
| `/api/playlists/add-song` | POST | 添加歌曲到歌单（需登录） |
| `/api/playlists/songs` | GET | 歌单歌曲列表 |
| `/api/playlists/remove-song` | DELETE | 移除歌单歌曲（需登录） |
| `/api/playlists/delete` | DELETE | 删除歌单（需登录） |

### API 网关 (Express, port 3000)

| 端点 | 说明 |
|------|------|
| `/search` | 多平台聚合搜索（QQ + 网易云） |
| `/cloudsearch` | 网易云单平台搜索 |
| `/song/url/v1` | 网易云播放URL |
| `/song/url/qq` | QQ音乐播放URL |
| `/song/detail` | 歌曲详情 |
| `/personalized` | 推荐歌单 |

## 聚合搜索算法

```
最终得分 = 排名分 × 平台权重 + 跨平台加成

- 网易云权重: 1.0
- QQ音乐权重: 0.9
- 同名歌曲加成: +0.3（两平台都有时加成）
- 去重键: 歌曲名|歌手名
- 排序: 最终得分降序
```

## 移动端适配

采用**同项目路由分流**方案（方案C），移动端与桌面端共享 API/Store/Auth，但使用独立的视图组件。

- **入口**: App.vue 同步检测设备 → 移动端自动跳 `/m`，桌面端误入 `/m` 则跳回 `/`
- **路由**: `/m/home`, `/m/search`, `/m/likes`, `/m/recent`, `/m/playlists`, `/m/profile`, `/m/player`
- **底部栏**: MTabBar（首页/最近/歌单/收藏/我的）+ MBottomPlayer（迷你播放条）
- **播放队列**: MQueuePopup 从下到上弹出，去重自动入列
- **歌词页**: `/m/player` — 圆形封面 + 四行歌词 + 进度条 + 收藏/下载/歌单/播放模式
- **CORS**: 允许所有来源（支持 cpolar 内网穿透）

## 缓存策略

| 缓存 | 位置 | TTL | 说明 |
|------|------|-----|------|
| 搜索结果 | Redis | 1h | Key: `song:search:v2:{keyword}` |
| 播放历史 | MySQL | 最多300条 | 自动删除旧记录 |
| 下载歌曲 | RustFS/MinIO | 永久 | 已下载歌曲存对象存储 |

## 关键配置

### musicapi QQ Cookie（需定期更新）

在 `musicapi/server.js` 中设置 QQ 音乐的 Cookie 信息：
```javascript
qqMusic.setCookie({
  uin: '...',
  qqmusic_key: '...',
  psrf_qqaccess_token: '...',
  // ...
});
```

### 网易云 VIP Cookie

在 `vibeMusic-backend/src/.../NeteaseApiService.java` 的 `init()` 方法中设置 `MUSIC_U` Cookie。

## 注意事项

- 修改 Java 后端文件后需**重启后端服务**
- 修改 `musicapi/server.js` 后需**重启 Node.js 服务**
- 前端 Vue 文件由 Vite 热更新，无需手动重启
- QQ Cookie 大约 7-14 天过期，需从浏览器导出更新
