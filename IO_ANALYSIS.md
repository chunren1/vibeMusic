# vibeMusic I/O 性能分析报告

> 基于 2026-06-22 源码审查，按 I/O 类型分层分析，每个问题附带具体代码位置与优化方案。

---

## 一、Network I/O — 网络请求

### 1.1 musicapi HTTP 调用

**涉及文件：** `NeteaseApiService.java`

**当前状态：** ✅ 良好
- 使用 Apache HttpClient 5 连接池（`RestTemplateConfig.java`）
- 连接池配置：maxTotal=100，maxPerRoute=20，connectTimeout=10s，responseTimeout=30s
- 连接池自动驱逐过期和空闲连接
- `RestTemplate` 和 `RestClient` 共享同一连接池

**发现的问题：**

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 🔴 **阻塞式同步调用** | 所有 `restTemplate.exchange()` 均为同步阻塞 | 中 |
| 🟡 **每次请求创建新 HttpHeaders** | `buildHeaders()` 每调用一次新建 `HttpEntity` | 低 |
| 🟡 **URL 构建字符串拼接** | `buildUri()` 用 `StringBuilder` 拼接（安全隐患低，性能可接受） | 低 |

**阻塞式调用影响分析：**

```
SongPlayService.getPlayInfo() 同步调用链：
  → neteaseApiService.getSongUrl()        [阻塞 HTTP，等待 5-10s]
  → neteaseApiService.getSongUrl()        [降级重试]
  → neteaseApiService.searchQQ()          [跨平台降级，阻塞 HTTP]
  → neteaseApiService.getQQSongUrl()      [再次阻塞 HTTP]
    最坏情况：串行 5+ 次同步 HTTP，总计 20s+
```

但在 `SongSearchService` 中，网易云和 QQ 搜索已通过 `SEARCH_EXECUTOR` 线程池并行执行（`Future<List<SongDTO>>` + `getWithTimeout`），这是正确的做法。

**优化建议：**

| 优化 | 方案 |
|------|------|
| **搜索场景** | 已并行 ✅ |
| **播放场景** | 降级链改为非阻塞 → 使用 `CompletableFuture` 链式组合，提前返回第一个可用 URL |
| **歌词/详情等** | 异步返回（返回 `CompletableFuture`）或保持同步（单次请求，开销可接受） |

### 1.2 Elasticsearch HTTP 调用

**涉及文件：** `ESSearchService.java`, `ESCleanupTask.java`

**当前状态：** ✅ 较好
- 使用 `WebClient`（Reactor Netty 非阻塞）发送 ES 请求
- 3 秒超时保护，失败后 `available.set(false)` 自动降级
- `saveAll()` 使用 `_bulk` API 批量写入，避免逐条 HTTP

**发现的问题：**

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 🟡 **bulk 写入 ndjson 在内存中拼接** | `saveAll()` 用 StringBuilder 拼接大 JSON 字符串 | 低 |
| 🟡 **`searchWithHighlight` 手动拼接 JSON** | 直接字符串模板拼接查询 DSL | 低 |
| 🟡 **ES WebClient 无独立连接池控制** | WebClient 默认连接池行为不明 | 低 |
| 🟢 **ES 初始化用了 `new Thread()`** | `ensureIndex()` 用裸线程而非线程池 | 低 |

**优化建议：**

```java
// 1. ES WebClient 连接池显式配置
WebClient.builder()
    .clientConnector(new ReactorClientHttpConnector(
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
            .responseTimeout(Duration.ofSeconds(3))
            .wiretap(false)
    ))
    .baseUrl(esUris).build();

// 2. bulk 写入改为流式（仅当批量大小 > 10MB 时有必要）
// 当前 < 80 条记录，每条约 500 字节，总计 < 40KB，内存拼接可接受

// 3. ES 初始化改用线程池
// 当前用 daemon thread 不影响启动，可接受
```

### 1.3 音频流代理

**涉及文件：** `SongController.java` (`/api/songs/stream`)

**当前状态：** ✅ 良好
- RustFS 路径：直接 Range 读取，CPU/内存开销低
- 远程代理路径：`RestClient.exchange()` 流式透传，使用 `StreamUtils.copy()` 8KB buffer
- 失败重试：CDN URL 过期自动重获 URL 再试一次

**发现的问题：**

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 🟡 **远程代理阻塞 servlet 线程** | `streamFromRemote()` 使用了 `RestClient.exchange()`，虽然是回调形式但内部仍阻塞 | 中 |
| 🟡 **RustFS 存在检查后可能变化** | `storageService.exists()` + 后续读取之间存在 TOCTOU 竞态 | 低 |

**优化建议：**

```java
// 1. 远程音频代理改为响应式非阻塞流
// 使用 WebClient 的 DataBuffer Flux + ServerResponse 流式写回
// 但当前 RestClient + HttpServletResponse 方案足够（servlet 容器有线程池）

// 2. RustFS 读取合并 statObject + getObject 为一次调用
// 使用 getObject() 的异常处理判断是否存在，消除一次额外 HTTP
```

### 1.4 AI 助手 HTTP 调用

**涉及文件：** `AssistantController.java`

**当前状态：** ⚠️
- 使用 `RestTemplate.postForEntity()` 同步调用 SiliconFlow API
- **阻塞 Servlet 线程等待 AI 响应**（通常 3-10 秒）

**优化建议：**

```java
// 改为 Server-Sent Events (SSE) 流式返回
@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chat(@RequestBody ChatRequest req) {
    return webClient.post()
        .uri("https://api.siliconflow.cn/v1/chat/completions")
        .bodyValue(req)
        .retrieve()
        .bodyToFlux(String.class)
        .onErrorResume(e -> Flux.just("AI 服务暂时不可用"));
}
```

---

## 二、Database I/O — MySQL 查询

### 2.1 连接池配置

**涉及文件：** `application.yml` (HikariCP)

**当前状态：** ✅ 良好
```
maximum-pool-size: 20
minimum-idle: 5
connection-timeout: 5000ms
max-lifetime: 600000ms (10min)
idle-timeout: 300000ms (5min)
```

**优化建议：**
- 当前配置适用于低并发场景，用户量增长后考虑上调 `max-pool-size` 至 50
- 添加 `leak-detection-threshold: 10000` 用于开发环境排查连接泄漏

### 2.2 查询分析

| 查询位置 | SQL 类型 | 阻塞性 | 优化状态 |
|----------|---------|--------|----------|
| `PlaylistMapper.listPlaylistsWithStats` | 单次 JOIN + 关联子查询 | 同步 | ✅ 已优化 |
| `SongMapper.insertOrUpdateUrl` | ON DUPLICATE KEY | 同步 | ✅ 一次 SQL 完成 |
| `SongMapper.findRandomSongs` | LIMIT + OFFSET | 同步 | ⚠️ 大表 OFFSET 低效 |
| `FavoriteService.favoritesSet` | LIMIT 999 | 同步 | ⚠️ 全量读取收藏 ID |
| `PlayHistoryService.recent` | LIMIT + ORDER BY | 同步 | ✅ 索引覆盖 |
| `PlayHistoryService.record` | SELECT + INSERT/UPDATE | 同步 | ✅ 去重优化 |

**发现的问题：**

| 问题 | 位置 | 优化方案 |
|------|------|----------|
| 🟡 **大 OFFSET 分页** | `findRandomSongs` | 改用 ID 范围 + 随机偏移（`WHERE id > randomStart LIMIT N`） |
| 🟡 **收藏 ID 全量读取** | `favoritesSet` 99 9 条全查 | 初始只查最近 100 条，滚动加载 |
| 🟢 **N+1 已在之前优化** | `listPlaylistsWithStats` | ✅ 单次 SQL 完成 |
| 🟢 **索引优化已完成** | V2__optimize.sql | ✅ 冗余索引已删除 |
| 🟢 **慢查询已开启** | docker-compose.yml | ✅ long_query_time=1 |

### 2.3 事务范围

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 🟡 **事务内包含 HTTP 调用** | `DownloadService.doDownload()` | 高 |
| 🟡 **事务内包含 MinIO 上传** | `DownloadService.doDownload()` | 中 |

```java
// DownloadService.java:52-94
@Transactional(rollbackFor = Exception.class)
private String doDownload(...) {
    // 1. MinIO exists()       ← HTTP I/O
    // 2. songPlayService.getPlayUrl()  ← HTTP I/O（调用 musicapi）
    // 3. neteaseApiService.downloadSongToFile() ← HTTP I/O（下载 mp3）
    // 4. storageService.uploadStream() ← HTTP I/O（上传 MinIO）
    // 5. songService.saveDownloadedSong() ← DB I/O
}
```

**问题：** 事务内包含 4 次 HTTP 调用，事务持有时间 = HTTP 下载时间（可能 30s+），期间 MySQL 连接和行锁不释放。

**优化方案：**

```java
// 将 HTTP I/O 提到事务外，仅 DB 操作在事务内
// 步骤 1-3 在事务外完成（幂等性由下载锁保证）
// 步骤 4-5 在独立事务内完成
@Transactional(rollbackFor = Exception.class)
private void persistSongResult(String sourceId, String rustfsUrl, ...) {
    songService.saveDownloadedSong(sourceId, ...);
    // 仅 DB 操作，事务 < 50ms
}
```

---

## 三、File I/O — 文件读写

### 3.1 歌曲下载临时文件

**涉及文件：** `NeteaseApiService.java`, `DownloadService.java`

**当前状态：** ✅ 良好
- 使用 `File.createTempFile()` 创建临时文件
- `finally` 块中调用 `tempFile.delete()`，失败时 `deleteOnExit()`
- 流式写入：`FileOutputStream` + 8KB buffer

**发现的问题：**

| 问题 | 位置 | 严重度 |
|------|------|--------|
| 🟢 **临时文件管理** | `DownloadService.doDownload()` | 已正确处理 ✅ |
| 🟢 **流式下载防 OOM** | `NeteaseApiService.downloadSongToFile()` | ✅ |

### 3.2 音频流拷贝

**涉及文件：** `SongController.java`, `StreamUtils.java`

**当前状态：** ✅ 良好
- 8KB buffer 流式拷贝
- RustFS 直读：支持 Range seek
- 远程代理：`RestClient.exchange()` 流式透传

**优化建议：**

```java
// 增大 stream buffer 到 64KB 提升吞吐量
// StreamUtils.java 中的 copy 方法
byte[] buf = new byte[65536]; // 当前: 8192
```

### 3.3 已废弃的全量下载 API

**涉及文件：** `NeteaseApiService.java:123`

```java
@Deprecated
public byte[] downloadSong(String downloadUrl) {
    // 将整个 mp3 加载到 byte[] → OOM 风险
    ResponseEntity<byte[]> response = restTemplate.exchange(...);
    return response.getBody();
}
```

**建议：** 删除此方法（已标记 `@Deprecated` 且无调用方）。

---

## 四、Object Storage I/O — MinIO/RustFS

### 4.1 当前调用模式

**涉及文件：** `StorageService.java`

| 操作 | 方法 | 调用次数 |
|------|------|----------|
| 存在性检查 | `exists()` → `statObject()` | 每次播放/推荐检查 |
| 上传 | `upload()` / `uploadStream()` | 下载服务 |
| 读取 | `getObject()` / `getObjectRange()` | 音频流转发 |
| 无 HTTP 调用 | `getDirectUrl()` | 仅字符串拼接 |

### 4.2 问题分析

| 问题 | 位置 | 严重度 | 优化方案 |
|------|------|--------|----------|
| 🔴 **每次播放检查 RustFS 存在性** | `SongPlayService.getPlayInfo()` + `SongPlayService.getPlayUrl()` | 中 | 首次播放结果缓存到 Redis |
| 🔴 **推荐结果逐首检查 RustFS** | `RecommendService.markOfflineStatus()` — N 次 `statObject` | 高 | 批量检查或本地缓存 |
| 🟡 **RustFS URL 每次拼接** | `getDirectUrl()` 返回字符串（无副作用） | 低 | 可接受 |
| 🟡 **初始化时同步建桶** | `@PostConstruct init()` — 启动时阻塞 | 低 | 异步化 |

**推荐批量检查严重性高：**

```java
// RecommendService.markOfflineStatus() — 8 首推荐 = 8 次 statObject HTTP 调用
private void markOfflineStatus(List<SongDTO> songs) {
    for (SongDTO s : songs) {
        s.setCached(storageService.exists("songs/" + s.getSourceId() + ".mp3"));
        // 每次都发一次 HTTP HEAD 到 MinIO
    }
}
```

**优化方案：**

```java
// 方案 A: Redis 缓存 RustFS 文件列表（推荐）
// 维护一个 Set<String> cachedSongIds，TTL 5分钟
// markOfflineStatus 先查 Redis，不存在才查 MinIO

// 方案 B: 直接检查 DB song 表（下载时会更新 url 字段）
// song.getUrl() != null → 存在 RustFS
// 只需一次 MySQL 查询，消除 MinIO HTTP 调用
```

### 4.3 MinIO Client 线程安全

当前 `MinioClient` 实例是单例（`@PostConstruct` 创建），MinIO 官方文档声明 `MinioClient` 是线程安全的，✅ 无问题。

---

## 五、Cache I/O — Redis

### 5.1 当前操作审计

| 位置 | 操作 | 频率 | 优化状态 |
|------|------|------|----------|
| `SongCacheService` | `GET` / `SET` 搜索结果 | 每次搜索 | ✅ TTL 分层 |
| `RecommendService` | `GET` / `SET` / `DELETE` 推荐结果 | 每次推荐 | ✅ 污染检测 |
| `IdempotentGuard` | `SETNX` 去重键 | 写请求 | ✅ 5min 过期 |

### 发现的问题

| 问题 | 位置 | 优化方案 |
|------|------|----------|
| 🟡 **搜索结果缓存粒度粗** | `SongCacheService` 缓存整个 keyword 全部结果 | 按 page 分片缓存 |
| 🟡 **推荐缓存未限制大小** | `RecommendResult` 序列化整个对象（含 SongDTO 列表） | 已有 TTL 兜底 ✅ |

**优化建议：**

```java
// 搜索结果按 page 分片缓存
// 当前: song:search:v4:{keyword} → 缓存全部结果
// 优化: song:search:v4:{keyword}:page:{page} → 只缓存当前页
// 好处: 分页切换无需重建全量缓存, TTL 一致
```

### 5.2 Redis 连接池

**涉及文件：** `application.yml`

```yaml
spring.data.redis:
  lettuce.pool:
    max-active: 8
    max-idle: 8
    min-idle: 0
```

**分析：**
- max-active=8 偏低，当前足够（低频操作）
- min-idle=0：首次请求时需建立连接，建议设为 2

**优化：**

```yaml
spring.data.redis.lettuce.pool:
  max-active: 20
  min-idle: 2           # 避免冷启动连接延迟
```

---

## 六、定时任务 I/O

| 任务 | 文件 | I/O 类型 | 频率 | 状态 |
|------|------|---------|------|------|
| 播放历史清理 | `PlayHistoryCleanupService` | MySQL DELETE | 每天 3:00 | ✅ |
| ES 缓存清理 | `ESCleanupTask` | ES DELETE_BY_QUERY | 每 6 小时 | ✅ |

无并发冲突：两个任务操作不同数据源（MySQL vs ES）。

---

## 七、优化优先级总览

| 优先级 | 模块 | 问题 | 影响 | 工作量 |
|--------|------|------|------|--------|
| 🔴 P0 | DB 事务 | 事务内包含 HTTP I/O 导致长事务 | 连接池耗尽、死锁风险 | 30min |
| 🔴 P0 | RustFS | 推荐结果逐首 `statObject`（8 次 HTTP/次推荐） | 推荐接口延迟 50-200ms | 20min |
| 🟡 P1 | AI 助手 | 同步阻塞调用导致 servlet 线程长时间占用 | 用户等待、线程池压力 | 30min |
| 🟡 P1 | RustFS | 每次播放检查 `exists()` 可缓存 | 重复 HTTP 调用 | 15min |
| 🟡 P1 | Redis | 搜索结果缓存粒度粗（全量 vs 分页） | 缓存命中率 | 20min |
| 🟢 P2 | Redis | min-idle=0 冷启动延迟 | 首次请求 < 50ms | 1min（改配置） |
| 🟢 P2 | Stream | 8KB buffer → 64KB | 吞吐量提升 | 1min |
| 🟢 P3 | 已废弃代码 | `downloadSong(byte[])` 删除 | 减少维护 | 1min |

---

## 八、快速修复清单

### 立即实施（3 项，共 1 小时）

```java
// 1. DownloadService — 事务拆分（P0）
private String doDownload(...) {
    // 步骤 1-3: 事务外 → 幂等锁保护
    String rustfsUrl = downloadAndUpload(...);  // HTTP I/O
    // 步骤 4: 独立事务 → 仅 DB
    persistSongResult(sourceId, rustfsUrl, ...);
}

// 2. RecommendService — 缓存检查替代 MinIO statObject（P0）
private void markOfflineStatus(List<SongDTO> songs) {
    // 改为: 检查 song.url != null → 已缓存
    // 从 DB 一次查全，替代 N 次 MinIO HTTP
    Set<String> cachedIds = songMapper.selectList(...)
        .stream().map(Song::getSourceId).collect(Collectors.toSet());
    for (SongDTO s : songs) s.setCached(cachedIds.contains(s.getSourceId()));
}

// 3. application.yml — Redis min-idle（P2）
spring.data.redis.lettuce.pool.min-idle: 2
```

---

> **最后更新：2026-06-22**
> 本报告基于完整源码审查，每个问题均标注了具体文件位置和代码片段。
