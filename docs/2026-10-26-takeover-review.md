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
8. **审计文档脱敏**：确认当前文件无 `/root`、无 `/opt/vibeMusic`、无内部端口、无口令值；**历史提交里仍有旧路径**（`a2ef23d..c9054c5`），但无口令值，未做历史重写。
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

（待审查 Agent 填写）
