# 2026-10-30 脚本与协程取消修复：审查材料

> 审查对象：本轮两笔修复，跨两个仓。
> 主仓：`f22b1c3`（`create-db-users.sh` / `init-users.sh` / `deploy-backend.md`）
> Android 仓：`4fa0b9e`（`ApiActionRunner` 取消传播 + 回归用例 + 文档计数）
> 基线：主仓 `198ff3f`，Android 仓 `2a985af`。
> 本轮**没有改生产**，只改仓库脚本/代码/文档；不需要回滚线上。

---

## 1. 为什么要改（上一轮审查的遗留）

上一轮审查（`docs/2026-10-28-code-review.md` 之后的一批）留了四条：

1. `create-db-users.sh` 启动 mysql 时固定 `docker compose up -d mysql`，不带 compose 文件；在 full/demo 栈里会起错栈。
2. `init-users.sh` 只转义单引号，口令含反斜杠时 MySQL 字面值会变。
3. `ApiActionRunner` 用 `catch (Exception)` 把 `CancellationException` 也吞了，页面销毁时可能弹错误。
4. 迁移先于切流量，缺少 expand/contract 的书面约束。

本轮逐条修掉，没有夹带其它改动。

## 2. 主仓 `f22b1c3`

### 2.1 `scripts/create-db-users.sh`

| 改动 | 说明 |
|---|---|
| 新增 `-m` / `COMPOSE_FILE` | 指定起 mysql 用的 compose 文件；默认仍是 `docker-compose.yml` |
| 容器已存在但停止 → `docker start` | 不再用 compose 重建/改名；避免 full/demo 栈被默认文件覆盖 |
| 容器不存在 → `docker compose -f "$COMPOSE_FILE" up -d mysql` | 显式带上文件，起错栈的问题消失 |
| `.env` 取值去成对引号 | 避免把 `"` / `'` 当成口令的一部分 |
| 健康等待抽到分支外 | 无论 start 还是 compose up，都统一等 healthy（上限 120s） |

**审查重点**：`docker start` 路径是否保留了原容器的网络/端口/健康检查；`-m` 传入不存在的文件时是否 fail-fast（`set -e` + compose 自身报错）。

### 2.2 `vibeMusic-backend/sql/init-users.sh`

```bash
sql_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g"; }
case "${DB_PASSWORD}${FLYWAY_PASSWORD}" in
  *$'\n'*) echo "[02-users] 口令不能包含换行" >&2; exit 1 ;;
esac
```

- 反斜杠先加倍，再处理单引号；MySQL 默认开启反斜杠转义，`\\` 才能得到原字符。
- 换行会截断 SQL，直接拒绝。
- 仍不处理 `\0`/控制字符；口令生成器应限制可见 ASCII。

**审查重点**：转义顺序（先反斜杠后引号）是否正确；`case` 拼接两个变量是否会漏检（任一含换行即命中）。

### 2.3 `docs/deploy-backend.md`

新增「数据库迁移的兼容性约束（expand / contract）」：

- 4/9 迁移先于新 backend 启动，旧版本仍在服务；
- 加列/加表/回填可以，删列/改类型必须拆两个发布；
- 破坏性变更走维护窗口；
- 迁移成功后再用旧镜像回滚，可能不匹配新 schema。

**审查重点**：这条约束是否与 `scripts/deploy-backend.sh` 的实际顺序一致（4/9 迁移 → 5/9 起新容器 → 切流量）。

## 3. Android 仓 `4fa0b9e`

### 3.1 `ApiActionRunner.kt`

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: NetworkAuthException) {
    ...
```

- `rememberCoroutineScope` 在页面销毁时以 `CancellationException` 取消协程；此前会被 `catch (Exception)` 当成业务失败，弹提示/走鉴权回调。
- 现在原样抛出，结构化并发不被破坏；`finally` 仍执行（复位 busy/loading 是预期行为）。

### 3.2 `ApiActionRunnerTest.kt`

新增 `cancellationIsNotReportedAsFailure`：

- 抛 `CancellationException`；
- 断言 `onAuthLost` / `onError` 都没被调用；
- 断言 `finally` 仍执行一次。

### 3.3 文档计数

`README.md` / `AGENTS.md` 的验收门从 1074 改为 **1075**（新增 1 例）。

## 4. 验证证据（已实跑）

| 项 | 命令 | 结果 |
|---|---|---|
| 主仓脚本语法 | `bash -n scripts/create-db-users.sh`、`bash -n vibeMusic-backend/sql/init-users.sh` | 均通过 |
| 转义行为 | `printf "%s" "p'a\\b" \| sed ...` | 输出 `p''a\\b`，符合 MySQL 字面值预期 |
| Android 定向测试 | `./gradlew testDebugUnitTest --tests "*ApiActionRunnerTest"` | BUILD SUCCESSFUL |
| Android 全量 | `./gradlew testDebugUnitTest --rerun-tasks` | **1075 tests / 0 failures / 0 errors** |
| 主仓测试 | 未改 Java，无需重跑；上一轮基线 829 全绿 | — |

**没有执行**：`create-db-users.sh` 实际建号（会动本地库），只做了语法与转义验证。

## 5. 回滚

| 仓 | 回滚方式 |
|---|---|
| 主仓 | `git revert f22b1c3`（纯脚本/文档，无运行时影响） |
| Android | `git revert 4fa0b9e`（恢复旧的 catch 行为；1075 → 1074） |

本轮未部署，线上 backend/musicapi 不受影响。

## 6. 已知边界（审查时请确认口径）

1. **`create-db-users.sh` 仍假设容器有 healthcheck**；无 HEALTHCHECK 的容器会等满 120s 后报错。当前 mysql 镜像有，够用。
2. **`get()` 去引号是启发式**：如果口令本身以引号结尾且未成对，会被误删；生成口令时避免首尾引号即可。
3. **`init-users.sh` 不处理 `\0` 与控制字符**；`.env` 不支持多行，换行已拒绝。
4. **CI required check 未配置**：需要 GitHub 仓库设置，代码改不了；建议把 infra-lint 设为必需。
5. **HSTS `includeSubDomains` 未验证全部子域**：需确认 `ai/monitor` 等均 HTTPS；本轮未改 nginx。
6. **Android 取消测试是同步抛 `CancellationException`**，没有覆盖“真实 scope cancel 时正在挂起”的路径；语义等价，但如需更强证据可加 `awaitCancellation` + `cancelAndJoin` 用例。

## 7. 审查清单（按风险）

1. `scripts/create-db-users.sh`：`docker start` 分支、`-m` 路径、健康等待是否在所有分支都执行。
2. `vibeMusic-backend/sql/init-users.sh`：转义顺序、换行拒绝、`ALTER USER` 幂等。
3. `ApiActionRunner.kt`：`CancellationException` 是否在 `Exception` 之前；`finally` 行为是否符合预期。
4. `ApiActionRunnerTest.kt`：断言是否真的能捕获“吞取消”的回归（把 catch 改回去应红）。
5. `docs/deploy-backend.md`：约束是否与部署脚本 4/9–5/9 顺序一致。
6. 文档计数 1075 是否与当前测试报告一致。

## 8. 审查结论

（待审查 Agent 填写）
