# 异地备份（Offsite Backup）

> 适用对象：`scripts/offsite-backup-pull.sh`（拉取）+ `scripts/restore-mysql.sh`（恢复）。
> 一句话：**云上那份 dump 是唯一副本，本脚本把它定期拉到本机留一份，灾难时本机就能独立恢复。**
> 相关：`scripts/backup-mysql.sh`（云上产出，只读参考）、`docs/RESTORE-DRILL.md`（恢复演练 SOP）。

---

## 1. 为什么需要它

### 1.1 现状：备份与生产在同一个故障域

| 事实 | 含义 |
|---|---|
| `scripts/backup-mysql.sh` 每天 03:30 在**云上**跑，产出 `vibemusic-YYYYmmdd-HHMM.sql.gz`（约 67 KB） | 备份逻辑是有的 |
| dump 落在**云服务器本机** `/opt/vibeMusic/docker-data/backups/mysql/` | 备份和生产 MySQL **在同一台机器、同一块盘** |
| 仓库里没有这份数据（`docker-data/` 不入库） | **云端是唯一副本** |

于是所有单点风险都成立：机器挂了 / 云盘损坏 / 误删目录 / 忘了续费机器被回收 →
**生产库和它的备份一起消失**。这不是"能不能恢复"的问题，是"没有东西可恢复"。

### 1.2 本机能提供什么

本机（WSL2）根分区 `1007G 总 / 71G 已用 / **886G 空闲**`，留 14 份 67KB 的 dump 占用不到 1 MB。
即使按"每天一份、保留 14 天"算，异地留存的空间成本约等于 **0**，但它换来一个**独立故障域**的副本：
云上机器整台没了，本机这一份还在，`restore-mysql.sh` 立刻可以灌出一个可用库。

---

## 2. 方案取舍：拉取式 vs 对象存储

| 维度 | **拉取式（本方案，已落地）** | 对象存储（如 OSS/COS/S3） |
|---|---|---|
| 成本 | 0（复用本机空闲盘） | 存储费 + 请求费（量小但非零） |
| 新增凭据 | **无**（只用已有的服务器密码；本机不新增任何第三方 key） | 需新增 AK/SK，且要配置到云上或本机 |
| 故障域 | 与云服务器不同（本机 WSL） | 与云服务器不同（第三方 region） |
| 操作复杂度 | `cron` 一行 | 装 CLI/配 SDK + 生命周期规则 |
| 缺点 | 需要本机在跑（WSL 关机期间不拉） | 多一份凭据泄露面 |

**结论：选拉取式。** 我们要解决的只是"云端单点是唯一副本"，拉取式零成本、不新增第三方凭据、
不动生产侧配置，完全够用。

**若以后要**（各一句，暂时不做）：

- 想要"云上机器整台没了也不用手动拉" → 在云上加 `rclone`/`coscmd` 定时上传对象存储，本机仍保留拉取式作为第二副本。
- 想要"跨地域、多版本、防篡改" → 对象存储开版本控制 + 一次写入（WORM）保留策略，代价是要管 AK/SK。
- 想要"本机也不在线也不怕" → 再加一个独立的第三方目标（另一台机器/另一家云），本方案架构不用改，只是多一个 `--dest`。

---

## 3. 脚本自身的硬约束（改动前必读）

`scripts/offsite-backup-pull.sh` 对生产服务器**只读**，这是不变量，不是建议：

```
远端命令白名单只有三类： ls / sha256sum / cat
严禁在远端创建、修改、删除、移动、重命名任何文件或目录；
严禁在远端执行 docker / mysql / systemctl / cron 等任何写操作或进程控制命令。
下载动作 = 远端 cat <dump> 把字节流重定向到本地文件（等价于 scp，纯读）。
```

- 凭据**只从环境变量 `SSHPASS` 取**（`sshpass -e`），脚本不接受明文密码参数、不读配置文件、**任何情况下不打印口令**；缺失时立刻退出 3 并打印取值命令。
- 下载后**三重校验**：`gzip -t` 通过 + 大小非 0 且与远端一致 + `sha256` 与远端一致；任一不过 → **删掉本地副本**并以退出码 2 退出（绝不留半截文件）。
- 写入是**先落临时文件再原子 `mv`**，不会出现"看起来有、实际坏"的副本。本地副本权限 `0600`（dump 含全量业务数据）。

退出码：`0` 成功（含 SKIP）· `1` 运行期失败 · `2` 校验不一致（本地副本已删）· `3` 参数/前置条件错误（含缺 SSHPASS）· `4` 连不上云或远端读取失败。

---

## 4. 怎么用

### 4.1 一次性准备（每次 shell 会话）

密码只进环境变量，取值自 `~/.dsh/.credentials.yaml` 的 `refs.VIBEMUSIC_SERVER_PASSWORD`：

```bash
export SSHPASS="$(python3 -c "import yaml,os;print(yaml.safe_load(open(os.path.expanduser('~/.dsh/.credentials.yaml')))['refs']['VIBEMUSIC_SERVER_PASSWORD'])")"
```

> 这条命令**不打印**口令（只把值放进环境变量）。别用 `echo $SSHPASS`、别 `set -x`。

### 4.2 日常用法

```bash
cd ~/projects/vibeMusic

./scripts/offsite-backup-pull.sh                        # 拉最新一份 + 滚动保留 14 份
./scripts/offsite-backup-pull.sh --dry-run              # 只看"将下载什么/将删什么"，不落盘
./scripts/offsite-backup-pull.sh --file vibemusic-20261019-0330.sql.gz   # 指定某一份（补拉）
./scripts/offsite-backup-pull.sh --dest /mnt/d/vibemusic-backups         # 换留存目录
./scripts/offsite-backup-pull.sh --keep 30              # 保留 30 份
```

行为要点：

| 行为 | 说明 |
|---|---|
| **幂等** | 本地已有同名且 sha256 一致的副本 → 打印 `SKIP`，不重复下载，退出 0。定时任务天天跑是安全的。 |
| **剪枝** | `--keep N`（默认 14）按文件名倒序（`vibemusic-YYYYmmdd-HHMM` 即时间序）保留最新 N 份，**只删 `--dest` 下匹配 `vibemusic-*.sql.gz` 的文件**，删前打印将删清单（名字/大小/时间）。其它文件、隐藏文件、`.offsite-partial.*` 半成品一律不碰。 |
| **清单** | 每次结尾打印"本地留存清单"（文件名/字节数/修改时间 + 远端 sha256），便于人肉核对。 |
| **默认目录** | `~/.dsh/backups/vibemusic/`（与仓库解耦，**dump 绝不入库**）。 |

---

## 5. 挂定时（只在文档里，不装 cron）

云端在 03:30 产出备份，本机在 **05:30** 拉（错开 2 小时，避免刚好碰上备份进行中）。

**第一步**：建一个包装脚本 `~/.dsh/offsite-pull-cron.sh`（口令只进环境变量，不写进 crontab，
这样 `crontab -l` 里看不到任何密钥）：

```sh
#!/bin/sh
# 异地备份拉取（cron 包装）：口令只从 ~/.dsh/.credentials.yaml 取值到环境变量，不落 crontab。
set -eu
export SSHPASS="$(python3 -c "import yaml,os;print(yaml.safe_load(open(os.path.expanduser('~/.dsh/.credentials.yaml')))['refs']['VIBEMUSIC_SERVER_PASSWORD'])")"
exec /home/user/projects/vibeMusic/scripts/offsite-backup-pull.sh "$@" \
  >> "$HOME/.dsh/backups/vibemusic/pull.log" 2>&1
```

```bash
chmod 700 ~/.dsh/offsite-pull-cron.sh    # 只有本人可读可执行
```

**第二步**：cron 一行（`flock -n` 保证上一次没跑完时不会叠加第二个实例）：

```cron
30 5 * * * /usr/bin/flock -n /tmp/vibemusic-offsite.lock /home/user/.dsh/offsite-pull-cron.sh
```

> 为什么不把 `export SSHPASS=...` 直接塞进 crontab？因为 `crontab -l` 是明文的，
> 且 cron 的 `%` 与引号规则会让嵌套的 python 一行变形难调。包装脚本可单独跑、可中断调试。

### WSL 上 cron 的现实问题

1. **cron 可能根本没起**：先 `service cron status`，没起就 `sudo service cron start`（要开机自启见下条）。
2. **WSL 关机/休眠时 cron 不跑**：本机是笔记本，凌晨 05:30 很可能不在线，"每天定时"实际会变成"开机后补跑"。
   这没关系——脚本**幂等**，错过就补，拉到最新一份即可（只是 RPO 从"精确 24h"变成"上次开机以来的最近一份"）。
3. 想要真正准点的异地副本，就得上 §2 的"对象存储"方案（在云端推），那才是与"本机是否开机"无关的。
4. 日志只看 `~/.dsh/backups/vibemusic/pull.log`；退出码非 0 时 `[offsite][fatal]` 行即为原因。

---

## 6. 完整恢复链路：异地副本 → 可用数据库

这是本能力的**验收标准**：不是"文件存在"，而是"能灌出一个能用的库"。

```bash
cd ~/projects/vibeMusic
export SSHPASS=...                              # 见 §4.1

# ① 确认异地副本就位（幂等，安全）
./scripts/offsite-backup-pull.sh
ls -l ~/.dsh/backups/vibemusic/

# ② 完整性自检
gzip -t ~/.dsh/backups/vibemusic/vibemusic-<最新>.sql.gz && echo "包完整"

# ③ 空跑：只做清洗 + 残留扫描，不连任何数据库（期望看到剥离 DROP TABLE=N / USE=1 / CREATE DATABASE=1）
./scripts/restore-mysql.sh --dump ~/.dsh/backups/vibemusic/vibemusic-<最新>.sql.gz --dry-run

# ④ 真恢复：一次性 mysql:8.0 容器（随机端口、--rm、退出即销毁），零污染
./scripts/restore-mysql.sh --dump ~/.dsh/backups/vibemusic/vibemusic-<最新>.sql.gz

# ⑤ 收尾确认无残留
docker ps --format '{{.Names}}\t{{.Image}}'
```

**为什么必须走 `restore-mysql.sh` 而不是 `mysql < dump`**：dump 里带 `CREATE DATABASE vibemusic` +
`USE vibemusic` + 每表一条 `DROP TABLE IF EXISTS`，原样执行会把生产库"删表重建"。
`restore-mysql.sh` 用流式管道剥离这三类语句并改写库名，还有三道守卫（见 `docs/RESTORE-DRILL.md`）。
**异地副本再重要，也不能绕过恢复工具。**

### 6.1 RPO / RTO

| 指标 | 本方案 |
|---|---|
| RPO | 云上每日 03:30 一份；本机开机时补拉 → 最坏 ≈ 24h 数据丢失（比"没有副本"强无限倍） |
| RTO（本机恢复） | 秒级拉取（67KB）+ 秒级灌库 → 分钟级拿到一个可查的库 |
| RTO（灾难接管） | 需要一台有 docker + mysql 客户端的机器 + 本机这份副本；本机已验证可独立完成 |

---

## 7. 本机一分钟自测

```bash
cd ~/projects/vibeMusic
bash -n scripts/offsite-backup-pull.sh                    # 语法门
export SSHPASS=...
./scripts/offsite-backup-pull.sh --dry-run                # 期望 exit 0，只列计划
./scripts/offsite-backup-pull.sh && ./scripts/offsite-backup-pull.sh   # 第二次期望打印 SKIP
./scripts/offsite-backup-pull.sh --keep 2 --dry-run        # 检查"将删除"清单只含 vibemusic-*.sql.gz
env -u SSHPASS ./scripts/offsite-backup-pull.sh; echo $?   # 期望 exit 3 且提示 export SSHPASS
```

---

## 8. 绝不能做什么

- ❌ 不要在脚本里给远端加任何写操作（`rm`/`mv`/`touch`/`find -delete`/`docker`/`mysql`）。云上备份目录**只读**。
- ❌ 不要把 dump 提交进仓库、不要放进 `docker-data/`（那里会被误打包/误同步）；留存目录用 `~/.dsh/backups/`。
- ❌ 不要在命令行写明文密码、不要 `echo $SSHPASS`、不要 `bash -x` 跑本脚本（会打出变量）。
- ❌ 不要为了"让拉取成功"而跳过 sha256 校验；校验不过就删掉本地副本重来，**绝不留可疑文件**。
- ❌ 不要把 `--dest` 指向挂载盘（NTFS）做长期留存：权限/时间戳语义不同，且违背"项目与数据放原生盘"的约定。
- ❌ 不要改 `scripts/restore-mysql.sh` 的安全守卫来"方便恢复"。

---

## 9. 变更记录

- 2026-10-19 初版：新增 `scripts/offsite-backup-pull.sh` + 本文档。
  起因：云上每日备份**只有云端一个副本**，机器/磁盘/误删任一事故 = 生产与备份同时丢失。
  同日已完成端到端验证：拉取真实生产 dump（`vibemusic-20261019-1042.sql.gz`，67803 B，
  sha256 `3934bf98…b19a09`）→ 两侧 sha256 一致 → 二次运行 SKIP → `--keep 2` 剪枝只删匹配项 →
  该副本经 `restore-mysql.sh` 真实灌入一次性容器（`offsite-test-*`，随机端口）恢复出 8 张表并抽样可读 →
  容器已销毁、无残留。
