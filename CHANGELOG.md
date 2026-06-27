# vibeMusic 更新日志

> 本文档记录项目从 2026-06-16 至今的迭代优化历史。
> 更早的提交历史见 [Git 提交记录](https://github.com/kkStar/vibeMusic/commits/main)。

---

## 2026-06-27 第六轮优化（性能压测全面达标）

### 🚀 K6 压测结果（50 VU × 60s）

| 指标 | 优化前 | 优化后 | 目标 | 状态 |
|------|:-----:|:-----:|:----:|:----:|
| 搜索 P95 | 3.48s | **0.36s** | < 3s | ✅ |
| 音频流 P95 | 4.41s | **0.38s** | < 2s | ✅ |
| 收藏成功率 | 97% (17次失败) | **100%** | > 99.9% | ✅ |
| HTTP 总 P95 | 2.81s | **0.19s** | - | ✅ |
| 吞吐量 | 77.67 req/s | **158 req/s** | - | ↑104% |

### 优化措施

1. **搜索热门关键词预热**：@PostConstruct 启动时异步缓存 8 个高频关键词到 Redis，避免首次 API 穿透
2. **搜索线程池 4→50**：消除 50 并发下 API 调用排队延迟
3. **缓存污染探测器修复**：单平台结果不清空缓存（部分结果 > 每次穿透 API 4s）
4. **ES 降级优化**：ES 不可用时每 30s 重检，省去每次 1s HEAD 超时
5. **音频降级链串行→并行**：CompletableFuture 并行探测四级音质，P95 4.41s→0.38s（↓91%）
6. **收藏重试移出 @Transactional**：外层 retry + 内层事务，成功率 97%→100%
7. **musicapi 集成 prom-client**：添加 /metrics 端点，Prometheus 不再报 DOWN

## 2026-06-22/23 第五轮优化（移动端深度修复 + 全链路 HTTPS 升级 + AI 助手适配）

### 🔴 移动端严重问题修复
| 改进项 | 说明 |
|--------|------|
| CORS 修复 | cors 白名单缺少 `*.cpolar.cn`（用户隧道子域名），手机 HTTPS 访问被拦截 403 |
| HTTP→HTTPS 全链路升级 | 6 处 API 响应中封面 URL 从 `http://` 自动替换为 `https://`，防止手机混合内容拦截白屏 |
| 歌单封面修复 | MPlaylistsView 背景图 → `<img>` 标签，兼容手机 WebView；MPlaylistView 重写为 hero 布局（封面+标题+描述+播放按钮） |
| AI 助手移动端适配 | MChatView 重写：输入栏 `height: calc(100dvh - 116px)` 解决被播放栏+标签栏遮挡；AI 图标 emoji → SVG 音符；用户消息新增真实头像 |

### 🔒 安全与输入校验
| 改进项 | 说明 |
|--------|------|
| AuthController 输入校验 | 登录 `username` 加 `trim()` + 30 字长度限制 |
| SameSite Cookie 跨容器兼容 | `cookie.setAttribute("SameSite")` → `response.addHeader("Set-Cookie", ...)` 手动构建 |
| AssistantController 输入校验 | `/chat` 和 `/stream` 加 2000 字长度上限，防止超长文本导致 OOM |

### 🐛 逻辑 Bug 修复
| 改进项 | 说明 |
|--------|------|
| DownloadController null 安全 | `artist` 参数 key 存在但值为 null 时兜底；`song.getArtist()` 为 null 跳过前缀 |
| PlaylistView Toast 参数 | `window.toast?.('success', '消息')` 参数顺序颠倒 → 修正为 `('消息', 'success')` |
| HomeView 内存泄漏 | Banner `setInterval` 未在组件卸载时清除 → `onUnmounted(() => stopBanner())` |
| HomeView favStore 调用 | `fetchFavIds()` 从模块顶层移入 `onMounted`，避免 Pinia 未就绪 |

### 📊 数据库优化
| 改进项 | 说明 |
|--------|------|
| PlayHistory 清理 SQL | `DELETE...NOT IN (双重子查询)` → `ROW_NUMBER() OVER() + DELETE JOIN` 窗口函数 |
| V3__add_indexes.sql | 新增 `idx_artist(100)` 索引 + `url` 字段扩容 2048 |
| PlaylistMapper 封面查询 | SQL `REPLACE(cover_url, 'http://', 'https://')` 在查询层统一升级协议 |

### 🎨 前端体验优化
| 改进项 | 说明 |
|--------|------|
| AI 助手图标 | 🎵 emoji → 精美 SVG 音符（紫色渐变），桌面+移动端统一 |
| 用户消息头像 | 桌面+移动端 AI 对话均显示真实头像 `auth.avatarSrc` |
| HomeView 静默错误 | `loadBanners().catch(() => {})` → 加 `console.warn` 日志 |

### 🎛️ 批量管理与交互优化
| 改进项 | 说明 |
|--------|------|
| 收藏批量管理 | MLikesView：管理模式 + 多选复选框 + 批量取消收藏底栏（毛玻璃风格） |
| 歌单批量管理 | MPlaylistsView：管理模式 + 卡片复选框 + 批量删除底栏 |
| 批量删除端点 | 后端新增 `POST /api/favorites/remove-batch` + `POST /api/playlists/delete-batch` |
| 歌单去重 | `PlaylistService.addSong()` 新增 `exists()` 预检 + DB 唯一索引双重保障 |
| 歌词页"＋"→加入歌单 | MPlayerView：原打开播放队列 → 歌单选择器弹窗，支持去重反馈 |
| AI 对话停止输出 | MChatView + ChatView：`AbortController` + 红色方形停止按钮，点击立即中断生成 |
| 批量管理 UI 统一 | 红色实底按钮 → 透明描边风格，复选框 icon → stroke 对勾，底栏 → 毛玻璃，统一暗色主题 |

### ⚡ Claude Code 审查优化（性能关键项）
| 改进项 | 说明 |
|--------|------|
| JWT 用户 Redis 缓存 | `JwtAuthenticationFilter` 每次请求查 DB → Redis JSON 缓存 (TTL 5min)，命中率 ~99% |
| RecommendService N+1 | 排序 Comparator 中 `checkCached()` 逐条查 DB → 先调 `markOfflineStatus()` 批量标记 |
| 批量删除歌单优化 | for 循环 N×2 SQL → `selectBatchIds` 验权 + `delete(in ids)` 批量 2 次 SQL |
| keep-alive 限制 | MobileShell 6 视图全缓存 → max=4，低端手机减少内存占用 |

### 🔧 搜索与 API 标准化
| 改进项 | 说明 |
|--------|------|
| 搜索分页标准化 | 新增 `SearchResult` DTO：`{list, total, hasMore, source}`，替代裸返回 `List` |
| 前端适配 | TopBar/MSearchView/HomeView 接入新分页结构，`hasMore` 精确判断 |
| AI 助手限流 | `RateLimitService` Redis INCR 滑动窗口，每用户每分钟限 10 次 |
| musicapi 评分优化 | QQ 搜索加 `t=0` 单曲参数 + 热度归一化对齐 + URL 缓存独立 |

### 🎛️ 桌面端批量管理
| 改进项 | 说明 |
|--------|------|
| 歌单批量删除 | PlaylistsView：管理复选框 + 绿色选中边框 + 批量删除底栏 |
| 收藏批量移除 | LikesView：管理复选框 + 批量取消收藏 + 同步 Pinia store |
| 最近播放批量清除 | RecentView：前端过滤移除，即时生效 |

### 🐛 Bug 修复
| 改进项 | 说明 |
|--------|------|
| PlayHistory 播放时间丢失 | `FieldFill.INSERT` → `INSERT_UPDATE`，`updateFill()` 补填 `playedAt` |

---

## 2026-06-19 第四轮优化（上线前全面审查 + 安全加固）

### 🔒 安全加固
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

### 🎨 用户体验
| 改进项 | 说明 |
|--------|------|
| Toast 组件 | 新建 ToastMessage.vue + useToast.js，替换 12 处 alert() |
| 歌单详情全宽 | PlaylistDetailView 深色全宽布局，专辑列 + 收藏/队列按钮 |
| 推荐歌单随机 | personalizedPlaylists(30) → shuffle 取 6，实现"换一批" |
| 收藏状态同步 | MRecentView 从本地 favIds → 全局 useFavoriteStore |
| 移动端歌单 | 新建 MPlaylistView.vue + 路由守卫修复 |

### 🛠️ 基础设施
| 改进项 | 说明 |
|--------|------|
| musicapi /health 去重 | 删除重复定义，统一详细版格式 |
| Flyway 脚本清理 | V1__init.sql 移除 CREATE DATABASE/USE |
| Security 补全 | /api/image-proxy + /api/download 加入 permitAll |
| musicapi Dockerfile | wget → node 内建 http 健康检查 |
| 日志轮转全覆盖 | mysql/redis/rustfs 添加 logging max-size/max-file |
| SQL 迁移 | Flyway 脚本移除 CREATE DATABASE / USE |

---

## 2026-06-19 第三轮优化 (工程化增强 + 安全合规)

### 🏗️ 工程化增强
| 改进项 | 说明 |
|--------|------|
| Flyway 数据库版本控制 | `V1__init.sql` → `db/migration/`，`baseline-on-migrate` 兼容已有 DB |
| K6 压力测试 | `scripts/k6-test.js`，50 VU 全链路（搜索→歌词→播放→收藏→推荐）|
| 收藏幂等性 | `IdempotentGuard` (X-Request-Id + Redis 5min 去重)，前端 `request.js` 自动 UUID |
| Sentry 前端错误监控 | `main.js` 条件加载 `@sentry/vue`，`VITE_SENTRY_DSN` 环境变量注入 |
| ES 健康检查优化 | `GET /` → `HEAD /` + `toBodilessEntity`，彻底消除 Reactor `onErrorDropped` 红色堆栈 |

### 🛡️ 安全合规
| 改进项 | 说明 |
|--------|------|
| NOTICE.md | 法律风险声明（仅供学习/禁止商用/24h 删除数据）|
| Cookie 保护 | `musicapi/config.js` → `.gitignore`（QQ/网易云 Cookie 不再提交）|
| 凭据保护 | `.env.docker` / `.env.example` / `.env.production` → `.gitignore` |
| JWT 占位符 | `application.yml` 默认值改为开发占位符，生产强制环境变量 |
| 二进制清理 | `.cloudflared/` / ES IK 插件 jar / Android gradle-wrapper.jar → `.gitignore` |

---

## 2026-06-22 MySQL 性能优化

### 🔍 慢查询监控
| 改进项 | 说明 |
|--------|------|
| MySQL slow_query_log | 开启慢查询日志，`long_query_time=1`，未使用索引的查询一并记录 |
| 日志落盘 | 输出到 `/var/lib/mysql/slow.log`，配合 Docker 日志驱动轮转 |

### 📊 索引优化
| 改进项 | 说明 |
|--------|------|
| 删除冗余索引 x3 | `playlist_song.idx_playlist_id` / `user_favorite.idx_user_id` / `play_history.idx_user_id` 均被联合索引前缀覆盖 |
| 新增索引 x2 | `song.idx_name(50)` 歌曲名搜索 + `song.idx_created_at` 排序查询，Flyway V2 迁移 |
| Flyway V2__optimize.sql | 新增数据库迁移脚本，随后端启动自动执行 |

### 🧹 SQL 优化
| 改进项 | 说明 |
|--------|------|
| PlaylistMapper 去重 | 移除 Java @Select 注解中与 XML 完全重复的 SQL，统一在 XML 维护 |
| 索引依赖标注 | XML 注释说明 `idx_user_created` + `idx_pl_added` 索引对关联子查询的加速原理 |

### ⚡ I/O 性能优化
| 改进项 | 说明 |
|--------|------|
| DownloadService 事务拆分 | HTTP/文件 I/O 移出事务外（30s+），DB 持久化在短事务内（<50ms） |
| RecommendService statObject → DB | `markOfflineStatus()` 从 N 次 MinIO statObject 改为 1 次 DB 批量查询 |
| 播放 RustFS 缓存 | `SongPlayService.isCachedInRustFS()` Redis 缓存 exists 结果 (TTL 10min) |
| AI 助手 SSE 流式 | 新增 `POST /api/assistant/stream`，逐 token 推送减少线程阻塞 |
| Stream buffer 升级 | `StreamUtils` 8KB → 64KB 提升吞吐量 |
| Redis pool 优化 | lettuce max-active 8→20, min-idle 0→2 |

### 💾 API 调用缓存
| 改进项 | 说明 |
|--------|------|
| 歌词缓存 | `SongController.lyric` Redis TTL 365天，一次拉取永久复用 |
| 首页轮播缓存 | `SongController.banner` TTL 2h |
| 歌单详情缓存 | `PlaylistController.detail` TTL 6h |
| 推荐歌单缓存 | `PlaylistController.recommend` 缓存原始 30 个精选 (TTL 3h)，每次随机取 6 |
| API 死代码清理 | `NeteaseApiService` 删除 4 个无调用者的方法 + 废弃的 `downloadSong(byte[])` |

### 🧪 前端组件测试
| 改进项 | 说明 |
|--------|------|
| 测试 setup | `src/test-setup.js` 全局 Mock (Audio/composables/API/ResizeObserver/SvgIcon) |
| PlayerBar 组件测试 | 20 条用例：渲染/播放列表面板展开收起/播放控制/音量/时间格式化 |
| 前端测试总数 | 21 → 41 条 |

### 🎯 UI 交互优化
| 改进项 | 说明 |
|--------|------|
| 播放列表外部收起 | `useClickOutside` 组合式函数，点击面板外部自动关闭 |
| 应用范围 | `PlayerBar.vue` + `LyricsView.vue` 播放队列面板均支持，组件卸载时自动移除监听 |

### 🤖 AI 对话优化
| 改进项 | 说明 |
|--------|------|
| DeepSeek V4 模型 | AI 模型切换为 `deepseek-ai/DeepSeek-V4-Flash`（原 Qwen3.5-4B） |
| 智能关键词提取 | `searchSongs()` 3 级降级：提取关键词 → 原始消息 → "热门歌曲" |
| System prompt 增强 | 情绪/风格匹配规则（开心→轻快、伤感→治愈等） |
| 流式简化 | 移除 SSE 复杂度，同步 POST + 思考动画，体验稳定可靠 |

### 🛡️ 安全与工程化
| 改进项 | 说明 |
|--------|------|
| CORS 限制 | 从 `*` 限定为 localhost + 自定义域名 |
| Actuator 安全 | 仅暴露 `/actuator/health`，隐藏敏感端点 |
| 异常 traceId | `GlobalExceptionHandler` 返回 traceId 便于排障 |
| 事务 self-invocation 修复 | `DownloadService` 改用 `TransactionTemplate`，避免代理失效 |
| HttpHeaders 复用 | `NeteaseApiService` 静态常量复用，减少对象分配 |
| CI/CD 部署流水线 | `.github/workflows/deploy.yml` tag 触发自动测试+构建 |
| musicapi keep-alive | Express server 连接复用 65s |

### 📱 移动端适配
| 改进项 | 说明 |
|--------|------|
| AI 聊天输入栏 | `100vh` → `100dvh` + `position: sticky` + `padding-bottom` |
| 底部播放条遮挡 | 输入栏始终固定在可视区域底部 |

---

## 2026-06-16 第二轮优化 (后端 9 项 + 前端 2 项)

### 🔴 高优先级
| 改进项 | 说明 |
|--------|------|
| HTTP 连接池 | 新增 `RestTemplateConfig`，Apache HttpClient5 连接池 (100/20)，`RestClient.Builder` 统一注入 |
| 流式下载防 OOM | `NeteaseApiService.downloadSongToFile()` 写入临时文件，`StorageService.uploadStream()` 流式上传 |
| PlaylistService 异常规范 | `RuntimeException` → `BusinessException(404/403)`，配合 `GlobalExceptionHandler` 返回正确 4xx |
| PlayHistoryService 去重 | 连续同歌只 UPDATE played_at，节省存储 |
| PlayHistoryService 概率清理 | `deleteOldByUserId` 每 10 次触发 1 次，DELETE 开销降 90% |
| LIMIT 安全边界 | 3 处 `.last("LIMIT " + count)` 加 `Math.max(1, Math.min(count, MAX))` 防御 |

### 🟡 中优先级
| 改进项 | 说明 |
|--------|------|
| ES 初始化噪音消除 | `ensureIndex()` 改用 HEAD 请求 + `toBodilessEntity()`，彻底消除 Netty 响应体释放竞态 |
| ES bulk 统一 WebClient | `saveAll()` 替代 HttpURLConnection ndjson 写入，纳入连接池管理 |
| UserService 精准更新 | `updateAvatar/updateBgImage` 改为 `lambdaUpdate().eq().set()` 单字段更新 |
| PlaylistService INSERT IGNORE | 删 SELECT COUNT，改为 `catch DuplicateKeyException` 配合唯一索引 |
| SongSearchService Redis 优化 | 命中后消除二次 `getSearchCache()` 调用，一次读取直接分页 |
| SongPlayService 音质降级超时 | 新增 8s DEADLINE 检查，防止多级降级叠加阻塞 |
| GlobalExceptionHandler 状态码 | `@ResponseStatus` → `response.setStatus(ex.getCode())` 动态 HTTP 状态码 |

### 🟢 低优先级
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

### 🚀 前端性能优化
| 改进项 | 说明 |
|--------|------|
| Nginx Gzip 优化 | 补全 `text/html`/`font/woff2`，`comp_level 6→4`，`min_length 256` |
| Nginx upstream 连接池 | `upstream backend { keepalive 32 }`，消除每次 API 请求 TCP 握手 |
| Nginx 并发提升 | `worker_connections 1024→2048` + `multi_accept on` |
| Nginx 缓存策略 | `/uploads/` 缓存 `7d→1d + must-revalidate`，头像更换即时生效 |
| Vite target 升级 | `es2015→es2020`，bundle 缩小约 15% |
| Vite chunk 拆分 | `vue-core` / `pinia` / `axios` 独立 chunk，并行下载 |

---

## 2026-06-16 第一轮优化

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
