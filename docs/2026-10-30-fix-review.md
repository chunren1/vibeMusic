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
case "${DB_PASSWORD}${FLYWAY_PASSWORD}${BACKUP_PASSWORD:-}" in
  *$'\n'*|*$'\r'*) echo "[02-users] 口令不能包含换行/回车" >&2; exit 1 ;;
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
2. **`get()` 已改为只剥成对引号**：单侧引号（如 `abc'`）会保留；仍是启发式，`.env` 里请使用成对引号或不加引号。
3. **`init-users.sh` 已拒绝换行/回车（含 BACKUP_PASSWORD）**；`\0` 与控制字符仍不处理。
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

> 审查日期 2026-11-01。审查对象为 `f22b1c3`（主仓）与 `4fa0b9e`（Android 仓），**不是**当前 HEAD 的全量审查。
> 结论：**Approve（可合并）**，无 P0/P1，不需要额外回归；本轮只改了一处事实性注释。

### 8.1 审查清单逐条核对

| # | 审查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | `create-db-users.sh`：`docker start` 分支、`-m` 路径、健康等待是否在所有分支执行 | **通过** | 健康等待在 `if/elif` **之外**（`scripts/create-db-users.sh:46-53`），start 与 compose up 两条路都会等；`-m` 显式传给 `docker compose -f`（`:40`），文件不存在时 compose 自身非 0，配合 `set -euo pipefail`（`:16`）直接退出，不会静默起错栈 |
| 2 | `init-users.sh`：转义顺序 | **通过** | `sql_escape` 先 `s/\\/\\\\/g` 再 `s/'/''/g`（`vibeMusic-backend/sql/init-users.sh:37`）。顺序正确：若先处理引号，后加的 `\` 会被再次加倍 |
| 3 | `init-users.sh`：`case` 拼接是否漏检换行 | **通过** | `:41-43` 三个变量拼成一个串再匹配 `*$'\n'*|*$'\r'*`，任一含换行即命中；`BACKUP_PASSWORD` 用 `${BACKUP_PASSWORD:-}` 参与拼接，未提供时不误报 |
| 4 | `init-users.sh`：`ALTER USER` 幂等 | **通过** | `CREATE USER IF NOT EXISTS` + `ALTER USER`（`:50-51`、`:55-56`、`:67-68`）可反复执行；`FLUSH PRIVILEGES`（`:72`）在 GRANT 之后属冗余，但无害 |
| 5 | `ApiActionRunner.kt`：`CancellationException` 是否在 `Exception` 之前 | **通过** | `ApiActionRunner.kt:44-47` 的 `catch (e: CancellationException) { throw e }` 位于 `:52` 的 `catch (e: Exception)` 之前；`finally`（`:54-56`）仍执行，busy/loading 复位语义保留 |
| 6 | `ApiActionRunnerTest.kt`：用例能否真的抓到「吞取消」回归 | **通过** | `ApiActionRunnerTest.kt:117-127`。`CancellationException` 是 `Exception` 子类，把 `:44-47` 删掉后它会落进通用分支 → `onError` 被调用 → `assertNull("取消不应弹业务错误", rec.error)` 失败。**该用例是有效回归门，不是摆设** |
| 7 | `deploy-backend.md` 的 expand/contract 是否与脚本顺序一致 | **通过** | 脚本 `step "4/9 数据库迁移…"`（`scripts/deploy-backend.sh:642`）→ `5/9 起新容器`（`:722`）→ `7/9 切流量`（`:913`）；文档 `docs/deploy-backend.md:117-124` 描述的正是「迁移先于切流量、schema 需同时服务两个版本」 |
| 8 | 文档计数 1075 是否与实测一致 | **通过** | `README.md:18,67` 与 `AGENTS.md:14,19` 均为 1075；测试目录实测 **93 个 `.kt`**（`find app/src/test -name '*.kt' \| wc -l`），与「93 个测试文件」一致 |

### 8.2 本轮新发现（按严重度）

| 级别 | 位置 | 问题 | 处理 |
|---|---|---|---|
| **P2** | `vibeMusic-backend/sql/init-users.sh:22-25`（原） | 注释写「把 Flyway 拆成独立 init job **未实施**」，与 `2c13c54` 已实施的事实冲突；照此注释会误判「backend 仍持有 DDL 凭证」，进而低估/高估安全边界 | **本轮已修**：改成「DDL 凭证只出现在一次性迁移容器里」+ 诚实补一句「migrator 口令仍在云端 `.env`，防的是应用层 RCE 不是主机失守」 |
| P3 | `init-users.sh:48-70` | 只 `GRANT` 不 `REVOKE`：若历史账号权限比现在大，重复执行本脚本**不会收紧** | 未改。云端实测已是正确权限集（见 8.3），属未来风险；真要收紧应单独写 REVOKE 并对齐三份 compose |
| P3 | `MainActivity.kt:1660`、`ApiActionRunner.kt:10` | 两处注释写「19 处」，实际 `api.launch(` 调用点为 **17 处**（`ApiActionRunnerTest.kt:15` 写的 17 才对） | 未改。跨仓（native）改动，随下次 native 提交一并带上 |
| P3 | `vibemusic-native/AGENTS.md:28` | 写「60 个 `.kt` / 91 个测试」，实测 **62 / 93** | 未改。该文件人手维护且明令禁止 agent 自动改写，需用户确认 |
| P3 | `scripts/create-db-users.sh:70` | `$(get BACKUP_PASSWORD \|\| true)` 里的 `\|\| true` 无实际作用：`get` 用 `sed -n`，缺键也返回 0 | 未改。误导性写法，不影响行为 |
| P3 | `init-users.sh:78` | `case "$0"` 想判「是否由 initdb 触发」，但被 entrypoint `source` 时 `$0` 是 entrypoint 路径，分支不会命中 | 未改。只影响一行提示语是否打印 |

### 8.3 本轮额外取证（与修复本身无关，但支撑结论）

| 项 | 命令 | 结果 |
|---|---|---|
| 云端账号权限是否真如脚本所述 | `docker exec vibemusic-mysql … SHOW GRANTS` | `vibemusic`：仅 `SELECT,INSERT,UPDATE,DELETE`；`vibemusic_migrator`：上述 + `CREATE,DROP,REFERENCES,INDEX,ALTER,EXECUTE,CREATE ROUTINE,ALTER ROUTINE`；无 `vibemusic_backup`（demo 栈不跑备份容器）→ **与脚本一致** |
| 云上 demo compose 是否含 flyway-init | `md5sum` + `docker compose config --quiet` | 已同步为仓库版（`0994d5b5…`，244 行），`config` rc=0，服务清单含 `flyway-init`；**运行中 4 个容器未动** |
| 部署一致性对账 | `scripts/reconcile-cloud.sh` | 11/22 一致、11 项预期差异、**0 真漂移** |

### 8.4 未修项与理由

1. **跨仓注释（17 vs 19）**：属 native 仓，改它要单独提交并跑 1075 例，与本轮「运维收尾」范围不符。
2. **`AGENTS.md` 数字（60/91 → 62/93）**：该文件头明确「禁止让 agent 自动改写本文件」，须用户确认。
3. **REVOKE / `|| true` / `case $0`**：三处都不改变当前行为，改它们会扩大本次改动面；已在此记录，下次触碰对应文件时一并处理。

### 8.5 结论

- 无 P0/P1，**不需要额外回归**。
- 主仓本轮仅一处注释改动，`bash -n vibeMusic-backend/sql/init-users.sh` 通过；未改 Java，无需重跑 829 例。
- Android 仓本轮未改动。
