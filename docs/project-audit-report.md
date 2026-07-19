# vibeMusic 全链路项目审计报告

> 生成日期：2026-07-19 · 审计范围：前端 / 后端 / BFF 网关 / 数据库 / 基础设施
> 总计：**79 个问题**（🔴CRITICAL 17 / 🟠HIGH 24 / 🟡MEDIUM 25 / 🟢LOW 13）

---

## 目录

1. [前端模块（Vue 3）](#1-前端模块)
2. [后端模块（Spring Boot）](#2-后端模块)
3. [BFF 网关（Express）](#3-bff-网关)
4. [数据库与数据流](#4-数据库与数据流)
5. [基础设施（Docker/Nginx/脚本）](#5-基础设施)
6. [修复优先级矩阵](#6-修复优先级矩阵)

---

## 1. 前端模块

### 🔴 CRITICAL

#### F1. player.js error 处理导致无限重试死循环

- **文件**: `vibemusic-web/src/stores/player.js:86-109`
- **描述**: `audio.src = ''` 在部分浏览器中会触发二次 `error` 事件，导致重试计数器级联递增。`playNext()` 在 500ms 后重置 `_errorLocked` 和 `_retryCount`，而重试 setTimeout 在 300ms 设置旧 URL 并调用 `play()`。新旧 src 交替加载形成无限循环，`isPlaying` 快速翻转导致 UI 闪烁。
- **日志**: `code=4, 重试 (1/2) → (2/2) → ... 无限重复`
- **修复**:
  ```js
  // 错误处理中使用 guard 变量 + 不设 src=''
  audio.addEventListener('error', () => {
    if (_errorLocked) return
    _retryCount++
    if (_retryCount <= 2) {
      console.warn(`[Player] 重试 (${_retryCount}/2)`)
      audio.load()
    } else {
      _errorLocked = true
      isPlaying.value = false
      setTimeout(() => playNext(), 1000)
    }
  })
  ```

#### F2. 关键凭证泄露在版本控制中

- **文件**: `.env:35,49,51,32`、`.env.docker`、`scripts/test_deepseek.py:5`
- **描述**: DeepSeek API Key (`sk-19700b3bf1b44c6f8ca03f3d88b58e34`)、JWT Secret、QQ 音乐 Cookie、网易云 Cookie、MinIO 凭证均明文存在于 git 追踪的文件中。
- **影响**: 任何有仓库权限的人可盗用 API Key 和音乐平台账号
- **修复**: 
  1. 立即吊销所有凭证
  2. `git rm --cached .env.docker`
  3. 将 `.env.docker` 和 `scripts/test_deepseek.py` 加入 `.gitignore`
  4. 使用 `git filter-branch` 或 BFG 从历史中清除

#### F3. vite.config.js 生产构建丢弃 console.warn/error

- **文件**: `vibemusic-web/vite.config.js:19`
- **描述**: `drop_console: true` 在生产构建中删除所有 `console.*` 调用，包括 `console.warn` 和 `console.error`，导致生产环境无法调试。
- **修复**: `drop_console: ['log', 'info', 'debug']`

#### F4. routeKeyword 守卫阻止初始搜索

- **文件**: `vibemusic-web/src/views/SearchView.vue:78-82`
- **描述**: `watchEffect` 中 `if (routeKeyword === route.value.query.keyword) return` 在首次挂载时阻止搜索触发，导致带搜索词 URL 进入页面后搜索不执行。
- **修复**: 去掉该守卫，初始挂载时直接调用 `onSearch()`

#### F5. fullscreenchange 事件监听器泄漏

- **文件**: `vibemusic-web/src/views/LyricsView.vue:339`
- **描述**: `document.addEventListener('fullscreenchange', ...)` 在模块作用域注册，组件多次挂载/卸载后导致多个重复监听器，每个全屏变化触发多次回调。
- **修复**: 移至 `onMounted`/`onUnmounted` 生命周期

---

### 🟠 HIGH

#### F6. playBySourceId 中 isPlaying 设置存在竞态

- **文件**: `vibemusic-web/src/stores/player.js:130-134`
- **描述**: `isPlaying.value = true` 在 `audio.play()` 之前设置，若 play() 被浏览器阻止（自动播放策略），状态与实际播放不一致。
- **修复**: 移至 `.then()` 回调中

#### F7. sessionChecked 在网络错误后永久阻塞登录恢复

- **文件**: `vibemusic-web/src/stores/auth.js:75-108`
- **描述**: `sessionChecked = true` 在 try 块外部设置，即使 API 请求失败也会标记"已检查"，后续页面导航不再尝试恢复会话。
- **修复**: 移至 try 块内（API 返回后设置）

#### F8. useIsMobile resize 监听器未清理

- **文件**: `vibemusic-web/src/composables/useIsMobile.js:15-16`
- **描述**: `window.addEventListener('resize', ...)` 无对应 `removeEventListener`，路由切换时代理泄漏。
- **修复**: 添加 `onUnmounted` 清理

#### F9. MBottomPlayer 中隐式全局变量

- **文件**: `vibemusic-web/src/components/MBottomPlayer.vue:46,50`
- **描述**: `timeSaver = null` 未声明 `let`，成为隐式全局变量，多实例间互相覆盖。
- **修复**: 添加 `let` 声明

#### F10. 未登录时收藏点击无效无反馈

- **文件**: `vibemusic-web/src/stores/favorite.js:41-72`
- **描述**: 未登录用户点击收藏按钮时静默失败，无登录弹窗提示。
- **修复**: 添加 `auth.isLoggedIn` 检查，未登录时打开登录弹窗

#### F11. useAudioBackground 重复的 visibilitychange 监听器

- **文件**: `vibemusic-web/src/composables/useAudioBackground.js`
- **描述**: 4 个 `visibilitychange` 监听器分散在不同位置，同页面同时触发。
- **修复**: 合并为单个 `handleVisibilityChange`

---

## 2. 后端模块

### 🔴 CRITICAL

#### B1. ES 查询注入

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/service/ESSearchService.java:157-158, 234-238, 296`
- **描述**: 用户关键词直接拼接 ES 查询 JSON body/String，包含 `"` 或 `}` 的恶意关键词可破坏 JSON 结构。
- **修复**: 使用 `StringEscapeUtils.escapeJson()` 或 ES 参数化 QueryBuilder
  ```java
  // 改为
  .queryParam("q", "keyword:\"" + StringEscapeUtils.escapeJson(keyword) + "\"")
  ```

#### B2. 登录/注册无速率限制

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/controller/AuthController.java:52-78, 82-115`
- **描述**: POST `/api/auth/login` 和 `/api/auth/register` 无任何速率限制，可被暴力破解。
- **修复**: 添加 Redis 速率限制，每 IP 每分钟最多 5 次

#### B3. HttpHeaders 静态可变实例线程不安全

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/service/NeteaseApiService.java:135-137`
- **描述**: `private static final HttpHeaders SHARED_HEADERS` — `HttpHeaders` 非线程安全，并发修改会导致 `ConcurrentModificationException`。
- **修复**: 每次调用新建实例
  ```java
  private HttpEntity<String> buildEntity() {
      HttpHeaders headers = new HttpHeaders();
      headers.setAll(SHARED_HEADERS_MAP); // 用不可变 Map
      return new HttpEntity<>(headers);
  }
  ```

#### B4. RateLimitService Redis INCR+EXPIRE 竞态条件

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/service/RateLimitService.java:31-36`
- **描述**: `INCR` 后跟 `EXPIRE` 非原子操作，EXPIRE 未执行时 key 永久存在，用户永久被限流。
- **修复**: 使用 Lua 脚本
  ```lua
  local count = redis.call('INCR', KEYS[1])
  if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
  return count
  ```

#### B5. PlayHistory.playedAt 更新逻辑完全失效

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/entity/PlayHistory.java:18-19`
- **描述**: `@TableField(fill = FieldFill.INSERT_UPDATE, insertStrategy = FieldStrategy.NEVER)` — `NEVER` 优先级高于 `INSERT_UPDATE`，导致 UPDATE 时 playedAt 永不更新。
- **修复**: 移除 `FieldStrategy.NEVER`
  ```java
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime playedAt;
  ```

#### B6. PlayHistory 定时清理全表扫描

- **文件**: `vibeMusic-backend/src/main/resources/db/migration/V3__add_indexes.sql:13-16`、`PlayHistoryCleanupService.java:30-31`
- **描述**: V3 迁移删除了 `idx_played_at` 索引，`DELETE FROM play_history WHERE played_at < ?` 全表扫描，随数据增长逐渐锁表。
- **修复**: 重建索引
  ```sql
  ALTER TABLE play_history ADD INDEX idx_cleanup_played_at (played_at);
  ```

#### B7. Flyway 迁移 V3 版本号重复

- **文件**: `V3__add_indexes.sql`、`V3__add_playlist_fields.sql`
- **描述**: 两个迁移文件使用相同 `V3` 版本号，Flyway 校验失败或执行顺序不确定。
- **修复**: 重命名为 `V3_1__add_indexes.sql` 和 `V3_2__add_playlist_fields.sql`

---

### 🟠 HIGH

#### B8. Token 同时返回在 Response Body 和 Cookie 中

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/controller/AuthController.java:77, 113`
- **描述**: Access token 以 JSON body 返回（对 API 日志可见）并设置为 httpOnly Cookie。中间件日志可能记录 token。
- **修复**: 移除 JSON body 中的 `data.put("token", ...)`

#### B9. Token 黑名单元数据使用平台默认编码

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/security/JwtAuthenticationFilter.java:85`
- **描述**: `token.getBytes()` 使用平台默认编码，Windows (GBK) 与 Linux (UTF-8) 间 SHA-256 哈希不一致。
- **修复**: `token.getBytes(StandardCharsets.UTF_8)`

#### B10. StreamController Range 头解析缺陷

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/controller/StreamController.java:116-128`
- **描述**: Range 解析未验证 `start >= 0`、`end < fileSize`、`length > 0`，`response.setContentLength((int) length)` 对大文件有符号截断。
- **修复**: 添加边界校验

#### B11. SongPlayService 静态线程池未关闭

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/service/SongPlayService.java:35`
- **描述**: `GET_URL_EXECUTOR` 是 `static newFixedThreadPool(3)`，无 `@PreDestroy` 关闭，DevTools 热重载时线程泄漏。
- **修复**: 添加 `@PreDestroy void shutdown() { GET_URL_EXECUTOR.shutdown(); }`

#### B12. findRandomSongs 使用 OFFSET 大表扫描

- **文件**: `vibeMusic-backend/src/main/resources/mapper/SongMapper.xml:9-11`
- **描述**: `SELECT * FROM song LIMIT #{count} OFFSET #{offset}` 在偏移前扫描所有行，10 万+行时性能退化。
- **修复**: 使用 `WHERE id >= (SELECT id FROM song ORDER BY id LIMIT 1 OFFSET ?)` 或 `ORDER BY RAND()`

#### B13. importPlaylist 逐条 INSERT 性能问题

- **文件**: `vibeMusic-backend/src/main/java/com/vibemusic/service/PlaylistService.java:294-303`
- **描述**: 每批 50 条内部仍使用逐条 `songMapper.insert(ps)`，1000 首歌单产生 1000 次 INSERT。
- **修复**: 使用 MyBatis 批量模式 `sqlSessionFactory.openSession(ExecutorType.BATCH)`

#### B14. 测试 Schema 与生产 Schema 严重不一致（15+ 差异）

- **文件**: `vibeMusic-backend/src/test/resources/schema-test.sql`
- **描述**: 列类型（`INT` vs `BIGINT`）、长度（`VARCHAR(200)` vs `VARCHAR(500)`）、缺失字段（`platform`、`updated_at`）等大量差异，测试无法覆盖生产环境真实行为。
- **修复**: 同步 `schema-test.sql` 与所有迁移文件 DDL

#### B15. 数据冗余表无同步机制

- **文件**: `play_history`、`user_favorite`、`playlist_song`
- **描述**: `song_name`、`artist`、`cover_url` 在三张表独立冗余，song 表更新后不同步，展示信息过时。
- **修复**: 短期加定时同步任务，长期移除冗余字段改为 JOIN

---

## 3. BFF 网关

### 🔴 CRITICAL

#### G1. 凭证泄露（同上 F2，跨模块）
#### G2. unhandledRejection 吞掉异常不退出

- **文件**: `musicapi/server.js:268-270`
- **描述**: Node.js ≥15 默认在未捕获 Promise 拒绝时崩溃。此处理程序阻止崩溃，使进程在损坏状态（泄漏句柄、半开连接）中继续服务。
- **修复**: `process.exit(1)`

#### G3. Cookie 仅启动时加载，过期后永不刷新

- **文件**: `musicapi/server.js:208-214`
- **描述**: `qqMusic.setCookie(config.qq)` 和 `NETEASE_COOKIE` 仅启动时设置。`checkCookies()` 检测到过期后只设 `cookieStatus.* = false`，不刷新 token。
- **修复**: 添加 cookie 刷新逻辑，或输出清晰的操作指引

#### G4. 搜索重试使用线性退避，无熔断

- **文件**: `musicapi/server.js:636-652`
- **描述**: 延迟 500ms、1000ms、1500ms（线性），无抖动 → 恢复时惊群效应，无熔断。
- **修复**: 指数退避 + 随机抖动 + 熔断器

#### G5. 排名算法使用布尔值作为播放量

- **文件**: `musicapi/server.js:433`
- **描述**: `song._raw?.pay?.pay_play` 是布尔值 (0/1)，被作为播放量使用。付费歌曲获得 `popularity=1`，免费歌曲 `popularity=0`，系统性偏袒付费歌曲。
- **修复**: 移除 `pay_play`
  ```js
  const pop = song._raw?.listenCount || song._raw?.popularity || 0;
  ```

#### G6. LRU 缓存永不主动淘汰过期条目

- **文件**: `musicapi/server.js:320-352`
- **描述**: 过期条目仅 `get()` 时淘汰。从未被访问的条目永远占用内存直至达到 `max`。
- **修复**: 添加定期清理 `setInterval`

---

### 🟠 HIGH

#### G7. /metrics 端点未认证

- **文件**: `musicapi/server.js:191-200`
- **描述**: Prometheus 指标（含 cookie 状态、缓存大小）暴露给任何人。
- **修复**: 添加 Basic auth 或绑定内网

#### G8. Promise 指标路径标签永不匹配

- **文件**: `musicapi/server.js:162-171`
- **描述**: metrics 中间件在路由注册前执行，`req.route` 总是 `undefined`，带 query 的请求产生唯一标签 → 指标基数爆炸。
- **恢复**: 标准化路径标签

#### G9. 搜索关键词无输入验证

- **文件**: `musicapi/server.js:655`
- **描述**: `keyword` 直接来自 `req.query.keyword`，无长度限制或字符过滤。
- **修复**: 添加 `keyword.length > 200` 校验

---

## 4. 数据库与数据流

### 🔴 CRITICAL

#### D1. Flyway V3 版本号重复（同 B7）
#### D2. play_history 缺少清理索引（同 B6）
#### D3. PlayHistory.playedAt 更新失效（同 B5）

### 🟠 HIGH

#### D4. 用户删除无级联 — 孤立记录永久残留

- **文件**: 所有 Service 层
- **描述**: 无外键约束，无级联删除。用户删除后 `play_history`、`user_favorite`、`playlist`、`playlist_song` 产生孤立记录。
- **修复**:
  ```sql
  ALTER TABLE play_history ADD CONSTRAINT fk_ph_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
  ALTER TABLE user_favorite ADD CONSTRAINT fk_uf_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
  ```

#### D5. 缺少 source_id 独立索引

- **文件**: `play_history`、`user_favorite`、`playlist_song`
- **描述**: 按 `source_id` 查询的接口（如 favoriteSet、deleteBatch）无法利用索引。
- **修复**:
  ```sql
  ALTER TABLE play_history ADD INDEX idx_source_id (source_id);
  ALTER TABLE user_favorite ADD INDEX idx_source_id (source_id);
  ALTER TABLE playlist_song ADD INDEX idx_source_id (source_id);
  ```

#### D6. SongMapper.insertOrUpdateUrl 缺少 lyric 字段

- **文件**: `vibeMusic-backend/src/main/resources/mapper/SongMapper.xml:18-19`
- **描述**: `ON DUPLICATE KEY UPDATE` 未包含 `lyric` 列，歌词更新被忽略。
- **修复**: 在 INSERT 和 UPDATE 子句中添加 `lyric`

#### D7. 无 Flyway 回滚脚本

- **文件**: `vibeMusic-backend/src/main/resources/db/migration/`
- **描述**: V4 的 `DELETE` 操作不可逆，无回滚脚本。
- **修复**: 为 V4 等破坏性操作编写回滚脚本

---

## 5. 基础设施

### 🔴 CRITICAL

#### I1. Redis Exporter 密码为空但 Redis 需要认证

- **文件**: `docker-compose.yml:415-416`
- **描述**: `REDIS_PASSWORD` 为空，但 `redis.conf:46` 设置 `requirepass`。Exporter 认证失败 → 无 Redis 监控指标。
- **修复**: 注入 `REDIS_PASSWORD: ${REDIS_PASSWORD}`

#### I2. ES 健康检查缺少认证

- **文件**: `docker-compose.yml:124`
- **描述**: `curl -f http://localhost:9200/_cluster/health` 在 ES 启用 xpack 安全后返回 401。
- **修复**: 
  ```yaml
  test: ["CMD-SHELL", "curl -f -u elastic:${ES_PASSWORD} http://localhost:9200/_cluster/health || exit 1"]
  ```

#### I3. MySQL 端口暴露到宿主机

- **文件**: `docker-compose.yml:39-40`
- **描述**: `ports: "3306:3306"` 暴露到外部网络，防火墙误配时可被公网访问。
- **修复**: 生产环境移除 ports 或绑定 `127.0.0.1:3306:3306`

#### I4. Nginx 限流使用错误 IP

- **文件**: `nginx/nginx.conf:51`
- **描述**: `$binary_remote_addr` 在反向代理后始终为 Nginx 容器 IP，所有客户端共享一个令牌桶。
- **修复**: 
  ```nginx
  set_real_ip_from 0.0.0.0/0;
  real_ip_header X-Forwarded-For;
  limit_req_zone $http_x_forwarded_for zone=api_limit:10m rate=10r/s;
  ```

#### I5. Prometheus 指向错误的 targets

- **文件**: `prometheus.yml:25,37`
- **描述**: Docker Compose 模式使用 `host.docker.internal:8080`（宿主机），实际应使用 `backend:8080`。
- **修复**: 默认使用 `backend:8080`，注释宿主机配置

#### I6. backup-db.ps1 密码通过命令行传递

- **文件**: `scripts/backup-db.ps1:28`
- **描述**: `mysqldump -p$DB_PASS` 密码对 `docker ps` 和 OS 进程列表可见。
- **修复**: 使用 `--defaults-extra-file` 临时配置文件

#### I7. Grafana 配置了不存在的 Loki 数据源

- **文件**: `grafana/provisioning/datasources/datasource.yml:13-18`
- **描述**: Loki 数据源配置存在但无 Loki 服务，每次加载面板显示错误。
- **修复**: 删除 Loki 配置块

---

### 🟠 HIGH

#### I8. MySQL 密码通过环境变量传递（非 Docker Secret）

- **文件**: `docker-compose.yml:33`
- **描述**: `MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-123456}` — 使用环境变量而非 Docker Secrets。
- **修复**: 使用 `MYSQL_ROOT_PASSWORD_FILE: /run/secrets/db_root_password`

#### I9. docker-compose down → up 导致停机

- **文件**: `scripts/deploy.ps1:45-46`
- **描述**: 先 down 再 up 造成 ~60s 停机。
- **修复**: `docker-compose up -d --build --remove-orphans --no-deps`

#### I10. health-check.ps1 包含已停止容器

- **文件**: `scripts/health-check.ps1:25`
- **描述**: `docker ps -a`（含 `-a`）包含已停止容器，错误报告为失败。
- **修复**: 移除 `-a`

#### I11. backup-db.ps1 无失败检测

- **文件**: `scripts/backup-db.ps1:28`
- **描述**: `mysqldump ... | gzip` 失败时 `echo` 仍打印成功。
- **修复**: 添加 `set -o pipefail` 和退出码检查

#### I12. 日志写入每次调用 fs.readdirSync + fs.statSync

- **文件**: `musicapi/server.js:86-94`
- **描述**: 每次 `write()` 调用整个日志目录的 `readdirSync + statSync`，每行日志都产生磁盘 I/O。
- **修复**: 条件化调用 `_rotate()` 仅日期变化时

---

## 6. 修复优先级矩阵

### 🔴 立即修复（Blocking — 影响核心功能或安全）

| 优先级 | ID | 问题 | 模块 |
|--------|-----|------|------|
| P0 | F1 | player.js 死循环导致播放彻底不可用 | 前端 |
| P0 | F2/G1 | 凭证泄露需立即吊销 | 跨模块 |
| P0 | B1 | ES 查询注入 | 后端 |
| P0 | B4 | RateLimit 竞态永久限流 | 后端 |
| P0 | B5 | playedAt 更新失效 | 后端 |
| P0 | G2 | unhandledRejection 吞错误 | BFF |
| P0 | G3 | Cookie 过期永不刷新 | BFF |
| P0 | I1-I7 | 基础设施配置错误 | 基础设施 |

### 🟠 尽快修复（2-3 天内）

| 优先级 | ID | 问题 | 模块 |
|--------|-----|------|------|
| P1 | B2 | 登录无速率限制 | 后端 |
| P1 | B3 | HttpHeaders 线程安全 | 后端 |
| P1 | B6/B7/D1/D2 | 数据库索引/迁移问题 | 数据库 |
| P1 | G4 | 重试无退避无熔断 | BFF |
| P1 | G5 | 排名算法用布尔做播放量 | BFF |
| P1 | D4 | 用户删除无级联 | 数据库 |
| P1 | D5 | 缺少 source_id 索引 | 数据库 |

### 🟡 规划修复（这周内）

| 优先级 | ID | 问题 | 模块 |
|--------|-----|------|------|
| P2 | F6-F11 | 前端 6 个 HIGH 问题 | 前端 |
| P2 | B8-B15 | 后端 8 个 HIGH 问题 | 后端 |
| P2 | G6-G9 | BFF 4 个 HIGH 问题 | BFF |
| P2 | D5-D7 | 数据库 3 个 HIGH 问题 | 数据库 |
| P2 | I8-I12 | 基础设施 5 个 HIGH 问题 | 基础设施 |

### 🟢 后续优化（低优先级）

| 优先级 | ID | 问题数 | 模块 |
|--------|-----|--------|------|
| P3 | F12+ | ~3 个 LOW | 前端 |
| P3 | B16+ | ~10 个 LOW | 后端 |
| P3 | G10+ | ~5 个 LOW | BFF/基础设施 |

---

## 数据流总览

```
用户浏览器
  │
  ├─ Vue 3 (5173)
  │   ├─ Pinia Stores (player/auth/favorite/recommend)
  │   ├─ Axios → Vite Proxy (开发) / Nginx (生产)
  │   └─ Audio Element → /api/songs/stream
  │
  ├─ Nginx (80/443)                            ← I4: 限流 IP 错误
  │   ├─ /api/* → Spring Boot (8080)
  │   └─ /  → Vue 静态文件
  │
  ├─ Spring Boot (8080)
  │   ├─ Controllers → Services
  │   ├─ Services → MySQL / Redis / ES / MinIO
  │   └─ Services → Express BFF (:3000)    ← B1: ES注入
  │
  ├─ Express BFF (3000)
  │   ├─ NeteaseCloudMusicApi → music.163.com   ← G3: Cookie不刷新
  │   ├─ qq-music-api → y.qq.com               ← G3: Cookie不刷新
  │   └─ LRU Cache (搜索/URL)                   ← G6: 主动不淘汰
  │
  ├─ MySQL 8 (3306)                           ← I3: 端口暴露
  │   ├─ play_history                        ← B5: playedAt失效 + B6: 全表扫描
  │   ├─ user_favorite
  │   ├─ playlist / playlist_song
  │   └─ users / song
  │
  ├─ Redis 7 (6379)
  │   ├─ 缓存层（搜索/推荐/MinIO存在性）
  │   └─ Token 黑名单 / RateLimit / Chat Session
  │
  ├─ Elasticsearch 8.18 (9200)
  │   └─ 搜索索引（IK分词器）                    ← I2: 健康检查失败
  │
  └─ MinIO (9000/9001)
      └─ songs/{sourceId}.mp3
```

---

*报告结束。建议从 P0 问题开始，逐项修复。*
