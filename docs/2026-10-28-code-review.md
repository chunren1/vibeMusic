# 2026-10-28 近期代码全方面审查

> 范围：主仓 `0417c4f..a4c6c23`（15 个提交：网关按源拆分、每源熔断、SSRF 抽离、QQ 总开关、Spring Boot 4.1.2、前端隐藏 QQ 筛选）+ Android 仓最近 5 批审查修复与未提交的 media3 1.9.4。
> 方法：三端全量门禁 + 逐提交 diff + 关键路径手工复核 + 可复现的行为验证 + 独立安全扫描（批次 `diff-fast-20261028162336`）。
> 结论：**无 Critical/High 级安全漏洞；2 个 Medium 级可用性缺陷（熔断/空结果）、1 个 Medium 配置漂移、3 个 Low**。三端测试全绿。

## 0. 门禁基线

| 端 | 命令 | 结果 |
|---|---|---|
| 后端 | `mvn -o verify` | **828 tests / 0 fail / 1 skipped**，JaCoCo 门禁通过 |
| 网关 | `npm test` | **247 pass / 0 fail** |
| 前端 | `npx vitest run` + `vue-tsc` | **230 pass / 0 fail**，类型检查通过 |
| Android | `./gradlew testDebugUnitTest --rerun-tasks` | **1057 tests / 0 fail**（含未提交的 media3 1.9.4） |
| 安全扫描 | Fast diff | Critical 0 / High 0 / Medium 5 / Low 3（见下，已人工复核去重） |

## 1. 必读：两个可用性缺陷

### M1 · 熔断器“半开后永不重开”（`musicapi/src/source-health.js:77`）

打开条件写的是 `if (consecutiveFailures >= 3 && !openedAt) { openedAt = now; }`。`openedAt` 一旦写入不再更新，60s 后半开窗口结束，`isOpen()` 永远返回 false；此后即使上游持续故障，也不会再次熔断，每个请求继续吃满 10–12s。

**复现**（已实跑）：

```
3 次失败 → openNow=true
+61s → openAfter60s=false（预期：半开，放一个探测）
再失败一次 → openAfterFailInHalfOpen=false（预期：重新打开 60s）
consecutiveFailures=4，但状态永远 closed
```

**修法**：半开期失败要刷新 `openedAt = now`；或引入显式 `halfOpen` 状态，只放行一个探测请求（当前 `guard` 是放行全部并发）。

### M2 · 网易云“查无结果”被记成失败（`musicapi/src/sources/netease.js:138`）

`extractSongs` 把 `code !== 200` 与 `songs 为空` 都归成 `null`；4 条策略走完后统一 `failOutcome('netease','all-strategies-failed')`。冷门关键词 3 次即可把网易云熔断 60s；而且空结果会在每条策略内重试 3 次（0.5s+1s 退避），一次搜索白等约 6s。QQ 侧已按“成功码 + 空列表 = 不计账”修正，网易云未同步。

**复现**：mock 上游返回 `code:200, songs:[]`，一次 `searchNetease` 后快照为 `consecutiveFailures:1, state:'degraded', lastError:'all-strategies-failed'`。

**修法**：与 QQ 同口径——`code===200` 且 `songs` 为空时返回 `[]` 且不记账；仅非 200/异常记失败。`call()` 对空结果也不该重试 3 次。

### M3 · 网易云总预算超时后策略链继续跑、重复记账（`musicapi/src/sources/netease.js:142`）

`withTimeout(runStrategies, 12s)` 只让外层先返回并 `failOutcome` 一次；`runStrategies` 没有被取消，四策略仍会跑完并再次 `failOutcome`（或成功后 `okOutcome` 清零）。单次搜索可能记 2 次失败，阈值 3 实际 1.5 次就熔断；也可能“用户已拿到空结果，熔断却被迟到成功清掉”。

**修法**：给策略链传 `AbortSignal` 并在超时后停止；或让内层只在 `!timedOut` 时记账。

## 2. 配置与暴露面

### M4 · `MUSICAPI_QQ_ENABLED` 只在一个 compose 透传（`docker-compose.full.yml` / `.demo.yml`）

`docker-compose.yml:114` 有 `MUSICAPI_QQ_ENABLED: ${...:-true}`，`full` 与 `demo` 都没有。当前生产是手工容器 + env-file（实测 `false`、`/source-health` 显示 `qq: disabled`），所以**现网无影响**；但用这两份 compose 起栈时，QQ 会按默认 `true` 重新启用，而前端筛选已隐藏 → 用户会看到“QQ 结果但选不到 QQ”。

**修法**：两份 compose 的 musicapi environment 补同一行；CI 可加一条 `docker compose config` 断言三份一致（与 TZ 守卫同思路）。

### M5 · 四个运维端点仅凭来源 IP 放行（`musicapi/src/access.js:41` + `src/routes/ops-routes.js:24`）

`MUSICAPI_ADMIN_TOKEN` 未配置时 `canRefreshCookie` 退化为“127/10/192.168/172.16-31 即通过”。网关虽只绑 `127.0.0.1:3000`，但同机任意进程、`vibemusic-net` 内任意容器都能调用 `/internal/netease-rotate`、`/source-health`、`/cookie-status`。`/refresh-qq-cookie` 在镜像里因缺 `scripts/` 返回 501，风险较小；`rotate` 会真实切换共享 Cookie。

**修法**：强制配置管理令牌（部署 env-file 注入），或至少给写操作加 `Origin`/`Host` 校验与并发闸。

### M6 · 访问日志记录完整 query（`musicapi/server.js:66`，存量、本次未改）

`writeLog('access','INFO', req.originalUrl)` 会把 `?token=`、`?cookie=` 原样落盘。新增运维端点后，`/refresh-qq-cookie?token=...` 会把管理令牌写进 30 天日志（当前未配令牌，风险为 0，但属于“一配就漏”）。

**修法**：日志只取 `req.path` + 白名单 query 的键名；或复用 `helpers.scrubSecrets` 处理 URL。

## 3. Low

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| L1 | `sources/netease.js:49` | `result.body?.result?.songs \|\| result.body?.result?.songs` 同一表达式写两遍 | copy-paste，疑似丢了另一条路径（如 `result.songs`）；无功能影响但会误导 |
| L2 | `routes/netease-routes.js:71`、`qq-routes.js:19`、`source-routes.js:24` | 单源搜索 `limit` 直接 `parseInt` 透传、keyword 无长度上限；聚合搜索夹 100、酷狗/B站夹 50 | 网关仅内网可达，但后端可被诱导传大 limit → 上游内存/超时压力 |
| L3 | `sources/kugou.js:262` | CDN 探活全灭时回落 `candidates[0]`，不区分“私网被 SSRF 闸拒绝”与“CDN 502” | 后端 `AudioProxyService` 有 cdn-whitelist 兜底，属纵深缺口；建议被闸拒绝的候选直接丢弃 |
| L4 | `scripts/health-alert.sh:121` | QQ 已停用，但 `/cookie-status qq=false` 仍会每 30 分钟推“QQ Cookie 失效” | 告警噪音，掩盖真实故障；应读 `MUSICAPI_QQ_ENABLED` 或 `/source-health` 的 disabled 态 |
| L5 | `scripts/health-alert.sh:107` + `access.js:43` | 探活不带令牌；一旦配置 `MUSICAPI_ADMIN_TOKEN`，`/cookie-status` 对本地也返回 403，探活退化为 `ERR` 并**静默跳过** Cookie 告警 | 配令牌即失聪；脚本应读令牌或端点对本机免鉴权 |

## 4. Android 仓

- 最近 5 批是上一份审查（`docs/CODE-REVIEW-2026-10-28.md`）的修复，1057 例全绿；未发现新增回归。
- **未提交改动**：`gradle/libs.versions.toml` media3 `1.9.3 → 1.9.4`。已验证：1057 单测通过、`:app:checkDebugAarMetadata` BUILD SUCCESSFUL（compileSdk 35 兼容）。建议明确提交（commit message 写清只吃 patch）或还原，不要长期悬空。
- 遗留项与审查文档一致：`MainActivity` C5 需真机轮次、CODE-8 不改、工具链升级/i18n 待决策。

## 5. 做得对的地方（避免改错方向）

- SSRF 主闸覆盖完整：IPv4 私网/CGNAT、IPv6 ULA/链路本地/`::ffff` 映射、末尾点 FQDN、云元数据域名、逐跳 `beforeRedirect`；`allowPrivate` 仅测试使用。
- 路由拆分未丢中间件：20 条旧路由与 21 条新路由逐一比对，`searchLimiter`/`urlLimiter` 覆盖一致。
- 网易云隔离墙 + 延迟 `require` 解决了循环依赖导致的 Cookie 静默丢失，并有独立子进程冷启动回归。
- QQ 空结果与熔断口径已修正；酷狗 `privilegefilter=1`、B站过采都有实测依据。
- 网关 247 / 后端 828 / 前端 230 / Android 1057，全部通过。

## 6. 建议修复顺序

1. **M1 + M2 + M3**（同一模块，一次改完并补单测：熔断重开、空结果不计账、超时取消/单次记账）。
2. **M4**（两份 compose 补开关 + CI 一致性守卫）。
3. **L4 + L5**（告警口径，避免上线后噪音/失聪）。
4. **M6 + M5**（令牌与日志脱敏，可与运维端点加固一起做）。
5. L1/L2/L3 随手清理。

## 7. 验证命令

```bash
cd /home/user/projects/vibeMusic/musicapi && npm test          # 247
cd ../vibeMusic-backend && mvn -o verify                       # 828
cd ../vibemusic-web && npx vitest run && npm run typecheck     # 230
cd /home/user/projects/vibemusic-native && ./gradlew testDebugUnitTest --rerun-tasks  # 1057
```

安全扫描报告：`.codebuddy/security-scan/runs/diff-fast-20261028162336/security-scan-report.html`
