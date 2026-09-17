# PROJECT KNOWLEDGE BASE — vibeMusic

**Generated:** 2026-08-13
**Commit:** 4cb1ef2
**Branch:** main

## OVERVIEW
自建全栈音乐平台：网易云 + QQ 双源聚合搜索、AI Function Calling Agent、Redis→API→兜底缓存降级、Docker 编排、Prometheus 可观测。三子项目 + 根编排：`vibemusic-web`（Vue3）、`vibeMusic-backend`（Spring Boot 4）、`musicapi`（Express BFF）。

<!-- DESIGN_SYSTEM_START -->
## DESIGN SYSTEM（UI 生成必读）

> **任何 AI agent 生成或修改 UI 前，必须先读取根目录 [DESIGN.md](./DESIGN.md)，严格遵循其 YAML token 与 Do's and Don'ts。**

- **设计系统**：vibeMusic Velvet Encore —— 桌面 "Spotify Encore" + 移动 "Velvet Night" 双主题，统一暗色基调，单一品牌色驱动所有交互。
- **主色**：`#31c27c`（唯一品牌绿；hover `#28a86b`；tint `rgba(49,194,124,0.12)`）。**禁止**使用遗留翡翠 `#2ee59a` 与金 `#f0b90b`（已废弃待清理）。
- **字体**：系统栈。桌面 `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`；移动 `'HarmonyOS Sans', 'PingFang SC', -apple-system, sans-serif`。正文 14px/400，标题 18px/600，字重限 400–600。
- **间距**：4px 基数刻度 — xs 4 · sm 8 · md 12 · lg 16 · xl 20（移动页边距 16px，`--m-space-page`）。
- **圆角**：xs 4 · sm 8 · md 12 · lg 16 · xl 18 · pill 999px（chips/tags 用 pill，头像/关闭钮用圆形）。
- **深度**：靠背景色阶分层（#121212→#282828），阴影仅 `--shadow-1/--shadow-2`，禁霓虹 glow；移动端毛玻璃 blur(16px) saturate(1.2)。
<!-- DESIGN_SYSTEM_END -->

## STRUCTURE
```
vibeMusic/
├── vibemusic-web/        # Vue3+Vite+Pinia 前端 (5173)；桌面 + /m 移动双视图
├── vibeMusic-backend/    # Spring Boot 4 + MyBatis-Plus (8080)，详见其 AGENTS.md
├── musicapi/             # Express BFF 网关 (3000)，源码在 src/（server.js 仅装配）
├── scripts/              # 运维脚本（Python/JS/sh 混合）
├── nginx/                # 生产反代 (80/443)
├── docker-compose.yml    # 12 服务编排（full.yml 为含 OTel 的可选变体）
├── docs/                 # 各类报告 + postman 集合
└── docker-data/          # 运行时数据卷（已提交，建议勿动）
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| 后端搜索/推荐/播放核心 | `vibeMusic-backend/` | 见子 AGENTS.md |
| 前端页面/store/api | `vibemusic-web/src/` | 见子 AGENTS.md |
| BFF 网关/聚合搜索/Cookie | `musicapi/src/`（路由 routes.js） | 配置注入见 `src/config-loader.js` |
| 运维脚本 | `scripts/` | 见子 AGENTS.md |
| 容器编排 | `docker-compose.yml` | **12 services** |
| 生产反代/SSL | `nginx/nginx.conf` | `/api/`→backend:8080 |
| 架构说明/功能指标 | `README.md` | 数字以 README 为准 |

## CODE MAP
> 基于 codegraph 实测调用链（仅核心跨模块符号）。

| Symbol | Type | Location | Role |
|--------|------|----------|------|
| `SongSearchService.search()` | service | backend/service | 返回 `SearchResult`，3 个外部调用方 + getRandomSongs 内部调用 |
| `MyBatisPlusConfig` | config | backend/config | 自定义 SqlSessionFactory，必须注入 MetaObjectHandler |
| `SecurityConfig` | config | backend/config | 端点权限白名单 |
| `JwtUtils` | util | backend/common/utils | 双 Token（access 15min / refresh 7d） |
| `player store` | store | web/src/stores/player.js | 全局单例 Audio + `window.*` 兼容 |

## CONVENTIONS（跨项目，只列偏离标准处）
- **规模硬约束：单文件 ≤250 纯 LOC**（非空非注释行）；超出即拆分（按屏/按职责拆，见后端 AGENTS.md 的拆分先例）。
- **禁 `any`（Java 禁裸泛型）/禁空 catch**：catch 必须打日志或给可观察信号。
- **测试纪律**：禁止删除或弱化测试冒充通过；修复类改动必须落在生产调用路径上（改完 grep 调用方确认）。
- **UI 约束**：保留访客模式与深色主题；UI 改动前读 `DESIGN.md`。
- **API 契约信封 `{code,message,data}`** 不许改动；新增接口按 App 需求开（App 优先，Web 兼容不断）。
- **TDD 强制**：Bug 修复必须 Red→Prove→Green→Refactor，禁止跳过第一步（CLAUDE.md:106）。
- **模块系统**：`musicapi/`=CommonJS（require）；`vibemusic-web/`=ESM（import）。根 package.json 仅脚本。
- **密钥**：仅存 `.env` / `musicapi/config.js`（后者已 gitignore）。禁止硬编码进 Java/yml 兜底值。
- **JWT**：httpOnly Cookie（VIBE_TOKEN / VIBE_REFRESH），禁止 localStorage。
- **前端无 TypeScript / 无 eslint / 无 prettier**：纯 JS，`jsconfig.json` 仅提供 `@/*` 别名。

## ANTI-PATTERNS (THIS PROJECT)
- **容器数量别信旧文档**：实际 compose 定义 **12 services**（`docker-compose.full.yml` 为含 OTel 的可选变体）。
- **`mvn verify`（非 `mvn test`）才触发 JaCoCo 60% 覆盖率门禁**。
- **不要新增 `t:0` QQ 参数**：当前用经典 `client_search_cp` 端点（CLAUDE.md 该备注已过时）。
- **`PlayHistory.playedAt` 无 `FieldStrategy.NEVER`**：靠 MetaObjectHandler 填充，勿按 CLAUDE.md 旧备注"修复"成 NEVER（会改坏）。
- **`StorageConfig.java` 仍硬编码 minioadmin** —— 是已知违规，新增时勿复制此模式。
- **同一逻辑常有孪生副本**（历史最重 bug 根因）：白名单/降级链/搜索分支改动前先找第二份实现（如 `getPlayInfo` vs `getPlayUrl`）。
- **日志与凭据**：网关 `writeLog('cookie'|'api')` 经 `STREAM_BY_CATEGORY` 落盘；禁止把 Cookie 原文写日志（用 `scrubCookieValues`）。

## UNIQUE STYLES
- 中文注释与 `@DisplayName`（后端测试、代码内注释多用中文）。
- Redis Key 版本化前缀：`song:search:v4:`、`recommend:v3:` 等。
- 时间字段依赖 DB `DEFAULT CURRENT_TIMESTAMP`。

## COMMANDS
```bash
npm run dev            # musicapi(3000) + web(5173)；backend 需另起
npm run dev:full       # backend(8080)+api(3000)+web(5173) 三端
npm run dev:backend    # cd vibeMusic-backend && mvnw spring-boot:run
npm run build          # 前端生产构建
npm test               # 后端 mvn test + 前端 vitest run（不含 musicapi）
npm run test:backend   # cd vibeMusic-backend && mvn test
npm run test:frontend  # cd vibemusic-web && vitest run
npm run docker:up      # 全栈 up -d --build
npm run docker:dev     # 仅基础设施 mysql redis minio
npm run health         # PowerShell 健康检查（Windows only）
npm run backup:db      # mysqldump 备份（Windows only）
```
CI：`.github/workflows/ci.yml`（三并行测试 + infra-lint）、`deploy.yml`（仅构建产物）。

## NOTES
- 端口：web 5173 · backend 8080 · musicapi 3000 · MySQL 3306 · Redis 6379 · MinIO 9000/9001 · nginx 80/443（compose 内除 80/443 外均绑 127.0.0.1）。
- 后端配置 profile：默认 `dev`；compose 用 `docker`；`prod` 无 JWT 兜底密钥。
- 前端生产 `VITE_API_HOST` 留空 = 相对路径 `/api`（随访问域名，换域名无需改代码）。
- 本文件由 `/init-deep` 生成；子目录 AGENTS.md 覆盖各自领域细节。
