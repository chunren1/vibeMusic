# 🎵 vibeMusic

> **独立开发的全栈音乐平台** — 双源聚合搜索、AI Function Calling Agent、四级缓存降级、Docker 十容器编排、Prometheus 可观测性。

[![CI](https://github.com/chunren1/vibeMusic/actions/workflows/test.yml/badge.svg)](https://github.com/chunren1/vibeMusic/actions/workflows/test.yml)
[![Tests](https://img.shields.io/badge/tests-164%20passed-brightgreen)](https://github.com/chunren1/vibeMusic)
[![Coverage](https://img.shields.io/badge/coverage-60%25%2B%20gate-brightgreen)](https://github.com/chunren1/vibeMusic)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0-6DB33F)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D)](https://vuejs.org/)
[![Docker](https://img.shields.io/badge/Docker-10_Containers-2496ED)](https://www.docker.com/)
[![Vite](https://img.shields.io/badge/Vite-6-646CFF)](https://vite.dev)

---

## 📊 量化指标

| 🚀 指标 | 数值 | 🚀 指标 | 数值 |
|--------|:----:|--------|:----:|
| 全栈测试 | **164 条** | Docker 容器 | **10 个** |
| 代码覆盖率 | **60%+ 门禁** | 搜索 P95 | **< 0.4s** |
| 音频流 P95 | **< 0.4s** | 缓存命中率 | **92%** |
| AI 首字延迟 | **< 500ms** | API 端点 | **30+** |

---

## 🎯 Why vibeMusic?

市面上的音乐播放器项目大多停留在 CRUD 和播放功能。本项目希望**完整模拟互联网音乐平台的后台架构**，因此加入了：

- **双源聚合**：网易云 + QQ 音乐双源搜索、去重、评分排序
- **AI Agent**：基于 LLM Function Calling 实现自然语言操控音乐系统
- **缓存降级**：Redis → ES → API → 兜底四级链路，保障搜索 SLA
- **监控可观测**：Micrometer + Prometheus + Grafana，追踪 JVM/缓存/延迟
- **全栈 DevOps**：10 容器 Docker 编排 + GitHub Actions CI/CD + 164 条测试

---

## 🏗️ 架构

<p align="center">
  <img src="image/架构图.png" alt="System Architecture" width="90%" />
</p>

---

## 🚀 Quick Start

```bash
# 1. 构建前端
npm run build

# 2. 启动全栈（需 docker）
docker compose up -d mysql redis rustfs musicapi backend nginx

# 3. 开发模式（前端热更新）
npm run dev
```

| 服务 | 地址 |
|------|------|
| Web | http://localhost |
| API 文档 | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3001 / admin:admin |
| Prometheus | http://localhost:9090 |

---

## 🔍 功能

### 双源搜索 · AI Agent · 六级音质 · 个性化推荐

**搜索四级降级** — `Redis 2ms → ES 15ms → API 实时 800ms → 空结果兜底`，热门词预热确保缓存命中率 92%。

**AI Function Calling** — DeepSeek V4 + `search_songs` / `get_user_history` 工具，LLM 自主决定搜索关键词，SSE 流式输出，首字延迟 < 500ms。

**音质六级 SLA** — LOCAL → HIRES → EXHIGH → HIGHER → STANDARD → FALLBACK，`CompletableFuture` 并行探测，P95 < 0.4s。

**个性化推荐 v3** — 随机种子 + 歌手兴趣扩展 + Redis 缓存 + 离线标记，30 分钟刷新周期。

---

## 📊 性能

<p align="center">
  <img src="image/k6.png" alt="K6 Benchmark" width="90%" />
</p>

| 指标 | 基线 | 优化后 | 变化 | 目标 |
|------|:---:|:-----:|:----:|:----:|
| 搜索 P95 | 3.48s | **0.36s** | ↓ 90% | < 3s ✅ |
| 音频流 P95 | 4.41s | **0.38s** | ↓ 91% | < 2s ✅ |
| 收藏成功率 | 97% | **100%** | 17→0 失败 | > 99.9% ✅ |
| 吞吐量 | 77.7 req/s | **158 req/s** | ↑ 104% | — |

> 压测工具: K6 | 50 VUs × 60s | 详细报告: [docs/PERFORMANCE-REPORT.md](docs/PERFORMANCE-REPORT.md)

---

## 🐳 DevOps

<table>
  <tr>
    <td width="50%" align="center">
      <strong>10 容器一键部署</strong><br/>
      <img src="image/docker.png" alt="Docker" width="100%" />
    </td>
    <td width="50%" align="center">
      <strong>Grafana 仪表盘 — JVM / GC / 缓存命中率 / 搜索延迟</strong><br/>
      <img src="image/grafana.png" alt="Grafana" width="100%" />
    </td>
  </tr>
</table>

**监控体系**：Micrometer 埋点 → Prometheus 采集 → Grafana 可视化 → Alertmanager 告警推送

**10 容器**：Nginx · Spring Boot · Express BFF · MySQL · Redis · ES · MinIO · Prometheus · Grafana · Alertmanager

---

## 🧪 质量

```
全链路 164 条自动化测试
├── 后端 87 条 (JUnit 5 + Mockito + H2)
│   └── Service/Controller/安全组件/幂等/限流
├── 前端 77 条 (Vitest + jsdom)
│   └── Player/Auth/Favorite/Playlist Store
└── CI/CD (GitHub Actions)
    └── push / PR → 全量测试 → JaCoCo 60% 覆盖率门禁
```

---

## 🛡️ 技术栈

<p align="center">
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&style=flat" />
  <img src="https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?logo=springboot&style=flat" />
  <img src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&style=flat" />
  <img src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis&style=flat" />
  <img src="https://img.shields.io/badge/Docker-2496ED?logo=docker&style=flat" />
  <img src="https://img.shields.io/badge/Nginx-009639?logo=nginx&style=flat" />
  <br/>
  <img src="https://img.shields.io/badge/Prometheus-E6522C?logo=prometheus&style=flat" />
  <img src="https://img.shields.io/badge/Grafana-F46800?logo=grafana&style=flat" />
  <img src="https://img.shields.io/badge/Elasticsearch-8.18-005571?logo=elasticsearch&style=flat" />
  <img src="https://img.shields.io/badge/JUnit5-25A162?logo=junit5&style=flat" />
  <img src="https://img.shields.io/badge/GitHub_Actions-2088FF?logo=githubactions&style=flat" />
  <img src="https://img.shields.io/badge/DeepSeek-4FC08D?logo=openai&style=flat" />
</p>

---

## 🗺️ 路线图

| 阶段 | 内容 |
|------|------|
| ✅ **v1** | 用户认证 · 搜索播放 · 收藏歌单 · 歌词 |
| ✅ **v2** | AI 助手 · 双源聚合 · 推荐引擎 · 歌单导入 |
| ✅ **v3** | 缓存降级 · 幂等守卫 · 限流 · 连接池 |
| ✅ **v4** | 164 测试 · JaCoCo 60% · GitHub CI |
| ✅ **v5** | Docker 10 容器 · Prometheus · Grafana · 告警 |
| ✅ **v6** | K6 压测全达标 · 音频并行降级 · 收藏重试 |
| ⬜ **v7** | Kubernetes 部署 · ArgoCD · OpenTelemetry |

---

## 📖 API

启动后端后访问 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) 在线测试所有接口。

| 模块 | 端点 |
|------|------|
| Auth | `/api/auth/register` `login` `me` `logout` |
| Songs | `/api/songs/search` `play` `stream` `lyric` `random` |
| AI | `/api/assistant/chat` `stream` `history` |
| Favorites | `/api/favorites/toggle` `list` |
| Playlists | `/api/playlists/list` `create` `add-song` |
| Monitor | `/actuator/prometheus` `/api/monitor/cache-stats` |

---

## 📄 License

MIT © [chunren1](https://github.com/chunren1)
