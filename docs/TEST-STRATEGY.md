# vibeMusic 测试策略（TEST-STRATEGY.md）

> 适用范围：`vibeMusic-backend`（Spring Boot 4）+ `vibemusic-web`（Vue3）+ `musicapi`（Express BFF）+ `vibemusic-native`（Android App）。
> 门禁唯一真相源：`.github/workflows/ci.yml`；发版流程唯一真相源：`scripts/deploy-backend.sh`。

## 1. 目标与范围

- 目标：播放链路（播/停/切/断网/打断/通知/恢复）零 P0 带病上线；发版可回滚、可验证。
- 在测：后端 12 controller / 18 service；Web 桌面+移动双视图；BFF 聚合搜索+Cookie；App `PlaybackService` + `PlayDiag`。
- 不测：第三方版权曲库可用性（只测我方降级行为）、MinIO/云厂商 SLA 本身。

## 2. 测试分层

| 层级 | 对象与工具 | 规模（~为约数） | 在 CI 中的位置 |
|---|---|---|---|
| L1 单测 | 后端 JUnit5+Mockito（`vibeMusic-backend/src/test`，52 个测试类）；Web Vitest（`stores/__tests__/player.test.js` 等）；App JUnit+Robolectric（`vibemusic-native/app/src/test`，~80 个测试文件，~900+ 用例，含 `PlayDiagTest` 20 个 `@Test`） | 后端 ~480+ 用例；App ~900+ 用例 | ci.yml Job1 `mvn verify` / Job2 `vitest run` |
| L2 接口 | `api-test/`（pytest）：鉴权 401、搜索聚合、推荐去重、换一批重叠率 | 31 例（live 26 + 离线 5） | ci.yml Job5 `api-test`（push 触发，起 musicapi+后端实机）+ 手工触发 |
| L3 手工+线上 | 真机（播放/来电/断网/通知/被杀）；Bugly crashreport 4.1.9 + `PlayDiag` 面包屑线上巡检 | 冒烟 20 例/版本 | 发版后 + 每日巡检 |

说明：`musicapi` 网关单测走 Job3 `node --test`（12 个文件，离线 stub，不依赖 :3000 活服务）；`k6-test.js`（50 VUs，p95 search<3000ms/stream<2000ms）为性能下一步，当前只作阈值参考、不卡门。

## 3. 准入准出

准入（开测前必须满足）：需求可测（验收标准写到用例级）+ 接口契约冻结（`{code,message,data}` 信封不变，见根 AGENTS.md）+ CI 五 job 可运行。

准出（发版前必须满足）：

- [ ] CI 5 jobs 全绿：backend（`mvn verify`，JaCoCo LINE ≥60%）+ frontend（ESLint/typecheck/build/Vitest）+ gateway（12 文件 node:test）+ api-test（pytest 31 例，vs 实机后端+musicapi）+ infra-lint（compose+nginx+otel）
- [ ] 冒烟 20 例全过（含播放链路 PB-001~PB-010）
- [ ] 无 P0/P1 遗留（P2 允许带备注遗留≤3 个）
- [ ] 发版 7 步走完：本地打包 → 备份 → 传包对 md5 → 重建 → 等 healthy → 容器内 md5+日志+warmup → 推荐冒烟（见 `scripts/deploy-backend.sh`）

## 4. 风险评估

| 等级 | 范围 | 示例 | 对策 |
|---|---|---|---|
| P0 | 播放链路 / 发版流程 / 鉴权 401 | 切歌无声、发版包不一致、放行端点 401（`SecurityConfig` 白名单漏配） | 阻塞发布；播放链路用例全自动化或真机必过 |
| P1 | 推荐去重 / 搜索聚合 | 换一批大量重复、QQ/网易源一方挂掉无降级 | 发版冒烟（重叠率阈值）+ `SongSearchFallbackTest` 类单测覆盖 |
| P2 | UI 文案 / 非核心样式 | 歌词错位、深色主题色差 | 记录遗留，不阻塞 |

## 5. 环境策略

| 环境 | 数据与隔离 | 用途 |
|---|---|---|
| 本地 | H2（`MODE=MySQL`，`schema-test.sql`，test profile 排除 Redis） | L1 单测，`mvn test -Dtest=<Class>` 单类快跑 |
| CI | docker services（Job1 JDK17+占位密钥；Job5 起 mysql/redis services + musicapi + 后端实机跑 pytest；Job4 compose config 语法校验） | 门禁，与生产配置隔离（密钥仅占位值） |
| 线上 | 只读冒烟（`X-Device-Id: deploy-smoke`，refresh=true 连刷验重叠） | 发版验证；禁止写破坏性数据，warmup 只打读接口 |

## 6. 缺陷等级与 SLA

| 等级 | 定义 | SLA | 流转 |
|---|---|---|---|
| P0 | 阻断发布：无法播放 / 发版失败 / 大面积 401 | 2h 定位，24h 修复，当天回归 | 立即阻塞发版，回滚优先于修复（云上 `/tmp/app-backup-*` 保留旧包） |
| P1 | 核心受损：去重失效 / 单源降级失效 | 1 天修复，随下个发版回归 | 记入 release report 遗留风险 |
| P2 | 体验问题：文案/样式/边缘机型 | 1 周内修复 | 可带备注上线，≤3 个 |

回归要求：P0 修复必须补单测（后端 `@DisplayName` 中文 + TDD：先复现失败测试再修，见根 AGENTS.md），App 播放类问题必须补 `PlayDiag` 面包屑字段以便下次线上可定位。
