# AGENTS.md — musicapi（Express BFF 网关）

**镜像：** node:20.19-alpine。**结构：** 扁平单文件，无 src/。

## STRUCTURE
- `server.js` — 全部路由 + 中间件单文件全量。
  - 路由：`/search`、`/netease/*`、`/qq/search`、`/qq/playlist`、`/song/url/qq`、`/lyric`、`/health`、`/metrics`。
  - CORS：origin `http://localhost:5173`（可用 `CORS_ORIGIN` env 覆盖）。
  - 全局 `/search/url` 三级限流；Prometheus metrics；日志写 `logs/`（access / api-errors / cookie-monitor / degradation / cpolar-monitor）。
- `config.js` — Cookie 配置，**已 gitignore**。
- `config.example.js` — 提交模板。
- `test/server.test.js` — 测试。

## CONVENTIONS
- **Cookie 仅从环境变量读取**：`MUSIC_QQ_COOKIE`（JSON 解析）+ `MUSIC_NETEASE_COOKIE`（原始字符串）。禁止硬编码。
- `neteaseApis` 白名单数组限制暴露的 NeteaseCloudMusicApi 方法。
- QQ 搜索用经典 `client_search_cp` 端点（无 cookie）；**不要加回 `t:0` 参数**（已废弃移除）。
- 测试：`console.assert` + `node --test`，需先启动 server.js（端口 3000 直连），未接入根 npm test。

## ANTI-PATTERNS
- 把真实 Cookie/密钥写进 config.js 提交 —— config.js 必须保持 gitignore。
- 后端（vibeMusic-backend）不经过本网关直连网易云 —— backend 的 NeteaseApiService 只调本服务。