# 数据库恢复演练（Restore Drill）

> 适用对象：`scripts/restore-mysql.sh` + 生产备份 `scripts/backup-mysql.sh` 产出的 dump。
> 一句话：**恢复只能进一次性容器或空临时库，永远不要灌回生产实例。**
> 相关：`docs/DEPLOY.md`、`docker-compose.yml`、`scripts/backup-mysql.sh`（只读依赖，不要改）。

---

## 1. 为什么要剥离那三类语句

`scripts/backup-mysql.sh` 的导出命令是：

```sh
docker exec -e MYSQL_PWD="$PW" vibemusic-mysql sh -c \
  'exec mysqldump -uroot --single-transaction --quick --routines --triggers --databases vibemusic' | gzip > "$OUT"
```

`--databases vibemusic` 会让 dump 里出现：

```sql
CREATE DATABASE /*!32312 IF NOT EXISTS*/ `vibemusic` /*!40100 DEFAULT CHARACTER SET utf8mb4 ... */;
USE `vibemusic`;
```

而 `mysqldump` **默认带 `--add-drop-table`**，所以每张表前面还有一行：

```sql
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (...);
```

把这份 dump 原样灌回一个已经在跑的实例，等于对 dump 里每张表执行
**「删表 → 重建 → 回灌」**。后果是：

| 影响对象 | 后果 |
|---|---|
| 备份之后产生的业务数据（新注册用户、收藏、播放历史、歌单） | **全部删除，不可逆** |
| `flyway_schema_history` | 被删回备份时刻，配合 `baseline-on-migrate` + `validate-on-migrate:false`，迁移可能被重跑 |
| 表上的触发器/存储过程 | 随表被删，重建前存在短暂空窗 |

三条语句的处理方式（`restore-mysql.sh` 的清洗管道，纯流式，**不改动用户的 dump 文件**）：

| 语句 | 处理 | 原因 |
|---|---|---|
| `CREATE DATABASE ...` | **剥离** | 防止在任何实例上创建/触碰 `vibemusic`；库名由 `--target-db` 统一注入 |
| `USE ...` | **剥离** | 防止后续语句落到 `vibemusic`；改写为 `USE <target>` |
| `DROP TABLE IF EXISTS ...`（含 `DROP VIEW` / `DROP DATABASE`） | **剥离** | 这是唯一真正会毁数据的语句，必须一条不留 |

清洗后由脚本自己注入安全头部：

```sql
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;
CREATE DATABASE IF NOT EXISTS `<target-db>` DEFAULT CHARACTER SET utf8mb4;
USE `<target-db>`;
```

副作用（**这是好事**）：第二次往同一个非空库恢复时，会以
`ERROR 1050 (42S01): Table 'xxx' already exists` 失败退出，而**不会**删掉任何已有数据。

---

## 2. 三道守卫

| # | 守卫 | 触发条件 | 退出码 |
|---|---|---|---|
| 1 | 目标库名 | `--target-db vibemusic`（或 `mysql`/`sys`/`information_schema`/`performance_schema`）→ 直接拒绝 | 2 |
| 2 | 残留扫描 | 清洗后的 SQL 里**仍能扫到** `DROP TABLE/VIEW/DATABASE`、`USE`、`CREATE DATABASE` → 拒绝，一个字节都不灌 | 2 |
| 3 | 远程开关 | 目标主机不是 `127.0.0.1/localhost/::1` → 必须显式 `--allow-remote` | 2 |

另有前置守卫：`--mode existing` 且目标库**已有表** → 拒绝（除非 `--allow-nonempty`）。

退出码总表：`0` 成功 · `1` 运行期失败 · `2` **守卫拒绝** · `3` 参数/前置错误 · `4` 灌库失败 · `5` 恢复后无表。

---

## 3. 完整演练步骤（本机，零生产风险）

前置：本机有 `docker` + `mysql` 客户端 + `bash`；镜像 `mysql:8.0`（首次会自动 pull）。

### 3.1 取一份备份

云端备份落在 `/opt/vibeMusic/docker-data/backups/mysql/`（保留 14 天，`vibemusic-YYYYmmdd-HHMM.sql.gz`）。
用 `scp` 拉一份到**本机**（不要在生产机上解压执行）：

```bash
scp root@<云主机>:/opt/vibeMusic/docker-data/backups/mysql/vibemusic-20261019-0300.sql.gz /tmp/
gzip -t /tmp/vibemusic-20261019-0300.sql.gz && echo "包完整"   # 先验完整性
```

### 3.2 空跑一遍（不连任何数据库）

```bash
cd ~/projects/vibeMusic
./scripts/restore-mysql.sh --dump /tmp/vibemusic-20261019-0300.sql.gz --dry-run
```

输出会打印剥离统计（`DROP TABLE=N`、`USE=1`、`CREATE DATABASE=1`）与残留扫描结论。
**只要残留扫描不通过，脚本就以 exit 2 停下，不会连库。**

### 3.3 正式恢复（默认为一次性容器，零污染）

```bash
./scripts/restore-mysql.sh --dump /tmp/vibemusic-20261019-0300.sql.gz
```

脚本会：起一个随机端口的 `mysql:8.0`（`--rm`）→ 灌库 → 打印校验报告 → 退出时删容器。
报告含：清洗统计、表数量、每张表行数、触发器/存储过程数量，以及对
`users` / `user_favorite` / `play_history` / `playlist` / `song` 的抽样
（抽样列自动排除 `password|token|cookie|secret` 等敏感列）。

想人工进容器核对（演练时推荐）：

```bash
./scripts/restore-mysql.sh --dump /tmp/vibemusic-20261019-0300.sql.gz --keep
# 按输出提示取一次性密码（不落日志）：
docker inspect <container> --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^MYSQL_ROOT_PASSWORD=//p'
docker exec -it <container> mysql -uroot -p"<上面的密码>" -D vibemusic_restore_drill
# 核对完务必清理：
docker rm -f <container>
```

### 3.4 恢复进已有容器（演练"不会毁数据"用）

```bash
export DRILL_PW=...            # 目标容器的 root 密码，只放环境变量
./scripts/restore-mysql.sh --dump /tmp/backup.sql.gz --mode existing \
  --host 127.0.0.1 --port 33061 --user root --password-env DRILL_PW
```

若该库已有表 → 拒绝（exit 2）。加 `--allow-nonempty` 会看到
`Table 'xxx' already exists` 报错退出，**已有数据一条不少**。

### 3.5 清理与收尾

```bash
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'   # 确认没有残留的一次性容器
docker rm -f <任何 drill 容器>
rm -f /tmp/vibemusic-*.sql.gz /tmp/backup.sql              # dump 含全量业务数据，别长留
```

---

## 4. 绝不能做什么

- ❌ **绝不把 dump 灌进生产实例**，也绝不 `docker exec -i vibemusic-mysql mysql -uroot < dump.sql`。
- ❌ **绝不对任何名为 `vibemusic` 的库恢复**（脚本已硬守卫，别绕过）。
- ❌ 不要为了"让恢复跑通"而给 dump 补 `DROP TABLE`、或去掉脚本的残留扫描。
- ❌ 不要在恢复脚本里加 `--force`（`mysql -f`）：那会让"表已存在"的报错被吞掉，掩盖真问题。
- ❌ 不要改 `scripts/backup-mysql.sh`（备份是红线，只读参考）。
- ❌ 不要把 dump、还原出的库、容器密码提交进仓库。

---

## 5. 本机一分钟自测

```bash
cd ~/projects/vibeMusic
bash -n scripts/restore-mysql.sh                       # 语法门
printf 'SELECT 1;\n' > /tmp/t.sql
./scripts/restore-mysql.sh --target-db vibemusic --dump /tmp/t.sql; echo "exit=$?"   # 期望 exit=2 被拒
./scripts/restore-mysql.sh --dump /tmp/t.sql --dry-run                                # 期望 exit=0 干净通过
docker ps --format '{{.Names}}' | grep -c 'vibemusic-restore' || echo "无残留容器 ✓"
```

---

## 6. 变更记录

- 2026-10-19 初版：`scripts/restore-mysql.sh` + 本文档。
  起因：仓库有 `backup-mysql.sh` 却**没有任何 restore 路径**，
  而该 dump 含 `DROP TABLE IF EXISTS` + `USE vibemusic`，误灌生产即不可逆丢数据。
