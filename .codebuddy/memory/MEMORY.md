# vibeMusic 项目记忆

## 项目概述
全栈音乐学习项目，模拟网易云音乐核心功能。
- 架构：Vue 3 + Spring Boot + MySQL + Redis + RustFS/MinIO
- 前端：`vibemusic-web/` (Vite + Vue3)
- 后端：`vibeMusic-backend/` (Spring Boot + JPA)
- API 网关：`musicapi/` (Express, 端口 3000, 多平台聚合)

## 重要规则
- 修改 Java 后端文件后，必须提醒用户重启后端服务
- 前端 Vue 文件由 Vite 热更新无需重启
- `replace_in_file` 和 `write_to_file` 工具对 `.vue` 和 `.java` 文件经常静默失败，需要用 Node.js 脚本或 PowerShell 脚本作为 workaround
- 修改 SecurityConfig 后也需要重启后端

## 技术决策
- 不使用第三方破解，使用自有 VIP 账号 Cookie 获取音乐
- 自建对象存储实现歌曲离线缓存（RustFS）
- Redis 缓存搜索结果，TTL 1 小时
- 缓存键前缀 `song:search:v2:` (v2 是因为 v1 只有单平台数据)
- 播放历史上限 300 条，自动清理旧数据

## MySQL 优化 (2026-06-22)
- MySQL 慢查询日志已开启：`long_query_time=1`，日志文件 `/var/lib/mysql/slow.log`
- 删除了 3 个冗余索引（playlist_song.idx_playlist_id, user_favorite.idx_user_id, play_history.idx_user_id）
- 新增 2 个索引：song.idx_name(50), song.idx_created_at
- PlaylistMapper 删除了 Java 和 XML 中的重复 SQL，统一在 XML 维护
- ORM: MyBatis-Plus + HikariCP（未引入 Druid）
- Flyway 迁移：V2__optimize.sql

## 线程池与缓存修复 (2026-07-06)
- SEARCH_EXECUTOR: Executors.newFixedThreadPool(50) → ThreadPoolExecutor(50/100, Queue=200, CallerRunsPolicy)，防 OOM
- WARM_EXECUTOR: Executors.newSingleThreadExecutor() → ThreadPoolExecutor(1/1, Queue=20, DiscardOldestPolicy)
- SongController: 新增 ASYNC_CACHE_EXECUTOR(2/4, Queue=20, DiscardPolicy)，替换 CompletableFuture.runAsync 默认 ForkJoinPool
- RecommendService.readCache: isCachePoisoned 检测到污染后自动 delete Redis key + return null 触发重算（不再仅 warn）

## 当前架构状态
- musicapi (端口 3000): 提供 `/search` 多平台聚合搜索、`/cloudsearch` 网易云单平台、`/song/url/qq` QQ播放URL
- 聚合搜索算法: 网易云权重 1.0，QQ 权重 0.9，同名歌曲 bonus 0.3
- QQ Cookie 需定期更新（存储在 musicapi/server.js 中）
- QQ Cookie 已外置到 musicapi/config.js
- RustFS 缓存兜底策略（2026-06-09 修复）:
  - 播放/流代理优先检查 RustFS 缓存 → API → DB兜底
  - DB 中始终存直接URL（不过期），不再存7天有效的预签名URL
  - stream 端点增加 RustFS 直读兜底（StorageService.getObject）
  - 下载文件名改为 "歌手 - 歌曲名.mp3"

## 监控体系 (2026-06-27 搭建)
- Prometheus(9090) + Grafana(3001) + Alertmanager(9093) 三容器已加入 docker-compose
- pom.xml 有 micrometer-registry-prometheus 依赖
- application.yml 暴露 /actuator/prometheus,metrics 端点
- SecurityConfig 放行 /actuator/**
- Grafana Dashboard: docker-data/grafana/provisioning/dashboards/json/vibemusic-overview.json
- 已埋点指标: cache.hit.redis / cache.hit.es / cache.miss.api / search.latency
- 告警规则: 服务宕机 / 搜索P95>1s / JVM内存>85% / 缓存穿透率>70%
- Docker 容器总数: 10（原7 + 监控三件套）

## AI 助手架构 (2026-07-18 更新)
- LLM: DeepSeek V4 Flash (`deepseek-v4-flash`)，API: `https://api.deepseek.com/chat/completions`
- **关键**: 必须传 `extra_body: {thinking: {type: "disabled"}}`，否则 V4 默认推理模式吞掉所有 token、content 为空
- 从"聊天框+独立搜索"升级为"Function Calling Agent"
- ChatMemoryService: Redis 存会话历史，保留最近 10 轮，TTL 30min
- AiToolService: 定义 search_songs + get_user_history 两个工具
- AssistantController /chat: 带 tools 调用 LLM → 解析 tool_calls → 执行 → 结果回传 → 最终回复
- AssistantController /stream: WebClient 真正 SSE 流式（替代 RestTemplate 伪流式）
- 新增 DELETE /api/assistant/history 清除对话记忆
- API Key 存储在 .env 的 AI_API_KEY，application-dev.yml 有默认值

## README 优化 (2026-06-27)
- README 全面重写，面向面试官视角，展示完整全栈能力
- 移除所有移动端引用（APK、多端等）
- 新增深度内容：搜索降级链详解、BFF 网关设计理由、推荐引擎 v3 演进、六级音质 SLA
- 新增性能优化清单（18 项含具体效果数据）、安全体系 12 层防护表
- 每个技术选型附带理由，让面试官理解"为什么这样设计"

## 内网穿透（Cloudflare Tunnel）
- 域名: www.vibemusic.abrdns.com (注册于 abrdns.com/cloudns.net)
- Cloudflare Tunnel ID: ae061393-aae9-4ced-b40e-3a9818849993
- 启动脚本: scripts/start-cloudflare-tunnel.bat
- 配置文件: C:\Users\靖敏\.cloudflared\config.yml
- CNAME 记录: www → ae061393-aae9-4ced-b40e-3a9818849993.cfargotunnel.com
- 必须先启动前端(5173)再开隧道

## 生产环境安全加固 (2026-07-08)
完成 16 条审计发现的全栈修复（12 步操作），覆盖安全/可靠性/性能/运维四个维度：

### 已修复
- `vibemusic-web/.env.production`：Sentry DSN 真实密钥替换为占位符
- `docker-compose.yml`：MySQL/Redis/ES/MinIO 端口不再对外暴露，仅 Docker 内部网络访问（注释保留便于本地调试）
- `nginx/nginx.conf`：添加 `/actuator/` location 返回 403 + CSP 安全头
- `CorsConfig.java`：allowedOriginPatterns 从 `*` 改为环境变量 `CORS_ORIGINS` 注入
- `SongController.java`：streamFromRemote 添加 audioUrl 域名白名单防 SSRF
- `musicapi/server.js`：`/netease/*` 添加方法白名单 + 移除 `/qq/*` 通配路由
- `docker-data/redis/redis.conf`：maxmemory 256→512MB
- `docker-data/prometheus/prometheus.yml`：移除 host.docker.internal target
- `docker-data/alertmanager/alertmanager.yml`：移除无效 webhook URL
- `docker-compose.yml`：ES 开启 xpack.security + 密码认证
- `vibeMusic-backend/Dockerfile`：USER app 非 root + `-XX:MaxRAMPercentage=75.0` + `-XX:+UseG1GC`
- `musicapi/Dockerfile`：USER node 非 root
- `vibeMusic-backend/.dockerignore` + `musicapi/.dockerignore`：新建
- `.env.example`：新增 ES_PASSWORD 变量

### 用户需手动完成
1. Sentry 控制台轮换 DSN 密钥并填入 `.env.production`
2. 生成强密码：MYSQL_ROOT_PASSWORD / ES_PASSWORD / JWT_SECRET / MINIO_ROOT_PASSWORD
3. 确认 `nginx/certs/` 下有 valid TLS 证书（fullchain.pem + privkey.pem）
4. 如有需要，配置 Alertmanager 实际通知通道

## 全链路审计报告 (2026-07-19)
- 文件: `PROJECT-AUDIT.md`（项目根目录）
- 覆盖: 前端 37.vue+24.js / 后端 85.java / BFF 998行 server.js / 基础设施 13容器
- 共发现 **31 个问题**: 5 Critical + 9 High + 11 Medium + 6 Low
- **状态: ✅ 全部修复完成** (2026-07-19)
  - 前端: ErrorBoundary + safeCapture + drop_console 调整 + index.html 清理
  - 后端: 线程池策略修复 + RestTemplate 连接池增大 + 全局异常细化
  - BFF: 速率限制 + 参数校验 + Cookie 15min检查 + 异步日志 + 黑名单扩展
  - 基础设施: 端口全关 + 凭据安全 + CSP 加固 + MinIO 备份 + JVM 优化
  - 测试: 前端 4/4 通过, 后端 12/12 通过
- **需重启服务**: musicapi (速率限制/参数校验新逻辑生效)、Spring Boot (线程池/连接池生效)
