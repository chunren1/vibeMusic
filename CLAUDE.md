# vibeMusic

> 自建全栈音乐平台 — 网易云 + QQ 音乐双源聚合搜索，高品质在线播放。

## Commands

```bash
# ── 开发 ──
npm run dev                # 同时启动 musicapi (3000) + 前端 (5173)
npm run dev:api            # 仅启动 Express 网关
npm run dev:web            # 仅启动 Vue 前端
cd vibeMusic-backend && mvn spring-boot:run   # 仅启动 Spring Boot (8080)

# ── 编译与构建 ──
cd vibeMusic-backend && mvn compile -DskipTests -q   # 后端编译（跳过测试）
npm run build              # 前端生产构建

# ── 测试 ──
npm test                   # 全量测试（后端 + 前端）
npm run test:backend       # 仅后端测试
npm run test:frontend      # 仅前端测试
cd vibeMusic-backend && mvn test -Dtest=<TestClass> -pl .   # 运行单个测试类

# ── Docker ──
npm run docker:up          # 全栈启动（构建 + up -d）
npm run docker:dev         # 仅启动基础设施（MySQL / Redis / MinIO）
npm run docker:down        # 停止所有容器
npm run docker:status      # 查看容器运行状态
npm run docker:logs        # 查看容器日志

# ── 运维 ──
npm run health             # 健康检查
npm run backup:db          # 数据库备份
npm run deploy             # 前端构建 + 全栈 Docker 部署
```

## Architecture

```
vibeMusic/
├── vibemusic-web/                 # Vue 3 前端 (端口 5173)
│   └── src/
│       ├── views/                 # 桌面端页面
│       │   └── mobile/            # 移动端页面（每个桌面页须有对应移动端版本）
│       ├── stores/                # Pinia：player / auth / favorite
│       ├── api/                   # Axios 请求封装层
│       ├── composables/           # useAudioBackground / useIsMobile
│       └── router/                # Vue Router 路由配置
├── vibeMusic-backend/             # Spring Boot 4 后端 (端口 8080)
│   └── src/main/java/com/vibemusic/
│       ├── controller/            # REST Controller（共 7 个）
│       ├── service/               # 业务 Service（共 14 个）
│       ├── entity/                # MyBatis-Plus Entity（共 6 个）
│       ├── mapper/                # MyBatis-Plus Mapper
│       ├── config/                # SecurityConfig / MyBatisPlusConfig / ...
│       └── dto/                   # 请求/响应 DTO
├── musicapi/                      # Express BFF 网关 (端口 3000)
│   ├── server.js                  # 聚合搜索 + 评分去重 + Cookie 管理
│   └── config.js                  # Cookie 配置
├── docker-compose.yml             # 7 容器编排
├── package.json                   # 工作区根脚本（concurrently 编排）
└── scripts/                       # 运维脚本（health-check / backup-db / deploy）
```

### 外部依赖

| 服务 | 端口 | 用途 |
|------|------|------|
| MySQL 8 | 3306 | 主数据库 |
| Redis 7 | 6379 | 缓存 / Session |
| Elasticsearch 8.18 | 9200 | 全文搜索 |
| MinIO | 9000/9001 | 对象存储（音频文件） |

## Conventions

### Java / Spring Boot

- **时间字段**：所有 Entity 时间字段必须加 `@TableField(insertStrategy = FieldStrategy.NEVER)`，依赖 DB `DEFAULT CURRENT_TIMESTAMP`
- **Controller 文档**：新增 Controller 必须加 `@Operation(summary = "...")` 注解
- **search() 联动**：修改 `search()` 返回类型时，必须同步检查 `RecommendService`、`AssistantController`、`getRandomSongs` 三处调用方
- **MyBatisPlusConfig**：自定义 SqlSessionFactory 时需显式注入 MetaObjectHandler 到 GlobalConfig
- **密码校验**：长度 ≥ 8 位，BCrypt `$2b$10$` 加密
- **API Key**：仅存 `.env` 文件，禁止硬编码
- **安全端点**：新增端点需在 `SecurityConfig` 中配置对应权限

### Vue 3 / Vite

- **双端同步**：每个桌面端页面必须在 `views/mobile/` 下创建对应的移动端版本
- **状态管理**：全局状态统一使用 Pinia stores（player / auth / favorite）
- **API 调用**：所有后端请求通过 `api/` 层封装，不在组件中直接调用 axios

### Git

- **Commit 格式**：`<type>: <描述>`（类型：feat / fix / perf / refactor / docs / test / chore / security）
- **分支命名**：`<type>/<short-desc>`（例：`feat/qq-search`、`fix/time-field-npe`）
- **重构前**：创建分支 + 提交当前状态
- **失败上限**：同一问题尝试 3 次仍失败 → `git reset --hard` 回滚 + 报告原因

### TypeScript / JavaScript

- Express 网关 `musicapi/` 保持 CommonJS（`require` / `module.exports`）
- 前端统一使用 ES Module（`import` / `export`）

## Constraints

### TDD（Bug 修复强制流程）

收到 Bug 修复请求时，按以下步骤执行，禁止跳过第一步：

1. **Red** — 先写一个会失败的测试用例
2. **Prove** — 运行测试，证明它确实失败并展示输出
3. **Green** — 编写最小化代码让测试通过
4. **Refactor** — 重构代码消除重复

### 修改验证

- 每完成一个子任务：运行 `cd vibeMusic-backend && mvn compile -DskipTests -q` 确认编译
- 修改涉及多模块（推荐 / 搜索 / 历史）：列出所有受影响模块并逐一验证
- Bug 修复后：必须运行关联测试，确认回归通过
- 禁止在未确认数据写入链路完整性的情况下仅修改查询/展示逻辑
- **修改 Java 文件后必须提醒用户重启后端**

### 安全

- JWT 使用 httpOnly Cookie，禁止 localStorage 存储
- 登录/注册接口需幂等防护（防重复提交）
- 所有用户输入必须后端二次校验，不信任前端验证

## Custom Commands

| 命令 | 说明 |
|------|------|
| `/plan <spec>` | 分析 Spec 文件，输出分步实施计划（含文件路径 + 风险标注 + 验证要求），不修改代码 |
| `/audit-time` | 审计所有 Entity 时间字段配置（insertStrategy / DB 默认值），输出修复方案表格 |

详见 `.claude/commands/` 目录。

## Notes

- `UserFavorite.createdAt` — 已修复 FieldStrategy.NEVER（407685e）
- `PlayHistory.playedAt` — 已修复 FieldStrategy.NEVER + MetaObjectHandler 注入（f927fb9）
- `BaseEntity.createdAt/updatedAt` — 已修复
- `MyBatisPlusConfig` — 自定义 SqlSessionFactory 需显式注入 MetaObjectHandler 到 GlobalConfig
- QQ 音乐搜索 — 需要 `t:0` 参数指定单曲类型，否则返回结果不准确
