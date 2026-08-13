# PROJECT KNOWLEDGE BASE — vibeMusic

**Generated:** 2026-08-13
**Commit:** 4cb1ef2
**Branch:** main

## OVERVIEW
自建全栈音乐平台：网易云 + QQ 双源聚合搜索、AI Function Calling Agent、Redis→ES→API→兜底四级缓存降级、Docker 编排、Prometheus 可观测。三子项目 + 根编排：`vibemusic-web`（Vue3）、`vibeMusic-backend`（Spring Boot 4）、`musicapi`（Express BFF）。

## STRUCTURE
```
vibeMusic/
├── vibemusic-web/        # Vue3+Vite+Pinia 前端 (5173)，含 Capacitor android/ 子目录
├── vibeMusic-backend/    # Spring Boot 4 + MyBatis-Plus (8080)，详见其 AGENTS.md
├── musicapi/             # Express BFF 网关 (3000)，CommonJS 扁平单文件
├── scripts/              # 运维脚本（PowerShell/Python/JS 混合）
├── nginx/                # 生产反代 (80/443)
├── docker-compose.yml    # 14 服务编排（README 写 10、CLAUDE.md 写 7 —— 均已过时）
├── docs/                 # 各类报告 + postman 集合
└── docker-data/          # 运行时数据卷（已提交，建议勿动）
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| 后端搜索/推荐/播放核心 | `vibeMusic-backend/` | 见子 AGENTS.md |
| 前端页面/store/api | `vibemusic-web/src/` | 见子 AGENTS.md |
| BFF 网关/聚合搜索/Cookie | `musicapi/server.js` | 单文件全量 |
| 运维脚本 | `scripts/` | 见子 AGENTS.md |
| 容器编排 | `docker-compose.yml` | **14 services** |
| 生产反代/SSL | `nginx/nginx.conf` | `/api/`→backend:8080 |
| 架构说明/功能指标 | `README.md` | 与 CLAUDE.md 有数量漂移 |

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
- **TDD 强制**：Bug 修复必须 Red→Prove→Green→Refactor，禁止跳过第一步（CLAUDE.md:106）。
- **模块系统**：`musicapi/`=CommonJS（require）；`vibemusic-web/`=ESM（import）。根 package.json 仅脚本。
- **密钥**：仅存 `.env` / `musicapi/config.js`（后者已 gitignore）。禁止硬编码进 Java/yml 兜底值。
- **双端同步**：桌面页 `views/XxxView.vue` 必须有 `views/mobile/MXxxView.vue`。
- **JWT**：httpOnly Cookie（VIBE_TOKEN / VIBE_REFRESH），禁止 localStorage。
- **前端无 TypeScript / 无 eslint / 无 prettier**：纯 JS，`jsconfig.json` 仅提供 `@/*` 别名。

## ANTI-PATTERNS (THIS PROJECT)
- **`test:ci` 脚本损坏**：`mvn test -Pci` 引用不存在的 `ci` profile（pom.xml 无 profiles）。不要执行。
- **容器数量别信 README/CLAUDE.md**：实际 compose 定义 **14 services**。
- **`mvn verify`（非 `mvn test`）才触发 JaCoCo 60% 覆盖率门禁**。
- **不要新增 `t:0` QQ 参数**：当前用经典 `client_search_cp` 端点（CLAUDE.md 该备注已过时）。
- **`PlayHistory.playedAt` 无 `FieldStrategy.NEVER`**：靠 MetaObjectHandler 填充，勿按 CLAUDE.md 旧备注"修复"成 NEVER（会改坏）。
- **`StorageConfig.java` 仍硬编码 minioadmin** —— 是已知违规，新增时勿复制此模式。
- **不要相信 CLAUDE.md 的数量**：controller 实际 11 个、service 19 个、entity 7 个（非 7/14/6）。

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
CI：`.github/workflows/{test,backend-ci,frontend-ci,deploy}.yml`。

## NOTES
- 端口：web 5173 · backend 8080 · musicapi 3000 · MySQL 3306 · Redis 6379 · MinIO 9000/9001 · nginx 80/443。
- 后端配置 profile：默认 `dev`；compose 用 `docker`；`prod` 无 JWT 兜底密钥。
- 前端生产 `VITE_API_HOST=https://www.vibemusic.abrdns.com`（.env.production）。
- 本文件由 `/init-deep` 生成；子目录 AGENTS.md 覆盖各自领域细节。
