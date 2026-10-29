# deploy/production — 云上生产真源的仓库镜像

> 更新：2026-10-19 · 完整说明见 `docs/cloud-source-of-truth.md`

本目录存放**云服务器 `101.132.167.178` 上"只能从云上取到"的生产配置**。
这些文件不在 `/opt/vibeMusic` 里（那里没有），而是散落在云主机的系统路径下，
过去完全没有版本管理。本次按「云上 → 仓库」方向纳管进来，**逐字节原样**，便于对账。

| 本目录文件 | 云上真实路径 | md5 | 说明 |
|---|---|---|---|
| `nginx/vibemusic-site.conf` | `/etc/nginx/sites-enabled/vibemusic` | `719d8fc5…` | **生产 vhost**：`vibe.cyk666.top`，`127.0.0.1:8081` → 静态 `/var/www/vibemusic` + `/api/` → `127.0.0.1:8080` |
| `nginx/security.conf` | `/etc/nginx/conf.d/security.conf` | `667bb81d…` | 生产安全头 + `limit_req_zone` / `limit_conn_zone` |
| `cloudflared/config.yml` | `/etc/cloudflared/config.yml` | `2ecc5f2f…` | Cloudflare Tunnel ingress（cyk666.top / www / vibe / monitor / ai） |
| `crontab.txt` | root `crontab -l` | — | 每日 03:30 备份、每 5 分钟看门狗 |

## 重要：这些**不是** `nginx/nginx.conf`

仓库根的 `nginx/nginx.conf` 是 **CI 用的影子配置**（`ci.yml` 会 `nginx -t` 校验它），
生产**不使用**它。本目录下的才是线上生效的系统 nginx 配置。
两者监听端口、上游地址、证书、前端根目录都不同，**不要互相覆盖、不要合并**。
详见 `docs/cloud-source-of-truth.md` §4 与 `nginx/README.md`。

> **blog.service 已移除（2026-10-29）**：`/etc/systemd/system/blog.service`、`/opt/blog`、`/opt/blog-srv`
> 均已从云上删除（该服务从 9 月部署起就没接过 nginx 路由，公网从未可达，只白吃 ~140MB 内存）。
> 本目录不再有 `systemd/` 子目录；历史内容见 git 记录与 `docs/2026-10-26-takeover-review.md` §8。

## 发版（将来）

严格按 `docs/cloud-source-of-truth.md` §6 执行：**先备份 → 传 → `nginx -t` → 才 `reload`**。
顺序不能反；`nginx -t` 失败时**绝不** reload。

## 2026-10-29 回填（云上 → 仓库）

`nginx/vibemusic-site.conf` / `nginx/security.conf` 此前与云上漂移：云上 2026-10-26 审计时直接补的
HSTS（`Strict-Transport-Security`，5 处）+ `limit_req_status 429` / `limit_conn_status 429`
（默认 503 会被误判成服务故障）从未回到仓库。本次按「对账方向 = 云上 → 仓库」逐字节回填
（125 / 18 行，md5 见上表），`scripts/reconcile-cloud.sh` 复核为 **0 项真漂移**。

和下面「只读镜像」不矛盾：**发现漂移时以云上为准回填**（云上才是正在生效的配置）；
**之后要改配置，仍然先在仓库改 → 评审 → 按 §6 上云**。改完当场同步仓库，否则下次对账又变 2 项漂移。

## 禁忌

- 别在云上跑构建（1.6G 内存会 OOM）。
- 别 `docker compose down -v` / 别 `docker system prune`。
- 别把 `/root/.cloudflared/*.json`（隧道私钥）或云上 `.env`（生产密钥）提交进仓库。
- 本目录**只读镜像**：改动应先在仓库改、评审、再按 §6 发上云，而不是反过来。
