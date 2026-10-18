# 发版报告模板（RELEASE-TEMPLATE.md）

> 每次发版复制本模板填写。示例值来自真实的"推荐去重"发版（2.0.x），可直接对照。

## 模板（空表）

| 项 | 内容 |
|---|---|
| 版本 / 提交 | `<tag> / <short-sha>` |
| 发版时间 / 发版人 | |
| 用例统计 | L1 单测 x 例 / L2 接口 x 例 / L3 冒烟 20 例 |
| 通过率 | 单测 x/y；冒烟 x/20 |
| 覆盖率 | 后端 JaCoCo LINE x%（门禁 ≥60%，`mvn verify`）；前端 Vitest x% |
| 性能对比 p50/p95 | search p95 <3000ms / stream p95 <2000ms（`scripts/k6-test.js` 阈值，当前手工对照） |
| md5 三处一致 | 本地 `target/*.jar` = 云上 `target/app.jar` = 容器内 `/app/app.jar`（`deploy-backend.sh` 1/3/6 步） |
| 遗留风险 | P0 x 个（必须 0）/ P1 x 个 / P2 x 个 |
| 回滚方案 | 云上备份包路径 `/tmp/app-backup-<TS>-*.jar`；回滚命令：`docker compose -f docker-compose.demo.yml up -d backend` 后验 healthy |

## 填写示例：推荐去重版本 2.0.x

| 项 | 内容 |
|---|---|
| 版本 / 提交 | 2.0.x / `a1b2c3d`（示例） |
| 发版时间 / 发版人 | 2026-09-xx / xxx |
| 用例统计 | L1 后端 82 例全绿（含 `RecommendServiceTest` 去重用例）；L2 游客连刷手工 5 次；L3 冒烟 20/20 |
| 通过率 | 单测 82/82；冒烟 20/20 |
| 覆盖率 | JaCoCo LINE ≥60% 通过（`mvn verify` 门禁）；前端 Vitest 通过 |
| 性能对比 p50/p95 | 推荐接口 refresh=true 连刷 3 次均 <1s（发版脚本第 7 步实测）；k6 50 VUs 为下一步，未卡门 |
| 去重效果 | 游客同设备 5 连刷，重叠仅 1/40 首（seen 集合=已推荐记忆 ∪ 已播放，候选池耗尽回填补满 8 首） |
| md5 三处一致 | 本地 = 传入包 = 容器内运行包，一致（脚本第 1/3/6 步校验） |
| 遗留风险 | P0 0 个；P1 0 个；P2 1 个（换一批 toast 文案待优化，不阻塞） |
| 回滚方案 | 未触发；备份包留存 `/tmp/app-backup-<TS>-*.jar`，healthy 检查 8 分钟超时即按手册回滚 |

备注：`warmup-backend.sh` 预热 + 推荐冒烟均为只读接口，不写生产数据；线上巡检靠 Bugly crashreport + `PlayDiag` 面包屑，次日复查无新增崩溃即关闭本报告。
