<h1>
  <img src="image/logo.png" alt="vibeMusic" width="48" style="vertical-align: middle; margin-right: 12px;" />
  vibeMusic
</h1>

> 独立开发的全栈音乐平台 — 双源聚合搜索、AI Function Calling Agent、Redis + ES 四级缓存降级、Docker 十容器编排、Prometheus 可观测性。

<p align="center">
  <a href="https://github.com/chunren1/vibeMusic/actions/workflows/test.yml"><img src="https://github.com/chunren1/vibeMusic/actions/workflows/test.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/chunren1/vibeMusic"><img src="https://img.shields.io/badge/coverage-60%25%2B%20gate-brightgreen" alt="Coverage"></a>
  <a href="https://adoptium.net/"><img src="https://img.shields.io/badge/Java-17-orange" alt="Java"></a>
  <a href="https://vuejs.org/"><img src="https://img.shields.io/badge/Vue-3.5-4FC08D" alt="Vue"></a>
  <a href="https://www.docker.com/"><img src="https://img.shields.io/badge/Docker-10_Containers-2496ED" alt="Docker"></a>
</p>

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

> Vue SPA → Nginx → Spring Boot / Express BFF → MySQL / Redis / ES / MinIO → Prometheus → Grafana

---

## 🚀 Quick Start

```bash
# 开发模式
git clone https://github.com/chunren1/vibeMusic.git
npm run install:all
npm run dev

# Docker 全栈部署
npm run build
docker compose up -d
```

| 服务 | 地址 |
|------|------|
| Web | http://localhost |
| API 文档 | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3001 |

---

## 🔍 功能

**搜索四级降级** — `Redis 2ms → ES 15ms → API 实时 800ms → 空结果兜底`，热门词预热确保缓存命中率 92%。

**AI Function Calling** — DeepSeek V4 + `search_songs` / `get_user_history` 工具，LLM 自主决定搜索关键词，SSE 流式输出，首字延迟 < 500ms。

**音质六级 SLA** — LOCAL → HIRES → EXHIGH → HIGHER → STANDARD → FALLBACK，`CompletableFuture` 并行探测，P95 < 0.4s。

**个性化推荐 v3** — 随机种子 + 歌手兴趣扩展 + Redis 缓存 + 离线标记，30 分钟刷新周期。

---

## 📸 页面展示

<table>
  <tr>
    <td width="33%" align="center"><img src="image/首页.png" alt="首页" width="100%" /></td>
    <td width="33%" align="center"><img src="image/歌曲播放页.png" alt="播放器" width="100%" /></td>
    <td width="33%" align="center"><img src="image/ai助手页.png" alt="AI助手" width="100%" /></td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="image/歌单页.png" alt="歌单" width="100%" /></td>
    <td width="33%" align="center"><img src="image/收藏页.png" alt="收藏" width="100%" /></td>
    <td width="33%" align="center"><img src="image/播放历史页.png" alt="播放历史" width="100%" /></td>
  </tr>
</table>

---

## 📊 性能

> **K6 Benchmark** — 50 虚拟用户 × 60 秒 × 9,961 请求 · 0 错误 · 所有阈值通过 ✅

<p align="center">
  <img src="image/k6.png" alt="K6 Benchmark" width="90%" />
</p>

| 指标 | 基线 | 优化后 | 变化 |
|------|:---:|:-----:|:----:|
| 搜索 P95 | 3.48s | **0.36s** | ↓ 90% |
| 音频流 P95 | 4.41s | **0.38s** | ↓ 91% |
| 收藏成功率 | 97% | **100%** | 17→0 失败 |
| 吞吐量 | 77.7 req/s | **158 req/s** | ↑ 104% |

---

## 🐳 运维监控

<table>
  <tr>
    <td width="50%" align="center">
      <strong>10 容器编排</strong><br/>
      <img src="image/docker.png" alt="Docker" width="100%" />
    </td>
    <td width="50%" align="center">
      <strong>Grafana 监控面板</strong><br/>
      <img src="image/grafana.png" alt="Grafana" width="100%" />
    </td>
  </tr>
</table>

**10 容器**：Nginx · Spring Boot 4 · Express BFF · MySQL 8.0 · Redis 7 · ES 8.18 · MinIO · Prometheus · Grafana · Alertmanager

**监控链路**：Micrometer 埋点 → Prometheus 采集 → Grafana 可视化 → Alertmanager 告警

---

## 🧪 测试

```text
164 条自动化测试
├── 后端 87 条 (JUnit 5 + Mockito + H2)
│   └── Service · Controller · JWT · 幂等守卫 · 限流
├── 前端 77 条 (Vitest + jsdom)
│   └── PlayerStore · AuthStore · FavoriteStore
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

启动后端后访问 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) 在线测试。

端点示例：`/api/songs/search` · `/api/songs/stream` · `/api/assistant/chat` · `/api/favorites/toggle`

---

## 📄 License

MIT © [chunren1](https://github.com/chunren1)
