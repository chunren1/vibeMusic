# 2026-10-26 接手续做：代码审查材料

> 审查对象：opencode 额度耗尽后，CodeBuddy 接手的 P0 收尾 + P1 低风险项。
> 基线：`348be57`（opencode 最后一笔）→ 当前 `401d264`。
> 一次性 diff：`git diff 348be57..401d264`（主仓，10 个提交）。
> 生产变更均已执行，**回滚点写在每节**；审查时优先看“生产动作”与“风险点”。

---

## 1. 接手时的事实基线（先核对，避免审错前提）

- opencode 已完成 P0 的 1/2/3/4/5/8/10 项，但 **P0-1 的代码改动未提交**（`vite.config.js` 留在工作区）。
- 审计文档里标“未执行”的 P0-5（MySQL）实际已是 **8.4.13**；P0-7（日志轮转）实际已生效（`20m×3`）。
- 真正剩余：P0-1 代码提交、P0-4（专用库账号）、P0-6（补丁重启）、P0-9（blog.service）。

## 2. 提交清单（按时间序）

| 提交 | 类型 | 改了什么 | 为什么 | 验证 |
|---|---|---|---|---|
| `a2ef23d` | 代码 | `vite.config.js`：`sourcemap:'hidden'→false`；新增审计文档 | `hidden` 只去掉注释，仍 emit 25 个 `.map`（1.6MB，含 `.vue` 原文）且公网可访问 | 重建 dist **2.8M→1.2M、0 个 .map**；229 tests、`vue-tsc` 通过 |
| `38e6128` | 文档 | 记录 P0-4、P0-9 执行结果 | 留痕 | — |
| `c9054c5` | 文档 | 记录 P0-6 重启 | 留痕 | — |
| `a25d36a` | 文档 | 删 DB 口令文件绝对路径、修 P0 表格 | 扫描 Medium：公开仓库侦察信息 | 路径 0 残留 |
| `f433009` | 代码/配置 | musicapi Dockerfile 内置 `tzdata/Asia/Shanghai`（561B）+ `ENV TZ`；`nginx/nginx.conf` 头部标注影子配置 | alpine 无 tzdata，`TZ` 静默失效；仓库 nginx 配置易被误当生产 | 镜像内 `date` = **CST**；3 个 YAML/镜像构建通过 |
| `1f34017` | 代码 | 删 `micrometer-registry-prometheus` 依赖与 `/actuator/prometheus` 暴露；prod 只留 health | 审计实测：依赖在、端点开、**无任何采集方** | `mvn -o verify` **828 全绿**；部署后端点 **404**、health **200**、搜索 200 |
| `6a243ce` | 文档 | P1 执行记录 | 留痕 | — |
| `0c9dfbb` | CI | 新增 `.github/dependabot.yml`（6 个生态） | P1-14 第一半 | YAML 解析通过 |
| `84ac4cb` | 配置 | 删两份 `prometheus.yml` 的 backend job；告警规则头注明休眠；文档去备份路径 | 扫描 Low：端点删了但采集端还在，启动监控栈会 `up=0` → BackendDown 永久误报 | 3 个 YAML 解析通过 |
| `401d264` | 配置/文档 | 告警头补齐 **17 条**失效规则说明；文档删 4 个 `/root` 路径、部署目录、内部端口 | 扫描 2 Low 的剩余部分 | 敏感串 0 残留；YAML 通过 |

## 3. 生产动作（不在 git，审查重点）

| 动作 | 具体 | 回滚 |
|---|---|---|
| **MySQL 专用账号** | 新建 `vibemusic@'%'`，授权 `vibemusic.*` 的 SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/INDEX/DROP/REFERENCES（Flyway 需要 DDL），无 GRANT OPTION；backend 以 `DB_USERNAME=vibemusic` 蓝绿发布；随后 `DROP USER 'root'@'%'` | 账号口令在服务器 600 文件（路径见 RUNBOOK）；回滚镜像 `rollback-20261026-165854`；如需恢复 root 远程，`CREATE USER 'root'@'%'` 并授权 |
| **blog.service** | `systemctl start` + `enable`（此前 failed 1 个多月） | `systemctl stop blog` |
| **nginx `$request_time`** | 宿主机 `/etc/nginx/nginx.conf` 新增 `main_timed` 格式，全局 access_log 切换；备份在服务器（路径见 RUNBOOK） | 还原备份 + `nginx -t && systemctl reload nginx` |
| **系统补丁重启** | `reboot`（libc6 补丁） | 无自动回滚；四容器 `unless-stopped`，重启后全部 healthy |
| **无 Prometheus 版后端** | 蓝绿部署 `1f34017`；预检 available 350MB < 400MB，用 `--force --health-timeout 420` | `rollback-20261026-173816` |
| **时区版 musicapi** | 镜像 `f433009`，重建容器（保留 `--network-alias musicapi`） | 上一版容器 `vibemusic-musicapi-prev-20261026173112` |

## 4. 审查清单（按风险排序）

### 4.1 必看：安全边界
1. **`vibemusic-web/vite.config.js`**：确认 `sourcemap:false` 在生产构建生效；确认没有其它路径把 `.map` 带进 dist（PWA `globIgnores`、compression 插件）。验收：`npx vite build && find dist -name '*.map' | wc -l` → 0，`du -sh dist` → ~1.2M。
2. **MySQL 权限**：`SHOW GRANTS FOR 'vibemusic'@'%'` 应只有 `vibemusic.*` 的 DML/DDL；确认 `root@'%'` 已不存在；确认备份脚本仍走 socket + `root@localhost`（部署目录下的 `backup-mysql.sh`（路径见 RUNBOOK）），未受影响。
3. **`musicapi/tzdata/Asia/Shanghai`**：561B，TZif2 fat，sha256 应与宿主 `/usr/share/zoneinfo/Asia/Shanghai` 一致；`strings` 只应含 `TZif2`/`CST-8`。属于 public domain 数据，注意别误当二进制夹带。

### 4.2 必看：配置一致性
4. **Prometheus 下线是否彻底**：`grep -rn "actuator/prometheus\|vibemusic-backend" docker-data/prometheus monitoring` 应只剩注释与告警表达式；两份 `prometheus.yml` 不应再有 backend job。**已知残留**：Grafana 看板 JSON 仍引用该 job（未改，避免动大 JSON），面板会显示无数据，已在 `alert-rules.yml` 头部注明。
5. **`alert-rules.yml`**：17 条依赖后端指标的规则（BackendDown/JVM/Hikari/HTTP 时延/成功率等）在无数据时不会触发；注释是否覆盖完整。
6. **nginx 日志格式**：`rt=` 是请求总耗时，`urt=` 是 upstream 耗时；静态资源 `urt=-` 属正常。注意这是**全局** access_log 格式，博客 vhost 也会变化。

### 4.3 常规：CI 与文档
7. **`dependabot.yml`**：6 个生态、weekly/monthly、limit 3/2；只在 GitHub 生效，Gitee 镜像不会收到 PR。
8. **审计文档脱敏**：确认当前文件无 `/root` 绝对路径、无部署目录绝对路径、无内部端口、无口令值；**历史提交里仍有旧路径**（`a2ef23d..c9054c5`），但无口令值，未做历史重写。
9. **`nginx/nginx.conf` 头注释**：是否足以阻止“改错生产配置”。

## 5. 验证命令（可直接跑）

```bash
# 前端：构建产物无 map
cd /home/user/projects/vibeMusic/vibemusic-web
npx vite build && find dist -name '*.map' | wc -l && du -sh dist
npx vitest run --reporter=dot        # 229
npm run typecheck

# 后端：全量门禁
cd ../vibeMusic-backend && mvn -o verify   # 828 tests

# 配置语法
cd .. && python3 -c "import yaml;[yaml.safe_load(open(p)) for p in ['docker-data/prometheus/prometheus.yml','monitoring/prometheus.yml','docker-data/prometheus/alert-rules.yml']]"

# 线上只读核验
ssh root@<host> 'docker ps --format "{{.Names}} {{.Image}} {{.Status}}"; systemctl is-active blog; ls /var/run/reboot-required 2>/dev/null || echo no_reboot'
ssh root@<host> 'docker exec vibemusic-backend printenv DB_USERNAME'   # vibemusic
curl -4 -s -o /dev/null -w '%{http_code}\n' 'https://vibe.cyk666.top/api/songs/search?keyword=%E6%99%B4%E5%A4%A9&page=1&size=1'  # 200
```

## 6. 已知偏差与接受的风险（审查时请确认口径）

1. **`--force` 绕过内存门禁**：blog 恢复后 available 仅 350MB，蓝绿预检拒绝；本次强制部署，healthy 66s。根因是 blog 常驻但**无任何路由指向 3100**。建议改成按需启动，否则下次发版仍需 `--force`。
2. **Grafana 看板未改**：`docker-data/grafana` 与 `monitoring/provisioning` 仍引用 backend job；监控栈当前未部署，无即时影响。
3. **MySQL 口令未轮换**：扫描曾暴露口令文件**路径**（非值），文件 600、值从未入库，判断无需轮换；如审查认为要轮换，改 `ALTER USER` + 重新部署 env 即可。
4. **文档历史**：旧备份路径/端口仍在 `a2ef23d..c9054c5` 历史中；无口令、无公网 IP，未做 `filter-repo`。
5. **tzdata 来源**：直接复制宿主 Ubuntu 的 zoneinfo 文件（fat 561B），不是从 Alpine 包安装（当前网络 `apk` 源 TLS 不可达）。如要求可追溯，可改为 CI 从 IANA 下载并校验 sha256。
6. **P1 未做项**：12（告警口径）、14 后半（trivy/npm audit 基线）、16（恢复演练）、17（MainActivity 拆分）、19（/opt 入 git）。不在本次范围。

## 7. 给审查 Agent 的产出要求

- 每条结论给 `文件:行号` + 证据（命令输出/报告路径）。
- 分级：High（安全/数据）立即修；Medium 当日修；Low 记入本节。
- 审查完把结论追加到本文档第 8 节并提交。

## 8. 审查结论

> 2026-10-29 补：§6 里两条「已知偏差」已处理，结论与证据如下（均为云端实测，不是看报告）。

### 8.1 `--force` 绕过内存门禁 → 已自然解除（blog 停用 + 纳管）

**根因确认**（2026-10-29 实测）：

- `systemctl is-enabled blog` = `disabled`、`is-active` = `failed`（2026-10-27 10:29:16 停，`status=143`）；
- `grep -rn 3100 /etc/nginx/` 为空 —— **nginx 没有任何路由指向 3100**，博客常驻纯属白吃内存；
- 蓝绿 dry-run 内存闸门：`available 642~676MB ≥ 400MB`，**不再需要 `--force`**。

**纳管**：`/etc/systemd/system/blog.service` 逐字节纳入仓库
`deploy/production/systemd/blog.service`（md5 `5929b6cc825c72688edc7528d0f4d198`，405 B），
并在 `deploy/production/README.md` 注明「保持 disabled，勿再启用」。

**遗留判断**：博客功能未下线（`/opt/blog`、`/opt/blog-srv` 都还在），只是不再常驻。
真要恢复，必须先给它一条 nginx 路由 + 重新算内存账（1608MB 机器上 backend 768M + blog ~300M 会顶到闸门）。

**2026-10-29 收尾：已彻底移除，不再"只是停用"**。用户确认该项目废弃，于是删掉
`/etc/systemd/system/blog.service`（含 `daemon-reload`）、`/opt/blog`（601M，源码+SQLite+9 篇 md）、
`/opt/blog-srv`（58M，Next.js standalone 产物），云上共回收 659M 磁盘；`3100` 确认无监听。
删前两份归档在本地：`~/backups/blog-nextjs-server-20261029.tar.gz`（云上源码+data，1.1M）、
`~/backups/blog-nextjs-20261029/blog-src.tar.gz`（本机同名项目，1.3M）。
**注**：本机 `~/projects/blog` 那个 Next.js 项目从来没有 GitHub 仓库（无 remote），14 个提交只存在于本机，
已随删除一并归档。

### 8.2 MySQL 迁移/运行账号拆分 → 已完成（Flyway 走 migrator，运行账号 DML-only）

**做了什么**

1. 新建 `vibemusic_migrator@'%'`：`SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER ON vibemusic.*`。
2. 运行账号 `vibemusic@'%'` 保留 `SELECT,INSERT,UPDATE,DELETE`，其余 `REVOKE`（建号 SQL 见 `.env.example` 注释）。
3. 注入方式：`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`（Boot 4.1.2 relaxed binding →
   `spring.flyway.user/password`；Flyway 从主 DataSource 派生 url，只覆盖凭据，**不需要** `spring.flyway.url`）。
4. 云上 `/opt/vibeMusic/.env`：新增 `FLYWAY_USER` / `FLYWAY_PASSWORD`，并把漂移的
   `DB_USERNAME=root` 改回 `vibemusic`（`root@'%'` 早已不存在，`docker compose up` 会直接连不上）。
5. `docker-compose.demo.yml` backend 段：`DB_USERNAME: ${DB_USERNAME:-vibemusic}` + 两个 `SPRING_FLYWAY_*`
   （改前备份 `.env.bak-20261029-121506` / `docker-compose.demo.yml.bak-20261029-121506`）。
6. 蓝绿部署：`--apply --skip-build --image 41bf3f5 --set-env SPRING_FLYWAY_USER=... --set-env SPRING_FLYWAY_PASSWORD=...`，
   **healthy 63s、零停机**（旧容器一直服务到 upstream 切完）。

**证据（关键，逐条可复现）**

| 断言 | 证据 |
|---|---|
| Flyway 用 migrator 连库 | `performance_schema.accounts`：`vibemusic_migrator@172.19.0.6` `TOTAL_CONNECTIONS=1`；同 IP 的 `vibemusic` 是新容器（236 次），旧容器是 `172.19.0.4`（696 次）。**该账号此前从未被使用过，唯一一次连接就发生在这次启动** |
| Flyway 真的跑完校验 | 后端日志：`Database: jdbc:mysql://mysql:3306/vibemusic (MySQL 8.4)` / `Successfully validated 11 migrations (00:00.249s)` / `Schema 'vibemusic' is up to date` |
| 运行账号不能再改表 | 用容器里的 `DB_PASSWORD` 以 `vibemusic` 登录：`CREATE TABLE vibemusic.zz_priv_probe` → `ERROR 1142 (42000): CREATE command denied to user 'vibemusic'@'127.0.0.1'`（无残留表） |
| 运行账号 DML 正常 | 同一账号 `SELECT COUNT(*) FROM song` 成功、`UPDATE song SET name=name WHERE 1=0` 成功（`affected=0`） |
| 线上没受影响 | 公网 `GET /api/songs/search?...` = **200**；容器 `healthy`、`restarts=0`、近 8 分钟日志无 error/exception；`information_schema` 行数与改前一致（`play_history 159` / `playlist 31` / `playlist_song 987` / `users 5`） |
| 两份 compose 的 DB 段已一致 | 云上 `docker-compose.demo.yml` md5 `a892d8f3…`，仓库同步了同样的两处改动 |

**取证方法备注（下次别再用 1 秒轮询 `processlist`）**：Flyway 的 validate 只有 249ms，
1 秒采样几乎必然漏掉。`performance_schema.accounts.TOTAL_CONNECTIONS` 是累计值、不随连接关闭消失，
才是确定性的证据；`events_statements_history` 在连接关闭后也不可靠（本次实测为空）。

**回滚方式**

- 配置：`cp -a /opt/vibeMusic/.env.bak-20261029-121506 /opt/vibeMusic/.env`（compose 同理）。
- 容器：`docker tag vibemusic-backend:rollback-<ts> vibemusic-backend:latest` 后重跑部署脚本；
  或直接改回 upstream 端口（脚本打印过 `sed -i 's#server 127.0.0.1:8082;#...8080;#'` 那条命令）。
- 权限：`GRANT CREATE, DROP, REFERENCES, INDEX, ALTER ON vibemusic.* TO 'vibemusic'@'%';`

**遗留**

- 两份 `docker-compose.demo.yml` 仍有其它漂移（改后云上 175 行 / 仓库 188 行：`TZ`、`mysql:8.0` vs `8.4`、
  fail-fast 默认值等），本次只改 backend 的 DB/Flyway 段，未整文件覆盖。
- 部署脚本已提示：**以后别再跑 `docker compose up -d backend`**（容器名/端口冲突，且会重建容器）。
- `vibemusic_migrator` 口令目前同时存在于云上 `.env`（600）与新容器 env 里，未进仓库；
  轮换时改 `ALTER USER` + 改 `.env` + 重新部署（`--set-env`）三处。

### 8.3 外部复审 M1/M2 → 已修（2026-10-29）

外部复审（范围 `0bc20fc`/`6cc5e24` + Android `774b35f`/`13a4451`/`a10cf97`/`2a985af`）提了两条 Medium，均已处理。

#### M1 · 「最小权限收益」表述不成立 → 改口径，不拆 init job

**问题**：`.env.example` 原文写「应用被攻破也改不了表结构」。这不成立 ——
`FLYWAY_PASSWORD` 通过 `SPRING_FLYWAY_PASSWORD` 注入**后端同一个容器**，
RCE 后 `cat /proc/self/environ` 就能拿到 migrator 凭证，照样 DDL。

**已改**（表述层，不改架构）：

| 位置 | 改动 |
|---|---|
| `.env.example` | 收益改为三条可验证的：① 防误操作（运行连接执行 DDL → `ERROR 1142`）② 收窄 SQLi 的 DDL 面（注入点复用运行账号连接池）③ 迁移失败/锁不占运行账号；并显式写「**不是**被攻破也改不了」+ 指向本节 |
| `docker-compose.demo.yml` backend 段 | 同上，补两行「收益边界」注释 |
| 云上 `docker-compose.demo.yml` | 同步（md5 `dabb1d4a…`，与仓库一致；未重启任何容器） |

**为什么暂不拆独立 init job**：把 Flyway 拆成一次性容器（`flyway` 镜像 + `depends_on` + 迁移完退出）确实能
让后端容器彻底不持有 DDL 凭证。但代价是：三份 compose 各加一个 service、本地 `up` 的启动顺序多一层依赖、
迁移失败的错误要在另一个容器里看；而本项目 DDL 面很窄（11 个迁移，追加式，历史文件禁止改）。
按「先要能用的结果」原则，先改口径留记录；要做时以本节为设计起点。

#### M2 · 另两份 compose 仍是 root 账号 → 三份统一 + 建号脚本入库

**问题**：`docker-compose.yml:171` / `docker-compose.full.yml:176` 还是 `DB_USERNAME: root` 且无
`SPRING_FLYWAY_*`；`mysql-backup` 用 `mysqldump -h mysql -u root`（TCP）。账号拆分只落在 demo 一份，
与「QQ 开关只改一份 compose」是同一类漂移。

**事实修正（实测，不照抄复审结论）**：`mysql:8.4` 官方镜像**首次初始化默认会建 `root@'%'`** ——
本地空卷起栈时 `-u root` 其实能连上。真实风险是另两条：
1. **复用已 DROP `root@'%'` 的库**（生产就是这种状态）时，`docker-compose.yml` / `.full.yml` 直接 1045；
2. 本地栈用 root 与生产权限模型不一致 —— 本地跑得通、线上才炸（这正是漂移的危害）。

**已改**：

| 文件 | 改动 |
|---|---|
| `vibeMusic-backend/sql/init-users.sh` | **新增**。initdb 时建三个号：`vibemusic`(DML) / `vibemusic_migrator`(DML+DDL) / `vibemusic_backup`(只读备份，`BACKUP_PASSWORD` 未传则跳过)。幂等：`CREATE USER IF NOT EXISTS` + `ALTER USER`，可反复跑（兼做口令同步） |
| 三份 compose 的 `mysql` | 挂载 `02-users.sh`；传 `DB_PASSWORD`/`FLYWAY_PASSWORD`（`.full.yml`/`.yml` 另传 `BACKUP_PASSWORD`） |
| `docker-compose.yml` / `.full.yml` backend | `DB_USERNAME: ${DB_USERNAME:-vibemusic}` + `SPRING_FLYWAY_USER/PASSWORD` |
| 同上 `mysql-backup` | 改 `-u vibemusic_backup` + `--no-tablespaces`，口令走 `MYSQL_PWD` 环境变量（不上命令行） |
| `scripts/create-db-users.sh` | **新增**。存量卷（`docker-data/mysql/data` 已存在，initdb 不再执行）一条命令补建账号：`./scripts/create-db-users.sh` |

**验证（本机 mysql:8.4 + mysql:8.0 客户端容器，跨容器 TCP，2026-10-29）**：

| 验证项 | 命令/现象 |
|---|---|
| initdb 自动建号 | 空卷起栈日志 `running /docker-entrypoint-initdb.d/02-users.sh` → `mysql.user` 出现 3 个账号 |
| 运行账号不能 DDL | `CREATE TABLE vibemusic.zz_probe` → `ERROR 1142 (42000): CREATE command denied to user 'vibemusic'@'172.19.0.3'` |
| 运行账号 DML 正常 | `SELECT count(*) FROM users` = 1；`INSERT`/`DELETE` 成功 |
| 迁移账号能 DDL | `CREATE TABLE` / `ALTER TABLE ... ADD COLUMN` / `DROP TABLE` 全成功，探针表残留 0 |
| 备份账号能 dump | `mysqldump --single-transaction --routines --triggers --no-tablespaces` → rc=0、stderr 空、2029 B |
| **备份真能还原** | 解压灌进探针库：表数 6 = 原库 6、`users` 行数 1 = 1，探针库已 drop |
| 缺 `--no-tablespaces` 会怎样 | 非 root 无 `PROCESS` 权限 → stderr 报 `Access denied ... for this operation when trying to dump tablespaces`（rc 仍 0，dump 不完整）—— 所以这项不能省 |
| 存量卷补建 | 只有表没有账号的容器跑 `./scripts/create-db-users.sh` → 三个账号出现，`SHOW GRANTS` 与预期一致 |
| 口令轮换 | 改 `DB_PASSWORD` 重跑脚本 → 新口令可登录、旧口令 `ERROR 1045` |
| 三份 compose 可解析 | `docker compose --env-file <dummy> config --quiet` 三份均退出 0；渲染后 backend `DB_USERNAME=vibemusic`、`SPRING_FLYWAY_USER=vibemusic_migrator`，mysql-backup dump 命令为 `-u vibemusic_backup ... --no-tablespaces` |

**没动的地方（有意）**：`scripts/backup-mysql.sh`（云上 cron 03:30 用，走容器内 socket + root）
不动 —— 宿主机 root 操作自己的库不属于「应用最小权限」范畴；改它需要新增账号依赖，收益为零。

**残留（未改，记一笔）**：`.env.docker` 是**未纳入 git** 的旧模板（7 月 19 日，内容仍是 `DB_USERNAME=root`），
云上那份还是 `777` 权限。它不是有效配置源（`.env.example` 才是），但谁要是 `cp .env.docker .env` 就会退回 root。
建议后续二选一：删掉，或改成指向 `.env.example` 的一行说明。本次不动是因为它不在版本管理里，改了也不会同步到任何地方。
