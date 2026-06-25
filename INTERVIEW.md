# vibeMusic 面试讲解完整版

> 自建全栈音乐平台 — 从 Vue 3 前端到 Spring Boot 后端，从 Express BFF 网关到 7 容器 Docker 部署，全链路一人完成。
> 以下按面试场景结构化编排，涵盖项目概述、架构、核心链路、技术难点、前端深度、后端深度、网关详解、追问预案。

---

## 一、项目概述（30秒电梯演说）

独立开发的全栈音乐平台，支持网易云 + QQ 音乐双源聚合搜索与高品质播放。**前端** Vue 3 桌面/移动双端，**后端** Spring Boot 4 + MyBatis-Plus，中间用 **Express BFF 网关** 做双平台 API 适配和搜索评分聚合。全链路覆盖从 API 网关到前端 UI，7 个 Docker 容器一键部署。

### 项目规模

| 维度 | 数据 |
|------|------|
| 代码量 | 前端 ~12,000 行 + 后端 ~8,000 行 + 网关 ~800 行 |
| 数据库 | 6 张核心表，Flyway V4 版本管理 |
| 容器 | 7 个 Docker（Nginx / Spring Boot / Express / MySQL / Redis / ES / MinIO） |
| 测试 | 42 条后端 + 41 条前端 = 83 条 |
| API | 后端 30+ 端点 + 网关 15+ 端点 |
| 前端页面 | 桌面 11 个 + 移动 12 个（含壳） |
| 缓存层 | Redis + Elasticsearch (IK分词) + 内存 LRU 三级 |

---

## 二、完整技术架构

```
┌──────────────────────────────────────────────────────┐
│              Nginx (port 80) 统一入口                  │
│    静态资源 + SPA fallback + /api/* 反向代理            │
│    Gzip + keepalive 32 + 音频流 proxy_buffering off    │
└──────────┬──────────────────────────┬─────────────────┘
           │                          │
   dist/ 静态资源              /api/* 代理
           │                          │
           ▼                          ▼
┌──────────────────┐    ┌───────────────────────────────┐
│   Vue 3 + Vite    │    │   Spring Boot 4 + Java 17      │
│   桌面 + 移动双端   │    │   MyBatis-Plus + JWT           │
│   Pinia Store      │    │   Redis 缓存 + ES 搜索         │
│   Capacitor APK    │    │   (localhost:8080)             │
└──────────────────┘    └──────┬──────────┬───────────────┘
                               │          │
                    ┌──────────┘          └──────────┐
                    ▼                                ▼
          ┌─────────────────┐          ┌──────────────────────┐
          │    musicapi      │          │  MySQL 8.0 + Redis 7  │
          │  Express.js BFF   │          │  + ES 8.18 (IK分词)   │
          │  (port 3000)     │          │  数据持久化 + 三级缓存  │
          │  网易云 + QQ 音乐  │          └──────────────────────┘
          │  Cookie 统一管理   │
          └────────┬─────────┘
                   │
          ┌────────┴────────┐
          │  MinIO / RustFS  │    ← 歌曲离线缓存，S3 兼容对象存储
          │  对象存储         │
          └─────────────────┘
```

### 技术栈与选型理由

| 技术 | 选型理由 |
|------|----------|
| Vue 3 + Composition API | 组合式函数复用（useAudioBackground/useClickOutside），setup script 简洁 |
| Vite | 秒级 HMR，esbuild 预编译，手动 chunk 拆分精准控制 vendor |
| Pinia | Vue 3 官方状态管理，模块化 Store，支持 setup store 模式 |
| Spring Boot 4 + Java 17 | 最新一代，AOT 支持，生态成熟 |
| MyBatis-Plus | Lambda 构建查询，MetaObjectHandler 自动填充，逻辑删除开箱即用 |
| Flyway | 数据库 schema 版本化，V1→V4 迁移全自动 |
| HikariCP | 字节码级连接池，Spring Boot 默认，性能最优 |
| Redis | 搜索缓存(TTL 1h) + 用户缓存(5min) + 幂等去重(5min) + AI 限流 |
| ES + IK 分词 | 中文歌曲名搜索，毫秒级响应 |
| MinIO | S3 兼容，歌曲离线下载持久化 |
| Docker Compose | 7 容器 + healthcheck + 启动依赖，一键启动全栈 |
| Nginx | 反向代理 + 静态资源 + Gzip + upstream keepalive |

---

## 三、核心业务链路详解

### 3.1 搜索链路（最核心，面试必讲）

```
用户输入"七里香"
        │
        ▼
┌─ ① Redis: GET song:search:v4:七里香:all:1
│   命中 → 返回 SearchResult{ source: "redis", list, total, hasMore }
│   耗时 <5ms，命中率 ~80%
│   未命中 ↓
│
├─ ② Elasticsearch: IK 分词搜索 search_cache 索引
│   命中 → 写 Redis(TTL 1h) → 返回 SearchResult{ source: "es" }
│   耗时 ~30ms，命中率 ~15%
│   未命中 ↓
│
├─ ③ 并行请求双平台 (4s 超时 + 线程池)
│   ├─ 网易云: NeteaseCloudMusicApi.cloudsearch(cookie) → /api/cloudsearch
│   ├─ QQ音乐: qqMusic.api('search', {t:0}) → /api/qq/search  
│   │
│   ├─ 归一化 → SongDTO (sourceId/name/artist/coverUrl/duration/platform)
│   ├─ 评分: 网易云权重1.0, QQ权重0.9, 跨平台+0.3
│   ├─ 去重: normalizeKey(name+artist) → LinkedHashMap → pickBest
│   └─ 排序: finalScore DESC
│
├─ ④ 写入缓存: Redis(TTL 1h) + ES bulk 索引
│   返回 SearchResult{ source: "api" }
│
└─ ⑤ 监控: Micrometer Counter/Timer 记录各层命中率
    前端展示: total 总数 + hasMore 是否还有更多
```

**分层日志**: `[CACHE-LAYER]` / `[ES-LAYER]` / `[API-LAYER]` 标记每次搜索命中层 + 耗时

### 3.2 JWT 认证流程

```
注册/登录
  ↓ POST /api/auth/login {username, password}
  ├─ BCrypt 密码验证 ($2b$10$)
  ├─ 生成 JWT (24h过期, userId嵌入payload)
  ├─ 手动构建 Cookie: VIBE_TOKEN=xxx; HttpOnly; SameSite=Lax; Secure
  └─ 返回 token + 用户信息

后续每次请求
  ↓ JwtAuthenticationFilter (OncePerRequestFilter)
  ├─ ① 从 Authorization: Bearer xxx 读token（优先）
  ├─ ② 从 Cookie VIBE_TOKEN 读token（降级，防XSS）
  ├─ ③ jjwt 验证签名 + 过期
  ├─ ④ 查用户: Redis缓存 5min → DB 兜底
  └─ ⑤ SecurityContextHolder 写入认证信息

防攻击措施：
- XSS: JWT 在 httpOnly Cookie 中，JS 不可读
- CSRF: SameSite=Lax，跨站不带 Cookie  
- 重放: 写操作自动带 X-Request-Id(UUID v4)，Redis 5min 去重
- 密码: BCrypt $2b$10$ 加密，≥8位限制
```

### 3.3 音质 SLA 六级降级

```
LOCAL (RustFS 直出)          ← 零 API 调用，最高 SLA
  ↓
HIRES (96kHz/24bit)          ← 自有 VIP Cookie
  ↓
EXHIGH (48kHz)
  ↓  
HIGHER (320kbps)
  ↓
STANDARD (128kbps)
  ↓
FALLBACK (DB 历史 URL 兜底)

网易云全试 → QQ 跨平台降级 → DB 兜底
8s deadline 超时中断降级链
每次降级记录 degradationCount
```

### 3.4 个性化推荐引擎

```
已登录 → 查 play_history 近30天记录
  ├─ 按歌手聚合权重(播放次数加权)
  ├─ Top5 歌手 → 搜索该歌手歌曲
  ├─ 去重 + 过滤已听 + 排除试听版(≤30s)
  ├─ 批量标记 RustFS 缓存状态(一次DB查询)
  └─ Redis缓存 6h(用户) / 10min(游客)

未登录 → getRandomSongs("热门") 兜底
换一批 → refresh=true 穿透缓存
播放后 → 异步清除推荐缓存
```

### 3.5 播放历史智能管理

```
每次播放 → 检查最近一条记录
  ├─ 同 sourceId → 仅 UPDATE played_at（去重，节省存储）
  └─ 不同 → INSERT 新记录
  ↓
每 10 次播放触发 1 次清理 → ROW_NUMBER() 窗口函数保留最近 300 条
```

### 3.6 musicapi BFF 网关详解

```
作用：后端 Spring Boot 与网易云/QQ 音乐之间的适配层

网易云搜索: 4策略降级链
  cloudsearch+cookie → search+cookie 
  → cloudsearch无cookie → search无cookie
  每策略内部重试3次(500ms→1000ms指数退避)

QQ音乐搜索: 单次调用 qqMusic.api('search', {key, limit, t:0})

聚合搜索 /search:
  并行 Promise.all → 多维评分(相关性×0.4+热度×0.3+排名×0.3)
  → MD5指纹去重 → 黑名单过滤 → refine精炼 → LRU缓存 → 分页返回

Cookie 管理:
  config.js 统一管理 → 启动注入 → 每小时自动巡检 → /cookie-status 端点

日志分类:
  api-errors.log / cookie-monitor.log / degradation.log / access.log
```

### 3.7 前端核心架构

```
App.vue → isMobile() 运行时判断
  ├─ 桌面端: sidebar(220px) + main + PlayerBar
  └─ 移动端: MobileShell + MBottomPlayer + MTabBar

双端共享: stores(5个) + composables(5个) + api层
视图独立: 桌面11页面 + 移动12页面(含壳)

PlayerStore (422行，最复杂):
  - 全局共享 audio (window.vibeAudio)
  - localStorage 持久化: vote_hide/vote_actions/vote_score/vote_down
    - 队列: 300ms debounce
    - 进度: beforeunload flushSave
    - 模式/音量: 即时写入
  - 三播放模式: list-loop / single(audio.loop) / shuffle
  - 刷新恢复: loadedmetadata → seek 到保存位置

useAudioBackground (移动端后台播放):
  - 三层切歌检测: timeupdate抢先切 → pause/ended兜底 → Worker心跳
  - 内联 Web Worker (Blob URL 动态创建，不受主线程节流)
  - iOS 省电对抗: pause监听 + 5级指数退避重试
  - Media Session API: 锁屏显示歌名 + 播放控制
  - Wake Lock: 播放时防止屏幕自动关闭

移动端设计系统 - Velvet Night:
  --m-bg-base: #08080a (4级深度)
  --m-primary: #2ee59a (翡翠绿) + glow
  --m-gold: #f0b90b (琥珀金辅色)
  --m-ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1)
  纯CSS变量，无预处理器依赖
```

---

## 四、技术难点与解决方案（面试重点表格）

| 难点 | 问题描述 | 解决方案 |
|------|---------|----------|
| **跨平台结果一致性** | 网易云用 `songs[].al.picUrl`，QQ用 `list[].albumcover`，两套字段体系 | musicapi 归一化 → 统一 SongDTO，后端 `normalizeKey(name+artist)` → LinkedHashMap 去重 + `pickBest` |
| **搜索缓存污染** | 某平台 API 宕机时，搜索结果单平台偏向，缓存后用户长期看到不完整结果 | 自动检测:all 缓存中任平台0条 → 删除重建；空结果TTL 10s；版本前缀v4自然淘汰 |
| **移动端后台播放** | timeupdate降频(1-3s)、setInterval节流(1min)、iOS强制暂停 | 三层切歌检测 + 内联Web Worker心跳 + 5级指数退避重试 + Wake Lock + Media Session |
| **移动端混合内容拦截** | HTTPS页面加载HTTP封面被浏览器拦截，封面全白 | 全链路6处 `http://`→`https://` 升级 + musicapi 自动替换 + SQL查询层 REPLACE |
| **JWT 存储安全** | localStorage 存储被 XSS 窃取 | 迁移到 httpOnly Cookie + 手动 Set-Cookie 头构建（兼容所有Servlet容器） |
| **N+1 查询** | 歌单列表先查歌单再逐个查封面 | 合并为单条SQL: LEFT JOIN + 关联子查询 + `idx_pl_added` 索引 |
| **移动端 AI 输入栏遮挡** | 播放栏(60px) + 标签栏(56px)共116px固定遮挡 | shell `height: calc(100dvh - 116px)` 精确减掉底部占用 |
| **PlayHistory 时间丢失** | `FieldFill.INSERT` 不处理 UPDATE + `updateFill()` 未填 playedAt | → `INSERT_UPDATE` + `updateFill()` 补上 |
| **下载防并发** | 同一首歌被多次下载 | `ConcurrentHashMap<sourceId, ReentrantLock>` + 无等待线程时清理锁 |
| **前端状态跨页面同步** | 搜索页收藏后歌词页不同步 | 全局 `reactive Set<string>` + 乐观更新 + 10+组件统一接入 |

---

## 五、前端深度讲解

### 5.1 双端架构

**决策**：单一 SPA 而非两套应用。桌面端和移动端共享 stores/composables/API 层，只分离视图层。

**路由守卫**：
```js
beforeEach:
  1. sessionChecked? → tryRestoreSession() 从 httpOnly Cookie 恢复登录
  2. meta.requiresAuth? → 弹 LoginModal（中断式鉴权，不跳转页面）
  3. UA/宽度检测 → 桌面/移动自动分流（静态 map + playlist 动态路径）
```

### 5.2 PlayerStore 核心设计

- 全局单例 audio 挂在 `window.vibeAudio`（Web Worker/Media Session 非Vue上下文也能控制）
- localStorage 分层持久化：高频变化 300ms debounce，关键数据 beforeunload flushSave
- 三播放模式：`list-loop`（顺序）| `single`（`audio.loop=true`）| `shuffle`（`Math.random()`）
- 刷新恢复：`loadedmetadata` 事件 → seek 到保存位置
- Web Audio API：`AnalyserNode` 提供频谱数据

### 5.3 FavoriteStore 全局同步

```
toggleFav() 流程:
  1. 乐观更新 reactive Set → 触发 Vue 响应式
  2. API 调用 toggleFavorite()
  3. 成功后端数据修正
  4. 失败回滚到原始状态
```

### 5.4 构建优化

- target: `es2020`，省 polyfill
- manualChunks: vue-core / pinia / axios / capacitor 独立分包
- Terser: `drop_console:true, passes:2`
- CSS: 按路由拆分，首屏更小

---

## 六、后端深度讲解

### 6.1 搜索三级缓存实现

`SongSearchService.search()` 统一入口，返回 `SearchResult{list, total, hasMore, source}`：

- **Redis 层**：`SongCacheService` 管理，前缀 `song:search:v4:`，TTL 分层（全平台1h/单平台30s）
- **ES 层**：`ESSearchService` 异步索引 + 健康检查 + 不可用自动降级
- **API 层**：4s 超时并行调 musicapi 双平台，`LinkedHashMap` 去重 + `pickBest` 选优
- **监控**：Micrometer Counter(`cache.hit.redis/es`, `cache.miss.api`) + Timer(`search.latency`)

### 6.2 JWT + Redis 用户缓存

```
JwtAuthenticationFilter:
  1. 优先 Bearer Header → 降级 Cookie
  2. jjwt 验证签名/过期
  3. getUserWithCache(): Redis GET "user:auth:{id}" → 命中返回 → miss查DB + 回填(TTL 5min)
  4. SecurityContextHolder 写入
```

### 6.3 下载并发控制

```java
ConcurrentHashMap<String, ReentrantLock> downloadLocks;
// 同一首歌只允许一个线程下载
lock.lock();
try {
    // 长I/O在事务外（30s+ HTTP下载）
    // DB写入用TransactionTemplate短事务（<50ms）
} finally {
    lock.unlock();
    if (!hasQueuedThreads()) remove(lock); // 防内存泄漏
}
```

### 6.4 Flyway 数据库版本管理

```
V1__init.sql    → 6张表 + 唯一索引
V2__optimize.sql → 删3冗余索引 + 加2缺失索引
V3__add_indexes  → 艺术家索引 + url扩容
V4__playlist_dedup → 歌单去重 + UNIQUE(user_id, name)
```

---

## 七、面试追问预案（按考察维度）

### 性能优化

**Q: 搜索QPS/瓶颈？**
> 三级缓存命中率：Redis ~80%, ES ~15%, API ~5%。瓶颈在 musicapi 第三方API延迟（不可控），通过4s超时 + 线程池并行控制。Redis 单次 <5ms。

**Q: 缓存一致性？**
> 搜索结果只读缓存，用 Cache-Aside + TTL + 版本前缀（v4）自然淘汰。推荐缓存 `refresh=true` 强制穿透。不存强一致性问题。

**Q: 慢查询监控？**
> `slow_query_log=ON + long_query_time=1`。做了索引清理（删3冗余 + 加2缺失）。Flyway 版本化管理。

### 安全性

**Q: JWT过期怎么办？**
> 24h过期，前端拦截器 401 → 弹登录框。**我知道但还没实现**：双token机制（access 15min + refresh 7d + Redis 黑名单）。

**Q: XSS/CSRF？**
> XSS: httpOnly Cookie + 前端不读写 token。CSRF: SameSite=Lax + Secure。SQL注入: MyBatis `#{}` 预编译。幂等: X-Request-Id + Redis 5min。

### 架构与扩展

**Q: 为什么加 musicapi 这一层？**
> musicapi 是平台适配层 + Cookie管理中心。Spring Boot 是业务中台——做认证、收藏、歌单、推荐、缓存加速、离线下载。分层清晰，各司其职。

**Q: 支持新平台怎么扩展？**
> musicapi 加平台适配器（统一 `search()/getUrl()` 接口），后端 `SongSearchService` 已有 `platform` 参数，新平台只需在网关层注册即可。

**Q: 高并发播放？**
> 音频流走 Nginx Range 代理 + RustFS 直读，后端只做轻量记录。JWT 无状态，不需要 sticky session。

### 前端深度

**Q: 移动端后台播放怎么实现？**
> 最复杂的一块。内联 Web Worker（Blob URL 创建）绕过主线程 setInterval 节流；三层切歌检测；iOS 省电 pause 对抗用 5 级指数退避重试；Media Session API 锁屏显示。

**Q: 为什么 Audio 挂 window 上？**
> Web Worker 回调、Media Session 回调这些非 Vue 上下文没法用 `useStore()`。挂 window 是最直接的共享方案。

**Q: 收藏状态怎么10+页面同步？**
> 全局 `reactive Set<string>` 单一数据源 + 乐观更新 + 后端修正。任意页面 toggle，所有页面响应式同步。

### 网关深度

**Q: musicapi 搜索怎么做评分的？**
> 每首歌 = 相关性(0.4) + 热度(0.3) + 排名(0.3)，× 平台权重(网易云1.0/QQ0.9)。热度用对数归一化防头部垄断。MD5 指纹去重 + 黑名单降权 + 精确名匹配加分。

**Q: 网易云 4 策略降级为什么 QQ 没有？**
> 网易云 SDK 有 cloudsearch 和 search 两种入口，Cookie 可带可不带 → 4 组合。QQ SDK 只有一个 search 方法，Cookie 进程级全局注入，无变体。

---

## 八、项目亮点总结

1. **全栈独立完成** — Vue 3 + Spring Boot 4 + Express BFF + 7 容器 Docker 部署
2. **三级缓存搜索** — Redis → ES(IK分词) → API，Micrometer 指标追踪，缓存污染自动清理
3. **自研 BFF 网关** — 4 策略降级 + 多维加权评分 + MD5 指纹去重 + Cookie 统一管理
4. **前端深度** — 内联 Web Worker 后台播放、双 Token 认证、全局状态同步、Vite 精细分包
5. **安全体系** — httpOnly Cookie 防 XSS、SameSite 防 CSRF、幂等防护、BCrypt 加密
6. **工程化** — Flyway DB 版本控制、JUnit + Vitest 83 条测试、GitHub Actions CI、K6 压测

---

> **最后更新**: 2026-06-25
> 建议结合自身理解口语化练习，不要逐字背诵。面试官问到一个点，用自己的话展开即可。
