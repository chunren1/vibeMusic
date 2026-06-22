# vibeMusic 项目详解（面试文档）

> 自建全栈音乐平台，支持网易云 + QQ 音乐双源聚合搜索与高品质播放。
> 本项目为个人全栈学习项目，以下文档按面试表达场景结构化梳理。

---

## 一、项目概述与定位

### 一句话描述
从零构建的全栈在线音乐平台，具备多音源聚合搜索、VIP 品质播放、三级缓存架构、离线下载能力，支持 Web + 移动端双端访问，完整覆盖从 API 网关到前端 UI 的全链路。

### 项目规模
| 维度 | 数据 |
|------|------|
| 代码行数 | 前端 ~12,000 行，后端 ~8,000 行，Node 网关 ~800 行 |
| 数据库表 | 6 张核心表（users, song, playlist, playlist_song, user_favorite, play_history） |
| 微服务/容器 | 7 个 Docker 容器（Nginx / Spring Boot / Express / MySQL / Redis / ES / MinIO） |
| 测试覆盖 | 后端 42 条（JUnit 5 + MockMvc）+ 前端 21 条（Vitest）= 63 条 |
| API 端点 | 后端 30+，网关 10+ |

---

## 二、技术架构

### 2.1 架构图

```
┌─────────────────────────────────────────────────┐
│            Nginx (port 80) 统一入口               │
│     静态文件 + SPA fallback + API 反向代理         │
└──────────┬──────────────────────────┬────────────┘
           │                          │
    静态资源 (dist/)              /api/* 代理
           │                          │
           ▼                          ▼
  ┌────────────────┐    ┌──────────────────────────┐
  │ Vue 3 + Vite    │    │  Spring Boot 4 + Java 17  │
  │ 桌面端 + 移动端  │    │  MyBatis-Plus + JWT       │
  │ (localhost:5173)│    │  (localhost:8080)         │
  └────────────────┘    └──────────┬───────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
    ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐
    │   musicapi    │   │  MySQL 8.0   │   │  Redis 7 + ES 8.18   │
    │  Express 网关  │   │  数据持久化   │   │  缓存 + 全文搜索       │
    │  (port 3000)  │   │  Flyway 迁移  │   │  IK 中文分词          │
    │ 音源聚合+播放  │   └──────────────┘   └──────────────────────┘
    └──────────────┘
           │
    ┌──────┴──────┐
    │  MinIO/RustFS │     ← 歌曲离线缓存，S3 兼容对象存储
    │  对象存储      │
    └──────────────┘
```

### 2.2 技术栈选型理由

| 技术 | 选型理由 |
|------|----------|
| **Vue 3 + Composition API** | 响应式系统 + 组合式函数复用（如 useClickOutside/useAudioBackground），社区活跃 |
| **Vite** | 秒级 HMR，esbuild 预编译，开发体验远超 Webpack |
| **Pinia** | Vue 3 官方状态管理，TypeScript 友好，模块化 Store |
| **Spring Boot 4 + Java 17** | 最新一代 Spring Boot，AOT 编译支持，生态成熟 |
| **MyBatis-Plus** | Lambda 表达式构建查询，逻辑删除/分页开箱即用 |
| **Flyway** | 数据库版本控制，schema 变更像代码一样可追溯 |
| **HikariCP** | 字节码级轻量连接池，Spring Boot 默认，性能最优 |
| **Redis** | 搜索缓存、幂等去重、会话状态 |
| **Elasticsearch + IK** | 中文歌曲名分词搜索，毫秒级响应 |
| **MinIO** | S3 兼容对象存储，歌曲离线下载持久化 |
| **Docker Compose** | 一键启动全栈，环境一致性 |
| **Nginx** | 反向代理 + 静态资源服务 + Gzip + 连接池 |

---

## 三、核心业务流程与关键模块实现

### 3.1 搜索三级缓存（Redis → ES → API）

```
用户搜索 → ① Redis (TTL 1h)          ← 命中率最高，平均 < 5ms
           ↓ 未命中
         → ② Elasticsearch (IK 分词)   ← 毫秒级，关键词中文分词
           ↓ 未命中
         → ③ musicapi 实时聚合         ← 兜底，网易云+QQ 并行请求
           ↓
         写入: Redis + ES 同步缓存
```

**实现细节：**
- 搜索缓存键前缀 `song:search:v4:`，版本号递增自然淘汰旧缓存
- 空结果 TTL 仅 10 秒，防止临时故障导致缓存"毒化"
- 单平台返回空结果时 TTL 30 秒，快速重试
- **缓存污染自动清理**：检测到全平台覆盖的单源缓存 → 自动删除重建
- 每次搜索日志标记命中层 `[CACHE-LAYER]` / `[ES-LAYER]` / `[API-LAYER]`

### 3.2 跨平台歌曲去重与合并算法

```
网易云 40 首 (权重 1.0) ─┐
                         ├─→ 规范化键(name|artist 去标点空格小写)
QQ 音乐 40 首 (权重 0.9) ─┘
                         ↓
                    LinkedHashMap 去重
                    ├─ 同名歌曲 → pickBest(音质/封面/时长)
                    ├─ availableSources: ["netease", "qq"]
                    └─ finalScore += 跨平台 bonus
                         ↓
                    finalScore 降序排序输出
```

**核心方法 `pickBest()`：**
- 优先排除试听版（duration ≤ 30s）
- 比较 qualityScore（有专辑信息 +0.5，有封面 +0.5）
- 最终按 finalScore 排序

### 3.3 音质 SLA 六级降级策略

```
LOCAL (RustFS 直出，零 API 调用)
  ↓ 降级
HIRES (96kHz/24bit)
  ↓ 降级
EXHIGH (48kHz)
  ↓ 降级
HIGHER (320kbps)
  ↓ 降级
STANDARD (128kbps)
  ↓ 降级
FALLBACK (DB 历史 URL 兜底)
```

**设计要点：**
- 每级降级记录 `degradationCount` 统计
- 8 秒超时 deadline 防止多级降级叠加阻塞
- 网易云尝试完毕 → 跨平台降级到 QQ 音乐
- RustFS 离线缓存优先，命中时直接返回（零 API 调用，最高 SLA）

### 3.4 个性化推荐引擎

```
播放行为 → play_history 表
  ↓
歌手兴趣权重聚合（最近播放频次加权）
  ↓
优先匹配 RustFS 已缓存歌曲（不消耗 API）
  ↓
Redis 缓存 (登录用户 6h / 游客 10min)
  ↓
推荐失败降级 → getRandomSongs("热歌") 兜底
```

- 播放后异步清除推荐缓存，确保推荐随行为实时更新
- `refresh=true` 参数跳过缓存重新生成
- 异常兜底：Redis 异常 → 跳过缓存直接推荐
- **缓存污染自动清理**：API 宕机缓存毒化 → 自动检测并重建

### 3.5 RustFS 离线缓存策略

```
仅用户主动下载时存入 RustFS，播放不自动下载

播放请求 → ① RustFS 缓存? → 直接返回（零 API 调用，SLA 最高）
         → ② API 在线 URL → 音质逐级降级
         → ③ DB 历史 URL → 兜底
         → ④ stream 远程代理（支持 HTTP Range 拖拽）
```

- 上传前去重：`StorageService.exists()` + `source_id UNIQUE` 索引
- 文件命名：`歌手 - 歌曲名.mp3`
- HTTP Range 支持：拖拽进度条秒级响应

### 3.6 认证安全体系

```
注册/登录 → BCrypt 加密密码 ($2b$10$)
  ↓
后端 Set-Cookie: VIBE_TOKEN (HttpOnly + SameSite=Lax + Secure)
  ↓
JWT Filter 优先级: Authorization Header → Cookie 降级
  ↓
前端 onMount 调用 /auth/me 从 Cookie 恢复登录态
```

**安全措施：**
- 密码 BCrypt 加密，明文永不出现在 DB 中
- JWT httpOnly Cookie 防止 XSS 窃取
- SameSite=Lax + Secure 防 CSRF（手动 Set-Cookie 头构建，兼容所有 Servlet 容器）
- 幂等防护：`X-Request-Id` + Redis 5min 去重
- 所有权校验：歌单/收藏操作验证 user_id

### 3.7 播放历史智能管理

```
每次播放 → 检查最近一条记录
  ├─ 同 sourceId → 仅 UPDATE played_at（去重，节省存储）
  └─ 不同 → INSERT 新记录
  ↓
每 10 次播放触发 1 次清理 → DELETE 保留最近 300 条
```

**设计要点：**
- 概率性清理（10:1 触发比）：DELETE 开销降低 90%
- 双层子查询绕过 MySQL "can't update in FROM" 限制
- `recordCounter` (AtomicInteger) 线程安全计数

---

## 四、技术难点与解决方案

| 难点 | 问题 | 解决方案 |
|------|------|----------|
| **跨平台搜索结果一致性** | 网易云和 QQ 音乐返回格式不同，歌曲名/歌手名写法不统一 | 规范化键：去标点 → 转小写 → 去空格，LinkedHashMap 去重 + pickBest 选优 |
| **搜索缓存污染** | 某平台 API 宕机时，搜索结果偏向另一平台，缓存后用户长期看到不完整结果 | 自动检测：:all 缓存中任平台 0 条 → 删除缓存重建；空结果短 TTL 10s |
| **音频流 CORS 与断点续传** | 浏览器 Audio 元素对跨域流要求 Range 请求支持 | Nginx 透传 Range 头 + 关闭缓冲；后端 Stream 端点支持 `Accept-Ranges` |
| **播放器跨页面状态保持** | 用户切换页面时播放不中断，刷新后恢复进度 | Pinia PlayerStore + 全局单例 `<audio>` + localStorage 持久化 + `beforeunload` 保存 |
| **音质逐级降级阻塞** | 多级降级串联可能达到 20+ 秒无响应 | 8 秒 deadline 超时检查，超时直接中断降级链 |
| **移动端混合内容拦截** | HTTPS 页面加载 HTTP 封面被手机浏览器拦截，封面全白 | 全链路 6 处 API 响应自动 `http://`→`https://` 升级（CoverUrl / PicUrl / AvatarUrl），SQL 查询层 `REPLACE` |
| **N+1 查询问题** | 歌单列表原来先查歌单再逐个查封面 | 合并为单条 SQL：LEFT JOIN + 关联子查询，配合 `idx_pl_added` 索引 |
| **MySQL 索引冗余** | 多表存在被联合索引前缀覆盖的独立索引 | Flyway V2 迁移：删除 3 个冗余索引，新增 2 个缺失索引 |
| **JWT 存储安全** | localStorage 存储 JWT 存在 XSS 窃取风险 | 迁移到 httpOnly Cookie，前端不再直接读写 token |
| **Cookie SameSite 兼容** | `cookie.setAttribute("SameSite")` 在部分 Servlet 容器无效 | 改用 `response.addHeader("Set-Cookie", ...)` 手动构建 Cookie 字符串 |
| **移动端 AI 输入栏遮挡** | 播放栏(pos:fixed 60px) + 标签栏(pos:fixed 56px) 共 116px 固定遮挡，覆盖输入栏 | shell `height: calc(100dvh - 116px)` 精确减掉 bottom bar 占用 |
| **ES 初始化日志噪音** | 每次 detect 产生 Reactor 异常堆栈 | HEAD 请求 + `toBodilessEntity()` 代替 GET，消除响应体释放竞态 |
| **Docker Alpine 兼容性** | 健康检查 wget 不存在 | 改用 curl 或 node 内建 http 模块 |

---

## 五、面试官深度追问预案

### 5.1 性能优化

**Q: 搜索接口 QPS 能做到多少？瓶颈在哪？**

> - 三级缓存分层命中率：Redis ~80%，ES ~15%，API ~5%
> - Redis 单次 < 5ms，ES 单次 ~30ms，API 聚合 ~1s
> - 瓶颈在 musicapi 第三方 API 延迟（不可控），通过 4 秒超时 + 线程池并行控制
> - 优化方向：热点关键词预加载到 Redis，ES 索引优化（IK 扩展词典），API 响应缓存

**Q: 数据库连接池为什么选 HikariCP？max 20 是否合理？**

> - HikariCP 字节码级优化，比 Druid/C3P0 性能更好
> - Spring Boot 4 默认连接池，零额外配置
> - max 20：当前用户量并发低，min-idle 5 保证快速响应
> - 高并发场景上调到 50-100，配合读写分离（备库分担读压力）

**Q: MySQL 慢查询怎么监控？索引怎么优化的？**

> - MySQL 原生 `slow_query_log=ON` + `long_query_time=1`
> - 日志输出到文件，配合 Docker 日志驱动轮转
> - 索引优化原则：删除被联合索引前缀覆盖的独立索引（3 处）；为高频查询字段补充索引（song 表 name + created_at）
> - 生产环境建议接入 Druid 监控面板或 percona-toolkit

### 5.2 缓存一致性

**Q: Redis 缓存和 ES 缓存怎么保证一致性？**

> - 采用 Cache-Aside 模式：先写 DB → 更新 Redis → 异步更新 ES
> - Redis 天然 TTL 兜底（1h），到期自动失效
> - ES 定时清理任务每 6 小时执行
> - 搜索结果是只读缓存，不存在强一致性要求
> - 推荐缓存采用 `refresh=true` 强制穿透机制

### 5.3 安全性

**Q: JWT 过期了怎么办？**

> - JWT 设置 24h 过期（86400000ms）
> - 前端 Axios 拦截器检测 401 → 清除 Cookie → 弹出登录模态框
> - **未实现 refresh token**（当前项目阶段），生产需补充：短命 access token (15min) + 长命 refresh token (7d)

**Q: XSS 和 CSRF 如何防范？**

> - XSS：JWT 从 localStorage 迁移到 httpOnly Cookie，前端无法读取
> - CSRF：SameSite=Strict Cookie 属性，跨站请求不带 Cookie
> - JSON 参数绑定防 SQL 注入（MyBatis `#{}` 预编译）

### 5.4 可扩展性

**Q: 如果要支持更多音乐平台（如网易云/QQ/酷狗/酷我），怎么扩展？**

> - musicapi 网关采用策略模式：每个平台实现统一 `search()` / `getUrl()` / `getLyric()` 接口
> - 聚合层归一化：统一 SongDTO（sourceId, name, artist, coverUrl, duration, platform）
> - 后端 `SongSearchService` 已有 `platform` 参数，新增平台只需在网关层注册

**Q: 歌曲缓存表（song）如果到百万级，怎么优化？**

> - 当前主键 UNIQUE on `source_id`（VARCHAR 100），百万级索引依然高效
> - 加入 Redis 热点歌曲缓存，减少 DB 查询
> - 考虑分库分表：按 source_id 哈希分表（ShardingSphere）
> - `SELECT * LIMIT offset` 大 offset 性能差，改为 ID 范围 + 随机取法

### 5.5 分布式与高可用

**Q: 当前是单机部署，如何做到高可用？**

> - MySQL：主从复制 + 读写分离（ShardingSphere-JDBC 配置）
> - Redis：Sentinel 哨兵模式，自动故障转移
> - 无状态后端：K8s Deployment replicas: 3 + Service LoadBalancer
> - ES：多节点集群 + 副本分片
> - 会话共享：JWT 无状态，不需要 sticky session

**Q: 如何处理高并发播放请求？**

> - 音频流走 Nginx 反向代理，支持 Range 断点续传
> - RustFS（MinIO）天然支持高并发对象读取
> - 播放 API 只做轻量记录（insert/update play_history），音频数据完全不经过后端
> - CDN 加速：RustFS URL 直接暴露给 CDN

---

## 六、核心知识点梳理

### 6.1 后端核心技术

| 知识点 | 在项目中的体现 | 面试重点 |
|--------|---------------|----------|
| **Spring Boot 自动配置** | `application.yml` 多 Profile (dev/docker/prod) | `@ConditionalOnClass`、`@EnableAutoConfiguration` 原理 |
| **MyBatis 动态 SQL** | Mapper XML + LambdaQueryWrapper | `#{}` vs `${}` 预编译、`ON DUPLICATE KEY UPDATE` |
| **Flyway 数据库版本控制** | V1__init.sql → V2__optimize.sql | baseline-on-migrate、repeatable vs versioned migration |
| **JWT 认证流程** | OncePerRequestFilter → SecurityContext | 无状态 vs 有状态、refresh token 设计 |
| **BCrypt 密码加密** | `$2b$10$` 格式存储 | 盐值生成、brypt vs argon2 |
| **HikariCP 连接池** | max 20 / min-idle 5 / max-lifetime 600s | 连接泄漏检测、`leakDetectionThreshold` |
| **H2 内存数据库测试** | `@SpringBootTest` 自动切换 H2 | 测试隔离、`@DataJpaTest` vs `@SpringBootTest` |
| **Redis 缓存策略** | Cache-Aside + TTL 分层 | 缓存穿透/击穿/雪崩、布隆过滤器 |
| **Elasticsearch IK 分词** | 中文歌曲名搜索 + 高亮 | 倒排索引、TF-IDF、BM25 算法 |
| **Docker Compose 编排** | 7 容器 + 健康检查 + 启动依赖 | depends_on vs healthcheck、bridge 网络模式 |

### 6.2 前端核心技术

| 知识点 | 在项目中的体现 | 面试重点 |
|--------|---------------|----------|
| **Vue 3 Composition API** | `<script setup>` + ref/reactive | 响应式原理 Proxy、effect 依赖追踪 |
| **Pinia 状态管理** | PlayerStore / FavoriteStore | reactive Store、getters 缓存、$patch |
| **Vue Router 路由设计** | 桌面/移动端自动分流 | 路由守卫、懒加载、`beforeEach` |
| **Vite 构建优化** | chunk 拆分 (vue-core/pinia/axios) | tree-shaking、Rollup 打包、esbuild |
| **Axios 拦截器** | 自动附 Authorization + 401 处理 | 请求/响应拦截、取消请求 AbortController |
| **组合式函数 (Composables)** | useClickOutside / useAudioBackground / useVirtualList | 逻辑复用、生命周期绑定、内存泄漏防范 |
| **Teleport + Transition** | LyricsView 传送 body + 动画 | 跨 DOM 层级渲染、CSS transition vs JS animation |
| **移动端适配** | 路由分流 `/m` + 独立组件 | 响应式设计 vs 自适应、viewport、触摸事件 |
| **HTTP Range 请求** | 音频流断点续传 | 206 Partial Content、`Content-Range` 响应头 |
| **Nginx 反向代理** | 统一入口 + 静态资源 + API 代理 | 负载均衡、keepalive、Gzip、缓存策略 |

### 6.3 运维与工程化

| 知识点 | 在项目中的体现 | 面试重点 |
|--------|---------------|----------|
| **CI/CD** | GitHub Actions 自动测试 | Pipeline 设计、环境变量注入 |
| **K6 压力测试** | 50 VU 全链路模拟 | QPS/TPS、P95 延迟、错误率 |
| **Sentry 错误监控** | 前端 JS 崩溃自动上报 | 错误分级、source map 上传 |
| **日志分级** | [ES-LAYER]/[CACHE-LAYER]/[API-LAYER] | 日志规范、ELK 技术栈 |
| **MySQL 慢查询优化** | slow_query_log + 索引分析 | EXPLAIN 执行计划、覆盖索引、回表 |
| **Git 工作流** | 功能分支 + 语义化提交 | Git Flow、PR Review |

---

## 七、项目亮点总结（电梯演说）

> 我独立开发了一个全栈音乐平台 vibeMusic，从技术选型到上线全流程覆盖。**前端**用 Vue 3 + Pinia 做了桌面/移动双端交互，**后端**用 Spring Boot + MyBatis-Plus 实现 RESTful API，中间用 **Express 网关**完成网易云和 QQ 音乐的双源聚合。
>
> 性能方面设计了**三级缓存架构**（Redis → ES → API），搜索命中率分层统计，写入了缓存污染自动清理机制。播放部分实现了**六级音质 SLA 降级**保证可用性，RustFS 离线缓存通过 HTTP Range 支持拖拽进度条。
>
> 安全层面做了 **JWT httpOnly Cookie** 防 XSS、BCrypt 密码加密、幂等防护。工程化上做了 Flyway 数据库版本控制、GitHub Actions CI、K6 压测、Sentry 监控。
>
> 最近还深入做了一轮 **MySQL 性能审查**，清理了冗余索引、补充了缺失索引、开启了慢查询日志，对 MyBatis XML 中的关联子查询做了索引依赖分析。移动端做了**全链路 HTTP→HTTPS 升级**和 **AI 助手深度适配**（输入栏固定定位、真实头像、SVG 图标），总共 **63 条测试**覆盖核心链路，**7 个 Docker 容器**一键部署。

---

## 八、可继续深入的方向（面试加分项）

| 方向 | 具体方案 | 体现能力 |
|------|----------|----------|
| **分布式锁** | 用 Redisson 实现歌曲下载幂等（防止并发重复下载） | 分布式一致性理解 |
| **读写分离** | ShardingSphere-JDBC 配置主从数据源 | 数据库架构演进能力 |
| **消息队列** | 播放记录异步写入 Kafka → 离线分析 | 异步解耦、削峰填谷 |
| **CDN 加速** | 对象存储 URL 代理到 CDN，音频文件就近访问 | 网络优化 |
| **灰度发布** | Nginx 按 Cookie 分流新版/旧版后端 | 持续交付 |
| **微服务拆分** | 搜索/推荐/用户 独立服务 + Spring Cloud Gateway | 服务治理 |
| **refresh token** | 双 token 机制，access token 15min + refresh 7d | 安全架构 |
| **WebSocket** | 歌词实时协同、多人听歌房间 | 实时通信 |
| **Druid 监控** | 接入 Druid SQL 监控面板，实时查看慢查询 | 可观测性 |

---

> **最后更新：2026-06-23**
> 本文档聚焦面试场景，建议结合自身理解做口语化练习，不必逐字背诵。
