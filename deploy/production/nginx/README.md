# 两个 nginx 配置，别搞混

这是本项目最容易踩的坑。仓库里有**两套完全不同的 nginx 配置**：

| | 仓库 `nginx/nginx.conf`（`/nginx/` 目录） | 本目录 `vibemusic-site.conf` + `security.conf` |
|---|---|---|
| **角色** | CI 校验用「影子配置」；自建 nginx **容器**方案用 | **云主机系统 nginx**，即线上真实反代 |
| **谁在用** | `.github/workflows/ci.yml`（`nginx -t`）、容器化部署方案 | 生产环境（`systemctl` 的 nginx） |
| **监听** | `443 ssl`，`server_name _` | `127.0.0.1:8081`，`server_name vibe.cyk666.top` |
| **上游** | `upstream backend:8080`（Docker DNS 服务名） | `proxy_pass http://127.0.0.1:8080`（宿主回环） |
| **TLS** | 容器内证书 `/etc/nginx/certs/` | **无证书**（TLS 由 Cloudflare 终结） |
| **前端根** | `/usr/share/nginx/html` | `/var/www/vibemusic` |
| **改了会怎样** | 挂 CI | 挂线上 |

## 为什么不把生产配置直接覆盖进 `nginx/nginx.conf`

因为 CI 依赖那个文件做语法校验，覆盖会同时破坏 CI 与"自建容器方案"的参照物。
所以生产配置**以新增文件的形式**纳管到 `deploy/production/nginx/`，
把两种用途物理隔开：`nginx/` = CI/容器；`deploy/production/nginx/` = 线上系统 nginx。

## 对应关系

| 本目录文件 | 云上路径 |
|---|---|
| `vibemusic-site.conf` | `/etc/nginx/sites-enabled/vibemusic` |
| `security.conf` | `/etc/nginx/conf.d/security.conf` |

云上还有 `/etc/nginx/nginx.conf`（主配置），它是 **Ubuntu 发行版默认**、未自定义，
因此未纳管；生产真正自定义的只有上面两个文件。

## 改生产 nginx 的正确姿势

```bash
# 备份 → 传 → 校验 → 才 reload（顺序不能反）
ssh root@101.132.167.178 'cp -a /etc/nginx/sites-enabled/vibemusic \
    /etc/nginx/sites-enabled/vibemusic.bak-$(date +%Y%m%d-%H%M%S)'
scp deploy/production/nginx/vibemusic-site.conf \
    root@101.132.167.178:/etc/nginx/sites-enabled/vibemusic
ssh root@101.132.167.178 'nginx -t && systemctl reload nginx'
```

`nginx -t` 不通过就**停手**，绝不 reload。
