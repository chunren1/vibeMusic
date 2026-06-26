# 🎵 vibeMusic

> **全栈音乐平台** — 独立完成从浏览器到数据库的全链路开发，覆盖 Vue 3 + Spring Boot 4 + Express BFF + Docker 七容器编排。
> 支持网易云 + QQ 音乐双源聚合搜索，自建三级缓存体系和推荐引擎，83 条自动化测试，GitHub Actions CI/CD。

[![CI](https://github.com/chunren1/vibeMusic/actions/workflows/test.yml/badge.svg)](https://github.com/chunren1/vibeMusic/actions/workflows/test.yml)
[![Tests](https://img.shields.io/badge/tests-83%20passed-brightgreen)](https://github.com/chunren1/vibeMusic)
[![JaCoCo](https://img.shields.io/badge/coverage-JaCoCo%20enabled-brightgreen)](https://github.com/chunren1/vibeMusic)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0-6DB33F)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D)](https://vuejs.org/)
[![Docker](https://img.shields.io/badge/Docker-7_Containers-2496ED)](https://www.docker.com/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## 📸 项目演示

| 首页 | 歌曲播放 | 歌单管理 |
|------|----------|----------|
| ![首页](image/首页.png) | ![歌曲播放](image/歌曲播放页.png) | ![歌单](image/歌单页.png) |

| 我的收藏 | 播放历史 | 个人中心 |
|----------|----------|----------|
| ![收藏](image/收藏页.png) | ![历史](image/播放历史页.png) | ![个人](image/个人页.png) |

<p align="center">
  <strong>🤖 AI 音乐助手 — DeepSeek V4 驱动，智能推荐、对话交互、关键词搜索</strong><br/>
  <img src="image/ai助手页.png" alt="AI助手" width="70%" />
</p>

---

## 🏗️ 技术架构

```
┌──────────────────────────────────────────────────────────┐
│                  Nginx (port 80) 统一入口                  │
│         静态资源 serve + /api/* 反向代理 + SPA fallback     │
│          Gzip 压缩 + upstream keepalive 连接池              │
└────────────┬─────────────────────────┬────────────────────┘
             │                         │
   dist/ 静态资源                /api/* 代理
             │                         │
             ▼                         ▼
┌─────────────────────┐   ┌────────────────────────────────┐
│   Vue 3 + Vite       │   │   Spring Boot 4 + Java 17       │
│   Composition API    │   │   MyBatis-Plus + HikariCP       │
│   Pinia 状态管理      │   │   JWT httpOnly Cookie 认证       │
│   Vitest 41 条测试    │   │   Knife4j OpenAPI 3.0           │
│   (dev:5173)         │   │   (localhost:8080)              │
└─────────────────────┘   └──────────┬─────────────────────┘
                                     │
                   ┌─────────────────┴────────────────┐
                   ▼                                  ▼
         ┌──────────────────┐            ┌──────────────────────────┐
         │   musicapi BFF    │            │   数据层 (Docker)          │
         │   Express.js 网关  │            │                          │
         │   (port 3000)     │            │  MySQL 8.0  · 主数据库     │
         │                   │            │  Redis 7    · 三级缓存     │
         │  网易云搜索/URL     │            │  ES 8.18    · IK 分词搜索  │
         │  QQ 搜索/URL       │            │  MinIO      · 歌曲离线缓存  │
         │  搜索评分聚合       │            │                          │
         │  Cookie 统一管理    │            └──────────────────────────┘
         └──────────────────┘
```

### 技术选型及理由

| 技术 | 为什么选它 |
|------|-----------|
| **Vue 3 + Composition API** | `setup script` 语法简洁；组合式函数复用（`useAudioBackground` / `useClickOutside` / `useToast`） |
| **Vite** | esbuild 预编译秒级 HMR；`rollupOptions` 手动 chunk 拆分精准控制 vendor |
| **Pinia** | Vue 3 官方状态管理，模块化 Store，`setup store` 模式，`$subscribe` 持久化播放队列 |
| **Spring Boot 4** | 最新一代框架；自动配置减少样板代码；AOT 编译支持 |
| **MyBatis-Plus** | Lambda 链式查询类型安全；MetaObjectHandler 自动填充时间字段；逻辑删除开箱即用 |
| **HikariCP** | 字节码级轻量连接池，Spring Boot 默认，性能优于 Druid |
| **Flyway** | 数据库 schema 版本化管理，V1→V4 自动迁移，确保环境一致性 |
| **Redis** | 搜索缓存(TTL 1h) + 用户缓存(5min) + 幂等去重(5min) + AI 限流(滑动窗口) |
| **Elasticsearch 8.18** | IK 中文分词 + 高亮 + 平台聚合统计，毫秒级搜索 |
| **MinIO / RustFS** | S3 兼容对象存储，歌曲离线缓存直读，不依赖第三方 URL |
| **Docker Compose** | 7 容器 + `healthcheck` + 启动依赖链，`docker compose up -d` 一键全栈 |
| **Nginx** | 反向代理 + Gzip + upstream keepalive 32 连接复用，`proxy_buffering off` 音频流直通 |

---

## 🔍 核心功能深度解析

### 一、双源音乐搜索体系

项目的技术核心。搭建了一套**完整的多级搜索引擎**。

```
用户输入 "周杰伦 晴天"
  → Controller 接收 → Service 校验关键词
  → 并行搜索（CompletableFuture + 线程池，3s 超时）:
      ├── L1: Redis 缓存命中？→ 直接返回 (~2ms)
      ├── L2: ES IK 分词匹配 (~15ms) → 命中回写 Redis
      ├── L3: musicapi 双源实时搜索 (~800ms) → 命中回写 Redis + 索引到 ES
      └── L4: 空结果兜底 (<1ms) — 全部源不可用时优雅降级
  → 返回统一 SearchResult { list, total, hasMore, source }
```

**搜索降级链（4 策略）：**

| 优先级 | 策略 | 延迟 | 适用场景 |
|--------|------|------|----------|
| L1 | Redis 缓存 | ~2ms | 热门搜索词直接命中 |
| L2 | Elasticsearch | ~15ms | 已索引歌曲秒级检索 |
| L3 | musicapi 实时 | ~800ms | 新歌/冷门歌曲首次搜索 |
| L4 | 空结果兜底 | <1ms | 所有源不可用时优雅降级 |

**双源评分算法（BFF 网关层）：**

```
最终分 = (网易云分 × 1.0 + QQ分 × 0.9 + 双源共有 bonus × 0.3) / 2.2
```
- QQ 搜索指定 `t=0`（单曲类型），避免返回歌单/专辑干扰结果
- 热度值归一化：不同平台的热度指标映射到统一 [0, 1] 区间
- URL 缓存与搜索缓存分离：URL 时效短 (~20min)，搜索结果 TTL 长 (1h)

**ES 索引设计：**
- IK 分词器 `ik_max_word`，"周杰伦的晴天" → `[周杰伦, 晴天]`
- 索引字段：`name` / `artist` / `album` / `source`（netease / qq）
- 聚合查询：按平台分组统计，前端展示 "网易云 15 首 / QQ 12 首"
- 高亮：匹配关键词高亮返回，前端展示搜索片段

**关键实现细节：**
- 网易云 + QQ 搜索通过 `CompletableFuture.supplyAsync()` 并行执行，`get(3s, TimeUnit)` 超时控制
- 前端搜索竞态修复：`AbortController` 自动取消旧请求，防止快速输入导致结果错乱
- 缓存键前缀版本化 `song:search:v2:` — v1 只存单平台数据，v2 合并为双源结果

---

### 二、BFF 网关层设计

**为什么加这一层？**

| 原因 | 说明 |
|------|------|
| **Cookie 隔离** | 网易云/QQ VIP Cookie 不能暴露到浏览器，网关层统一管理 |
| **接口适配** | 两个平台 API 签名和响应格式完全不同，网关做统一 DTO 转换 |
| **搜索聚合** | 双源评分、去重、排序在网关完成，后端拿统一数据 |

**网关路由：**
```
Express.js (port 3000)
├── /search           → 双源并行搜索 + 评分聚合（核心端点）
├── /cloudsearch      → 网易云单平台精准搜索
├── /song/url/netease → 网易云播放 URL（VIP 音质，有效期 ~20min）
├── /song/url/qq      → QQ 音乐播放 URL
├── /song/lyric       → 歌词代理
└── /health           → 健康检查
```

---

### 三、个性化推荐引擎（v3）

```
算法流程：
  1. 随机种子 (4 首) → 保证多样性，避免信息茧房
  2. 歌手扩展 (3 位 × 10 首) → 基于用户播放历史的歌手权重 Top-3
  3. 补充 → 结果不足时随机补全
  4. Redis 缓存 → key: recommend:v3:{userId}, TTL 30min
  5. 离线标记 → 批量 DB 查询替代 N 次 MinIO API 调用
```

**设计演进：**
- v1：纯随机 → 用户觉得"跟我无关"
- v2：仅歌手权重 → 推荐范围太窄，全是同一歌手
- v3：随机打底 + 歌手扩展 → 既有相关性又有惊喜感

---

### 四、AI 音乐助手

| 特性 | 实现细节 |
|------|----------|
| **模型** | DeepSeek V4 Flash，HTTP 同步调用 |
| **意图识别** | System prompt 引导："想听开心的歌" → AI 提取关键词 "轻快 流行" → 调用搜索 API |
| **关键词提取** | 3 级降级：提取音乐关键词 → 原始消息全文搜索 → "热门歌曲" 兜底 |
| **限流保护** | Redis 滑动窗口（INCR + TTL），每用户每分钟最多 10 次 |
| **停止生成** | 前端 `AbortController`，用户随时中断 AI 回复 |

---

### 五、音频播放与六级 SLA

```
音质降级链：
  LOCAL (RustFS 本地文件, ~0ms)
  → HIRES (Hi-Res 无损, 96kHz/24bit)
  → EXHIGH (320kbps)
  → HIGHER (192kbps)
  → STANDARD (128kbps)
  → FALLBACK (搜索同名歌曲兜底)
```

- 每级设 8s DEADLINE 超时，`CompletableFuture` 链式组合返回第一个可用 URL
- 音频流代理：Nginx `proxy_buffering off` + `Range` 透传支持 seek
- Connection reset 自动重试：重新获取新 URL 再试一次

---

## ⚡ 性能优化清单

| 类别 | 优化项 | 效果 |
|------|--------|------|
| 🔍 搜索 | Redis 缓存热门词 | 命中时 ~800ms → ~2ms |
| 🔍 搜索 | ES IK 分词替代 Like 模糊查询 | 全表扫 → 倒排索引 O(log n) |
| 🔍 搜索 | 双源线程池并行 + 3s 超时 | 串行 1.6s → 并行 ~800ms |
| 🎵 播放 | RustFS 离线缓存直读 | 省去每次第三方 API 请求 500ms+ |
| 🎵 播放 | 降级 8s DEADLINE | 单级超时不阻塞后续降级 |
| 🏦 数据库 | 冗余索引清理 + 精准索引 | `idx_name(50)` / `idx_artist(100)` |
| 🏦 数据库 | 播放历史概率清理 (10次触1次) | DELETE 开销降 90% |
| 🏦 数据库 | 批量删除 `delete(in ids)` | 替代 for 循环 N 条 SQL |
| 🏦 数据库 | `exists()` 预检 + DB 唯一索引 | 歌单去重双重保障 |
| 🔐 认证 | JWT 用户 Redis 缓存 (TTL 5min) | 每次请求省 1 次 DB 查询，命中率 ~99% |
| 🎯 推荐 | `markOfflineStatus()` 批量 DB 查询 | 替代 N 次 MinIO statObject |
| 🌐 HTTP | Apache HttpClient5 连接池 (100/20) | 连接复用 + keep-alive |
| 🌐 HTTP | `HttpHeaders` 静态常量复用 | 减少对象分配 |
| 🌐 HTTP | Nginx upstream keepalive 32 | 消除每次反向代理 TCP 握手 |
| 📦 前端 | Vite chunk 拆分 (vue-core/pinia/axios) | 独立并行下载 |
| 📦 前端 | Nginx Gzip (comp_level=4) | 传输体积减少 ~70% |
| ⚡ I/O | 下载 I/O 与 DB 事务分离 | HTTP 下载 30s 不占 DB 连接 |
| ⚡ I/O | Stream buffer 8KB → 64KB | 吞吐量提升 8x |

---

## 🛡️ 安全体系

| 层级 | 措施 | 防御目标 |
|------|------|----------|
| **传输** | JWT 存 httpOnly + SameSite Cookie，前端 JS 不可读写 | XSS 窃取 Token |
| **认证** | BCrypt 加密存储密码 | 数据库泄露后密码不可逆 |
| **授权** | `JwtAuthenticationFilter` 拦截 `/api/**` + `@Auth` 注解 | 未授权访问 |
| **输入** | 参数长度限制（用户名 30 字、AI 消息 2000 字） | 超长输入 OOM |
| **输入** | `@Valid` + `@NotBlank` + `username.trim()` | 空参数 / 空格绕过 |
| **幂等** | `X-Request-Id` + Redis 5min 去重 | 网络重放导致重复操作 |
| **CORS** | 白名单严格限制（localhost + 自定义域名），禁用 `*` | 跨域攻击 |
| **容器** | 所有密码 `${ENV_VAR:-default}` 外置 `.env` | 凭据硬编码泄露 |
| **网关** | VIP Cookie 仅在 BFF 层使用，不透传前端 | Cookie 泄露 |
| **配置** | Actuator 仅暴露 `/health`，隐藏敏感端点 | 信息泄露 |
| **日志** | 全局异常 `traceId` 返回，不暴露堆栈 | 内部实现泄露 |
| **协议** | 前端 + 后端 API 响应 URL 统一升级 `http→https` | 混合内容拦截 |

---

## 🧪 质量保障

```
全链路 83 条自动化测试
├── 后端 42 条 (JUnit 5 + MockMvc + H2 内存数据库)
│   ├── Service 单元测试  27 条  → 注册/登录/收藏/歌单/播放/清理
│   └── Controller 集成测试  15 条  → 认证/搜索/播放/流/歌词
│
├── 前端 41 条 (Vitest + jsdom)
│   ├── PlayerStore  21 条  → 队列操作/切歌/播放模式/持久化/去重
│   └── PlayerBar    20 条  → 组件渲染/面板展开收起/播放控制/音量
│
└── CI/CD (GitHub Actions)
    └── push / PR → 后端 + 前端全量测试 → JaCoCo 覆盖率报告
```

```bash
npm test                 # 83 条全量测试
npm run test:backend     # 仅后端 (mvn test)
npm run test:frontend    # 仅前端 (vitest run)
```

覆盖率报告：`vibeMusic-backend/target/site/jacoco/index.html`

---

## 🐳 DevOps & 运维体系

### Docker 一键部署

```bash
npm run docker:up        # 生产模式：7 容器全栈启动 (Nginx + Spring Boot + Express + MySQL + Redis + ES + MinIO)
npm run docker:dev       # 开发模式：仅启动中间件，本地开发前端/后端
```

**容器编排设计：**
- `depends_on` + `healthcheck` 确保启动顺序：MySQL → Redis/ES → MinIO → Spring Boot → Nginx
- 所有服务日志轮转 `max-size=10m, max-file=3` 防磁盘写满
- `restart: unless-stopped` 异常退出自动恢复

### 常用命令

| 命令 | 功能 |
|------|------|
| `npm run dev` | 并发启动 musicapi + 前端 |
| `npm run docker:up` | 全栈生产部署 |
| `npm run docker:dev` | 仅启动基础设施 |
| `npm test` | 全量 83 条测试 |
| `npm run health` | 容器健康检查 |
| `npm run backup:db` | 数据库备份 (mysqldump + gzip) |
| `k6 run scripts/k6-test.js` | K6 压力测试 (50 VU 并发) |
| `python scripts/health_check.py` | Python 健康检查脚本 |

### CI/CD 流水线

| 流水线 | 触发条件 | 流程 |
|--------|----------|------|
| `test.yml` | push / PR → main | 后端 JUnit + 前端 Vitest → 上传测试 & 覆盖率报告 |
| `deploy.yml` | 手动 / tag(v*) | 构建 JAR + dist → Docker 镜像 → 部署 → 健康检查 |

### 备份恢复

```bash
npm run backup:db           # → docker-data/backups/vibemusic_YYYYMMDD_HHmmss.sql.gz
# 恢复
gunzip vibemusic_*.sql.gz
docker exec -i vibemusic-mysql mysql -uroot -p123456 vibemusic < vibemusic_*.sql
```

---

## 📖 API 文档

启动后端后，Knife4j OpenAPI 3.0 文档可直接在浏览器测试所有接口：

> **[http://localhost:8080/doc.html](http://localhost:8080/doc.html)**

### 核心端点

| 模块 | 端点 | 说明 |
|------|------|------|
| Auth | `/api/auth/register` `login` `me` `logout` `change-password` | 注册/登录/JWT 认证 |
| Songs | `/api/songs/search` `play` `stream` `lyric` `random` `banner` `history` | 搜索/播放/歌词/推荐 |
| Recommend | `/api/recommend/personalized` | 个性化推荐引擎 (v3) |
| Favorites | `/api/favorites/toggle` `list` `ids` `remove-batch` | 收藏管理（含批量） |
| Playlists | `/api/playlists/list` `create` `songs` `add-song` `remove-song` `delete` | 歌单 CRUD |
| Download | `/api/download/{sourceId}` `check/{sourceId}` `file/{sourceId}` | RustFS 离线缓存 |
| AI | `/api/assistant/chat` | DeepSeek V4 音乐助手（限流保护） |
| Monitor | `/api/monitor/cache-stats` `/api/songs/es-health` | 监控端点 |

完整端点 + 在线测试 → [Postman Collection](docs/vibeMusic.postman_collection.json)

---

## 🤖 AI 工程化

本项目内置完整的 AI 开发规范，支持 Claude Code 和 CodeBuddy 双工具：

| 文件 | 用途 |
|------|------|
| `CLAUDE.md` | Claude Code 规则：TDD 铁律 + 安全约束 + 工作流 |
| `AI-GUIDE.md` | 非技术用户使用指南 |
| `.claude/commands/` | 自定义命令：`/plan`（规划模式）、`/audit-time`（时间字段审计） |
| `.codebuddy/` | CodeBuddy Agent 定义 + Rules（编码/安全/测试/工作流）+ Skills（代码生成模板） |

---

## 🚀 快速开始

### 环境要求

- Java 17+ / Node.js 20+ / Docker Desktop

### 步骤

```bash
# 1. 安装依赖
npm install
npm run install:all       # musicapi + 前端

# 2. 启动中间件（MySQL + Redis + ES + MinIO + Nginx）
npm run docker:dev

# 3. 启动后端（另一终端）
cd vibeMusic-backend
mvn spring-boot:run       # → http://localhost:8080
                           # API 文档: http://localhost:8080/doc.html

# 4. 启动前端
npm run dev               # concurrently 同时启动 musicapi(3000) + 前端(5173)

# 5. 生产部署
npm run build
npm run docker:up         # 访问 http://localhost
```

默认管理员：`admin` / `123456`

---

## 📂 项目结构

```
vibeMusic/
├── vibemusic-web/            # Vue 3 前端 (dev:5173)
│   ├── src/views/            # 页面组件（首页/搜索/播放/歌单/收藏/历史/AI）
│   ├── src/stores/           # Pinia 状态管理（auth/player/favorites/playlists）
│   ├── src/composables/      # 组合式函数（useAudio/useClickOutside/useToast）
│   └── src/__tests__/        # 前端单元测试（Vitest 41 条）
│
├── vibeMusic-backend/        # Spring Boot 后端 (8080)
│   ├── controller/           # REST 控制器（Auth/Songs/Playlists/Favorites/Recommend/AI）
│   ├── service/              # 业务逻辑 + 搜索/推荐/播放/下载
│   ├── config/               # Security / MyBatis-Plus / Redis / ES / CORS 配置
│   ├── db/migration/         # Flyway 数据库迁移脚本 (V1→V4)
│   └── src/test/             # JUnit 5 + MockMvc 测试（42 条）
│
├── musicapi/                 # Express BFF 网关 (3000)
│   ├── server.js             # 路由注册 + 搜索聚合核心逻辑
│   └── config.js             # Cookie 凭证管理（.gitignore）
│
├── docker-compose.yml        # 7 容器编排（Nginx/Spring/Express/MySQL/Redis/ES/MinIO）
├── nginx/nginx.conf          # Nginx 统一入口配置
├── scripts/                  # 运维脚本集（健康检查/备份/K6 压力测试）
├── docs/                     # Postman Collection + 截图
├── CHANGELOG.md              # 详细迭代记录（5 轮 >100 项改进）
└── .github/workflows/        # CI/CD（自动测试 + 部署）
```

---

## 📝 迭代记录

详细的优化历史和技术决策见 **[CHANGELOG.md](CHANGELOG.md)**，涵盖 5 轮迭代超过 100 项改进，包括：

- 搜索体系重构（双源聚合 + ES 索引 + 四级降级链）
- 性能优化（Redis 缓存体系 + I/O 事务拆分 + 连接池调优）
- 安全加固（JWT httpOnly + BCrypt + 输入校验 + 幂等防护）
- AI 工程化（Claude Code / CodeBuddy 双工具 Harness）

---

## 📊 项目规模

| 维度 | 数据 |
|------|------|
| 后端代码 | ~8,000 行 Java (Spring Boot + MyBatis-Plus) |
| 前端代码 | ~12,000 行 Vue 3 + JavaScript |
| 网关代码 | ~800 行 Express.js |
| 数据库表 | 6 张核心表 (Flyway V1→V4 版本管理) |
| API 端点 | 30+ REST 端点 + 15+ 网关端点 |
| 前端页面 | 11 个桌面端 + 12 个移动端路由 |
| 自动化测试 | 83 条 (后端 42 + 前端 41) |
| 提交记录 | 212 次提交，5 轮迭代 |

---

## 🗄️ 数据库设计

```
users ───────── 用户表
  │ id, username, password(BCrypt), nickname, avatar, bg_image
  │
  ├── playlist ───── 歌单表
  │     │ id, user_id → users.id, name, description
  │     │
  │     └── playlist_song ── 歌单歌曲关联 (多对多)
  │           playlist_id + source_id 联合唯一
  │
  ├── user_favorite ── 收藏表
  │     user_id + source_id 联合唯一, 冗余 song_name/artist/cover_url
  │
  └── play_history ─── 播放历史 (上限 300 条，定时清理)
        user_id + played_at 复合索引，连续同歌 UPDATE 去重
```

| 表 | 核心索引 | 说明 |
|------|----------|------|
| `users` | `uk_username` | BCrypt 密码存储 |
| `song` | `uk_source_id`, `idx_name`, `idx_artist` | 歌曲缓存 + ES 同步源 |
| `playlist` | `idx_user_created` | 用户歌单排序查询 |
| `playlist_song` | `uk_pl_song` (playlist_id + source_id) | 防重复添加 |
| `user_favorite` | `uk_user_song`, `idx_user_fav_created` | 收藏去重 + 排序 |
| `play_history` | `idx_user_played`, `idx_played_at` | 历史列表 + 定时清理 |

---

## 🗺️ 前端路由

```
桌面端 (/)                        需要登录
├── /              → 首页         ✗
├── /search        → 搜索         ✗
├── /playlists     → 我的歌单      ✓
├── /playlist/:id  → 歌单详情      ✗
├── /likes         → 我的收藏      ✓
├── /recent        → 最近播放      ✓
├── /profile       → 个人中心      ✗
├── /chat          → AI 助手      ✗
└── /login         → 登录         ✗

移动端 (/m)       自动检测设备跳转
├── /m             → 首页 (Shell)
├── /m/search      → 搜索
├── /m/playlists   → 歌单
├── /m/likes       → 收藏
├── /m/recent      → 历史
├── /m/profile     → 个人
├── /m/player      → 播放器
├── /m/chat        → AI 对话
└── ...
```

> 路由守卫：`beforeEach` 自动从 httpOnly Cookie 恢复会话，需登录页面未认证→弹登录框；移动端/桌面端按 User-Agent 自动分流。

---

## 已知问题

- QQ 搜索需 `t:0` 参数指定单曲类型

## License

MIT
