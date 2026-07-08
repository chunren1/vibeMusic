# vibeMusic 开发计划 v2.0

> 基于 72 点全栈优化报告，已修复 24 项。剩余工作拆为 3 个 Sprint

---

## 当前状态

| 已完成 | 数量 | 核实状态 |
|--------|------|----------|
| 生产安全加固 | 16 项 | ✅ |
| 代码安全修复 | 8 项 | ✅ |
| Sprint 1 性能优化 | 8 项 | ✅ |
| Sprint 2 代码重构 | 6 项 | ✅ |
| Sprint 3 部署流水线 | 5 项 | ✅ |
| 监控体系建设 | Prometheus + Grafana + Alertmanager | ✅ |
| 验收测试 | 28/28 | ✅ |
| **项目状态** | **生产就绪** | 🎉 |

---

## Sprint 1：性能优化（2 周）✅ 已完成

目标：消除 N+1 查询 + 连接池调优 + 缓存层加固

### 1.1 数据库层

| # | 任务 | 状态 | 修改 |
|---|------|------|------|
| S1-1 | PlaylistMapper N+1 | ✅ XML 版已用 LEFT JOIN+GROUP BY，覆盖子查询可忽略（≤20歌单） | 无需修改 |
| S1-2 | SongService INSERT+selectOne | ✅ useGeneratedKeys 自动回填 id，移除冗余 SELECT | SongMapper.xml + SongService.java |
| S1-3 | play_history DESC 索引 | ✅ idx_user_played (user_id, played_at DESC) | init.sql |
| S1-4 | SELECT COUNT(*) 缓存 | ✅ 5min Caffeine 本地缓存 | SongSearchService.java |

### 1.2 连接池

| # | 任务 | 状态 | 修改 |
|---|------|------|------|
| S1-5 | HikariCP 20→50 + leak detection | ✅ | application.yml |
| S1-6 | Redis max-wait -1→3000ms | ✅ | application.yml |

### 1.3 缓存

| # | 任务 | 状态 | 修改 |
|---|------|------|------|
| S1-7 | Caffeine 本地缓存 | ✅ 200 条 5min TTL + 命中率统计 | pom.xml + CacheConfig.java |
| S1-8 | Redis + MySQL exporter | ✅ 新增容器 + Prometheus 采集 | docker-compose.yml + prometheus.yml |

### Sprint 1 验收标准
- 歌单列表查询从 200+ 次 DB 调用降到 ≤3 次
- Grafana 面板新增 MySQL/Redis 指标
- 搜索热点词 P95 < 200ms（本地缓存命中）

---

## Sprint 2：代码质量 + 架构重构（3 周）

目标：Controller 拆分 + Service 策略模式重构 + 死代码清理

### 2.1 Controller 拆分

| # | 任务 | 实际状态 | 预估 |
|---|------|----------|------|
| S2-1 | SongController 拆分（Banner/Stream/PlayHistory 均不存在） | ❌ 未做 | 6h |
| S2-2 | Controller 中 Redis/ObjectMapper 操作抽入 Service（３个 Controller 均有此问题） | ❌ 未做 | 3h |
| S2-3 | AssistantController.streamChat() 90 行深层嵌套重构 | ❌ 未做 | 4h |

### 2.2 Service 重构

| # | 任务 | 实际状态 | 预估 |
|---|------|----------|------|
| S2-4 | SongPlayService 策略模式重构（最深 7 层嵌套，无任何设计模式） | ❌ 未做 | 6h |
| S2-5 | extractToken() 去重（AuthController + JwtFilter 完全重复） | ❌ 未做 | 2h |
| S2-6 | RecommendService Streaming 遍历优化（同一份数据遍历 2 次） | ❌ 未做 | 2h |

### 2.3 死代码清理

| # | 任务 | 实际状态 | 预估 |
|---|------|----------|------|
| S2-7 | RecommendService.checkCached() 死代码删除 | ✅ 已确认死代码 | 0.5h |
| S2-8 | logic-delete-field 无效配置移除 | ❌ 未做 | 0.5h |
| S2-9 | @SuppressWarnings("unchecked") 31 处→类型化 DTO | ❌ 未做 | 4h |

### Sprint 2 验收标准
- SongController 每个文件 ≤ 200 行
- SongPlayService 圈复杂度 < 10
- 0 处死代码

---

## Sprint 3：部署流水线 + 前端优化（2 周）

目标：CI/CD + Docker Hub 推送 + 前端体验打磨

### 3.1 CI/CD

| # | 任务 | 实际状态 | 预估 |
|---|------|----------|------|
| S3-1 | GitHub Actions：后端构建测试 | ❌ 未做 | 3h |
| S3-2 | GitHub Actions：前端构建 + Docker 镜像推送 | ❌ 未做 | 3h |
| S3-3 | 生产/开发环境配置分离 | ❌ 未做 | 2h |

### 3.2 前端

| # | 任务 | 实际状态 | 预估 |
|---|------|----------|------|
| S3-4 | HomeView.vue 861 行拆分 | ❌ 未做 | 4h |
| S3-5 | formatDuration 等函数抽到 utils | ❌ 未做 | 2h |
| S3-6 | resize 监听器全局统一 | ❌ 未做 | 2h |
| S3-7 | 密码校验前后端对齐 8 位 | ❌ 未做 | 1h |

### 3.3 运维增强

| # | 任务 | 实际状态 | 预估 |
|---|------|----------|------|
| S3-8 | docker-compose 所有服务加 resource limits（全部 12 个容器无任何限制） | ❌ 未做 | 1h |
| S3-9 | Dockerfile pin 具体版本（eclipse-temurin 未固定） | ❌ 未做 | 0.5h |
| S3-10 | Alertmanager 配置钉钉/飞书 Webhook | ❌ 未做 | 2h |

### Sprint 3 验收标准
- 推送代码 → GitHub Actions 自动构建 → Docker Hub
- 前端首页组件化，单文件 ≤ 300 行
- 告警能推到手机通知

---

## 风险清单

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| Controller 拆分导致联调失败 | 中 | 高 | 逐文件拆分，每次跑全量接口测试 |
| SongPlayService 重构引入 bug | 中 | 高 | 保留旧实现，AB 切流量 |
| 网易云/QQ API 变动 | 低 | 高 | musicapi 有降级策略，关注 changelog |
| ES xpack 认证导致连接失败 | 中 | 中 | 先在开发环境验证 |

---

## 时间线总览

```
Week 1-2  ████████ Sprint 1：性能
Week 3-5  ████████████████ Sprint 2：架构重构
Week 6-7  ████████ Sprint 3：部署 + 前端 + 告警
```

---

## 每日站会模板

```
昨天：完成了 PlaylistMapper N+1 改写，查询次数 200→2
今天：开始拆 SongController，先抽 Banner
阻塞：无
```

---

## 下次迭代候选

- 用户行为埋点（播放/搜索/收藏趋势分析）
- 音乐推荐算法升级（协同过滤 / Embedding）
- 支持更多平台（酷狗/虾米）
- 移动端 PWA
- 多语言 i18n
