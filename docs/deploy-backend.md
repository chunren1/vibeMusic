# 后端蓝绿部署（scripts/deploy-backend.sh）

一条命令把后端新镜像发到云主机，**对外零停机**。

```bash
./scripts/deploy-backend.sh                          # 默认 dry-run：只打印命令 + 只读盘点远端
./scripts/deploy-backend.sh --apply                  # 真发：本机构建→传镜像→起 next→等 healthy→切 nginx→停旧
./scripts/deploy-backend.sh --apply --skip-build      # 复用本机已有 vibemusic-backend:<TAG>
./scripts/deploy-backend.sh --apply --image 2026-10-23
./scripts/deploy-backend.sh --apply --health-timeout 420   # 冷启动更慢时放宽（默认 300s）
```

## 为什么改蓝绿

老流程是「停旧容器 → 起新容器 → 等 161 秒冷启动」，用户实打实断 2.6 分钟。
蓝绿把等待挪到切流量**之前**：新容器先在备用端口（8082/8083…）跑到 `healthy`，才 `reload nginx` 切过去；
切换本身是毫秒级 reload。失败时 nginx 一个字节没动，旧容器继续服务。

```
现容器 127.0.0.1:8080 ──┐
                        ├─ nginx upstream vibemusic_backend ── 用户
next   127.0.0.1:8082 ──┘   （只在 next healthy 之后才指过去）
```

## 内存账（1608MB 机器上这是唯一硬约束）

| 项 | 稳态 |
|---|---|
| backend（一个 JVM，limit 768M） | ~360MB |
| mysql / redis / musicapi | ~90 + 14 + 74MB |
| 小计 | **~540MB** |
| 蓝绿窗口内多一个 JVM | +360MB（启动峰值受 768M limit 约束） |
| 峰值 | **~900MB 起，最坏接近 1.3GB** |

所以脚本在**起新容器前**读 `free -m` 的 `available`，**低于 400MB 直接拒绝**（`--force` 可覆盖）。
别随手 `--force`：两个 JVM 抢内存会一起换页，冷启动从 161s 变几分钟，等于把零停机换成"更久的半死"。
顺带看一眼 `swap`：`health-alert.sh` 里 swap>80% 会告警，蓝绿期间换页会明显变多。

## 端口轮换（为什么不是 8080/8081）

- 现容器绑 `127.0.0.1:8080`，新容器从候选 `8080/8082/8083/8084` 里挑**第一个空闲且不是现用**的。
- **8081 不能用**：宿主机 nginx 自己 `listen 127.0.0.1:8081`（Cloudflare 隧道回源口），抢了就炸站点。
- 下次部署自动轮换回去（8082 → 8080），脚本每次现场判断"现在谁在服务"，不靠记忆。

## 镜像传输校验：比内容，不比 image ID

第 3 步 `docker save | ssh | docker load` 之后要确认「云端跑的就是本地这份」，判据是**内容**：

| 比什么 | 命令 |
|---|---|
| 层清单（逐层 diff_id） | `docker image inspect -f '{{json .RootFS.Layers}}' <img>` |
| Config（取 sha256） | `docker image inspect -f '{{json .Config}}' <img> \| sha256sum` |

两者都一致才继续；任一不一致会打印**哪一项不一致 + 两边值**并中止在 3/8（容器与 nginx 未动）。

**为什么不比 image ID（曾经这么写，2026-10-23 实测误杀）**：

```
本地 docker 29.1.3：id=sha256:666b4dbe…  size=288747223
云端 docker 29.7.2：id=sha256:668056cc…  size=290273361   ← 同一份 tar，load 完 ID 就变了
但：RootFS.Layers 9 层 diff_id 逐层相同；Config 的 md5 两边都是 d3d0be0c6381…
```

原因：`docker load` 跨版本会规范化 config JSON，`.Size` 的计算口径也不同。所以 **ID 和 Size 都不是内容指纹**，
只有「层 + Config」是。dry-run 会做一次只读预校验（比云端现有该 tag 的内容），真跑在传输后重新校验。

## 启动命令重建：`docker inspect -f '{{range}}'` 的尾部空行

新容器不是 compose 起的，`Entrypoint`/`Cmd` 要从现容器 `docker inspect` 抄。这里有个坑：

```bash
# 现容器 Entrypoint = ["sh"]
mapfile -t EP < <(docker inspect -f '{{range .Config.Entrypoint}}{{.}}{{"\n"}}{{end}}' "$C")
# 结果：EP=("sh" "")   ← 末尾多一个空串
```

`{{range}}` 给每个元素补换行，`docker inspect -f` 又给整体输出补一个换行 → `mapfile` 的末元素恒为空串。不滤掉，重建出来的命令是 `sh '' -c 'exec java ...'`，容器起来就死循环重启，日志只有：

```
sh: can't open '': No such file or directory
```

因为带 `--restart unless-stopped`，容器一直在重启、`State.Running` 永远是 `true`，脚本会**盲等到健康超时**（默认 420s）才失败。

脚本已加两道护栏：

1. `mapfile` 两侧都过 `sed '/^$/d'`；
2. 等 healthy 的循环里读 `RestartCount`，`> 3` 立即打印日志、删容器、退出（线上不动）。

## ⚠ 首次 `--apply` 会改一次宿主 nginx 配置

全程只此一次动 `/etc/nginx`：

1. 新建 `/etc/nginx/conf.d/vibemusic-backend-upstream.conf`：
   ```nginx
   upstream vibemusic_backend { server 127.0.0.1:<新端口>; keepalive 16; }
   ```
2. 把 `sites-enabled/vibemusic` 里两处 `proxy_pass http://127.0.0.1:8080;`（`/uploads/` 和 `/api/`）
   改成 `proxy_pass http://vibemusic_backend;`。

**怎么确认它只做了一次**：
- 改前自动备份到 `/root/.vibemusic-bluegreen/vibemusic.bak-<时间戳>`；
- 幂等依据是 `grep -q vibemusic_backend /etc/nginx/sites-enabled/vibemusic` —— 第二次起直接打印"已迁移过，跳过改写"；
- 改完先 `nginx -t`，不通过就**整体还原**（sites-enabled 与 upstream 一起），此时从未 reload，线上无感。

想手工核对/回退：
```bash
ssh root@101.132.167.178 "grep -n 'proxy_pass' /etc/nginx/sites-enabled/vibemusic"
# 回退成写死端口（注意：回退后蓝绿脚本会重新做一次迁移）
ssh root@101.132.167.178 "cp -a /root/.vibemusic-bluegreen/vibemusic.bak-<ts> /etc/nginx/sites-enabled/vibemusic && rm -f /etc/nginx/conf.d/vibemusic-backend-upstream.conf && nginx -t && systemctl reload nginx"
```
> `keepalive 16` 只有在 location 里同时配了 `proxy_http_version 1.1` + `proxy_set_header Connection ""` 才真正复用；
> 没配只是退化成短连接（**和现在行为一致**），不影响正确性。

## 回滚

| 场景 | 做法 |
|---|---|
| 新容器起不来 / 等 healthy 超时 | 脚本自动删 next 退出非 0，**线上没变**（nginx 未动） |
| 切流量后验证失败 | 脚本自动把 upstream 指回原端口 + reload + 删 next，旧容器继续服务 |
| 已切完想退回旧版本 | `./scripts/deploy-backend.sh --apply --skip-build --image rollback-<时间戳>`（同样零停机） |
| 收尾阶段失败 | 不回滚 nginx（旧容器可能已删），打印现状 + 人工命令 |

每次部署都会把切换前的 `latest` 保底打成 `vibemusic-backend:rollback-<时间戳>`，镜像层面永远有退路。

## 发布时临时改环境变量（`--set-env`）

新容器的环境变量默认是**从现容器 `docker inspect` 原样照抄**的。这意味着：如果这次发布要改的正是某个
环境变量（典型：`JAVA_OPTS`），不显式覆盖的话，新容器会带着**旧值**启动 —— 参数改了等于白改，还很难发现
（容器起来了、健康检查也过，只是 JVM 还是老一套）。

云上 compose 文件已漂移，不能靠改 compose；所以脚本提供 `--set-env`：

```bash
./scripts/deploy-backend.sh --apply --set-env 'JAVA_OPTS=-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/uploads/dumps/ -XX:MaxRAMPercentage=60.0 -XX:MinRAMPercentage=30.0 -XX:MaxDirectMemorySize=64m -XX:MaxMetaspaceSize=160m -XX:+UseG1GC'
```

（上例就是 2026-10 这次发布：摘掉 `-Xlog:class+load` 与 `-XX:NativeMemoryTracking=detail`，减少启动期
文件 IO 与 NMT 常驻开销。）

语义：

- **可重复**，`--set-env A=1 --set-env B=2`；同一个 KEY 写多次**以后者为准**。
- **生效时机**：先 `docker inspect` 导出，再合并覆盖项 —— 顺序不能反，否则旧值会盖回去。
- **去重**：写入远端 env-file 前按 KEY 去重，保留最后一次出现的值、沿用首次出现的位置。
  原因：同名变量在 env-file 里出现多次时，docker 各版本取首个/末个不一致，行为不可靠。
- **原样写入**：每行就是 `KEY=VALUE`，不做 shell 展开、不加引号、不转义。
  所以 `-Dfoo="bar baz"`、`$HOME`、`#hash` 都按字面量进容器。
- **拒绝换行**：值里含换行/回车直接报错退出（`exit 2`），因为 env-file 是一行一个变量，
  换行会被拆成两行、静默污染配置。要传多行请改用挂载文件。
- **不刷屏**：dry-run 打印 `KEY=***<前 12 字符>...`；`--apply` 只打印 `KEY=（值已隐藏，N 字符）`，
  完整值只出现在远端 600 权限的 `/root/.vibemusic-bluegreen/next.env` 里，不进终端历史、不进日志。

要确认覆盖是否生效（发布后）：

```bash
ssh root@101.132.167.178 "docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' vibemusic-backend | grep '^JAVA_OPTS='"
```

## 配套注意（不归这个脚本管，但会被它影响）

1. **`health-alert.sh` 的探活端口（已处理，2026-10-23）**：不再写死 `127.0.0.1:8080`，改为按优先级自动解析：
   1. 环境变量 `HEALTH_PORT`（显式覆盖，临时排查用）；
   2. `/etc/nginx/conf.d/vibemusic-backend-upstream.conf` 里的 `server 127.0.0.1:<port>;`
      —— 蓝绿切流量改的就是这一行，等于"nginx 指哪就探哪"；
   3. 回退 `127.0.0.1:8080`（蓝绿首次运行前的现状，那时还没有 upstream 文件）。
   解析失败（文件不存在 / 没有 server 行 / 端口非数字）**静默回退 8080**，不会中断巡检、不会报错退出。
   ⇒ 蓝绿切流量后**无需再手改告警脚本**。注意：云上副本 `/opt/vibeMusic/scripts/health-alert.sh` 要同步这次改动才生效
   （`scp scripts/health-alert.sh root@101.132.167.178:/opt/vibeMusic/scripts/`）；未同步前 `deploy-backend.sh` 收尾会提示。
2. **别再 `docker compose up -d backend`**：转正后的容器是 `docker run` 手工起的（名字相同、端口可能不同）。
   compose 会按自己的定义重建到 8080，而 nginx upstream 还指着 8082 → 502。要回 compose 管理：
   ```bash
   ssh root@101.132.167.178 "docker rm -f vibemusic-backend && cd /opt/vibeMusic && docker compose -f docker-compose.demo.yml up -d backend"
   ssh root@101.132.167.178 "sed -i 's#server 127.0.0.1:.*;#server 127.0.0.1:8080;#' /etc/nginx/conf.d/vibemusic-backend-upstream.conf && nginx -t && systemctl reload nginx"
   ```
3. **watchdog.sh** 只盯 `vibemusic-backend` / `vibemusic-musicapi` 的 `unhealthy`，蓝绿期间 next 不在它视野内（脚本自己轮询健康），无冲突。
4. 旧的 jar 直传流程（`mvn package` → scp → compose 重建）已被本脚本取代；`scripts/warmup-backend.sh` 仍可单独调用做预热。

## 参数速查

| 参数 | 默认 | 说明 |
|---|---|---|
| `--apply` | 否（dry-run） | 不加只打印，零副作用 |
| `--image TAG` | `latest` / `$IMAGE` | 镜像 tag，同时用于远端保底命名 |
| `--skip-build` | 否 | 跳过本机 `docker build`，复用已有 tag |
| `--health-timeout N` | 300 | 等 next healthy 的秒数上限 |
| `--host USER@IP` | `root@101.132.167.178` | 目标主机 |
| `--force` | 否 | available < 400MB 时仍然继续（有风险） |
| `--set-env KEY=VALUE` | 无 | 覆盖/追加新容器环境变量，可重复、同名后者胜（详见上文「发布时临时改环境变量」） |
