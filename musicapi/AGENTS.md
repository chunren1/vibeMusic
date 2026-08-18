# AGENTS.md — musicapi（Express BFF 网关）

**镜像：** node:20.19-alpine。**结构：** 入口 + `src/` 按职责拆分（CommonJS）。

## STRUCTURE
- `server.js` — 入口装配：中间件链 + 路由注册 + 启动（不承载业务逻辑）。
- `src/` — 按职责拆分：
  - `logger.js` — 按天轮转日志（access / api-errors / cookie-monitor / degradation / cpolar-monitor）。
  - `rate-limiters.js` — 全局 `/search/url` 三级限流。
  - `metrics.js` — Prometheus metrics 注册与中间件。
  - `cookie.js` — Cookie 统一管理（checkCookies / checkQQCookie / reloadQQCookie）。
  - `scoring.js` — 搜索评分算法（cleanSongName / fingerprint / 相关性 / 热度 / 去重 / 分页 / 标准化）。
  - `search.js` — 搜索代理（网易云 / QQ / 结果精炼 / 黑名单过滤 / 内存缓存）。
  - `routes.js` — 全部路由：`/search`、`/netease/*`、`/qq/search`、`/qq/playlist`、`/song/url/qq`、`/lyric`、`/health`、`/metrics`。
  - `config-loader.js` — 应用配置加载（优先根 config.js，回退环境变量）。
- `config.js`（根）— Cookie 配置，**已 gitignore**；`config.example.js` — 提交模板。
- `test/server.test.js` — 集成测试（需先启动 server.js）。

## CONVENTIONS
- **Cookie 仅从环境变量读取**：`MUSIC_QQ_COOKIE`（JSON 解析）+ `MUSIC_NETEASE_COOKIE`（原始字符串）。禁止硬编码。
- `neteaseApis` 白名单数组限制暴露的 NeteaseCloudMusicApi 方法。
- QQ 搜索用经典 `client_search_cp` 端点（无 cookie）；**不要加回 `t:0` 参数**（已废弃移除）。
- 测试：`console.assert` + `node --test`，需先启动 server.js（端口 3000 直连），未接入根 npm test。

## ANTI-PATTERNS
- 把真实 Cookie/密钥写进 config.js 提交 —— 根 config.js 必须保持 gitignore（src/config-loader.js 是加载器，可入库）。
- 后端（vibeMusic-backend）不经过本网关直连网易云 —— backend 的 NeteaseApiService 只调本服务。