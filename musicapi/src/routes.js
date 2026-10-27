// ==================== 全部路由（组合层）====================
//
// 2026-10-27 网关重构 pass 3：本文件原本 844 行 / 648 纯 LOC（超 250 硬约束），
// 按职责拆到 src/routes/ 下，这里只做**装配 + re-export**，调用方（server.js / 单测）
// 继续 `require('./routes')` 即可，一行不用改。
//
//   routes/helpers.js        —— 查询解析 / 上游响应脱敏 / 搜索缓存键（无状态纯函数）
//   routes/search-route.js   —— GET /search（聚合搜索主入口）
//   routes/ops-routes.js     —— /refresh-qq-cookie /cookie-status /source-health /internal/netease-rotate
//   routes/netease-routes.js —— /lyric /song/url/v1 /personalized /netease/search /netease/*
//   routes/qq-routes.js      —— /qq/search /song/url/qq /qq/lyric /qq/playlist
//   routes/qq-play.js        —— QQ 上游协议件（vkey 探活 / 歌词 / 请求头）
//   routes/source-routes.js  —— 咪咕 / 酷狗 / B站 的搜索与取链、/kugou/lyric
//
// 注册顺序：分组之间无路径重叠，组内顺序与拆分前一致。唯一有顺序要求的是
// `/netease/search` 必须早于 `/netease/*` 通配（同组内已保证）。
const sourceHealth = require('./source-health');
const config = require('./config-loader');
const helpers = require('./routes/helpers');
const registerSearchRoute = require('./routes/search-route');
const registerOpsRoutes = require('./routes/ops-routes');
const registerNeteaseRoutes = require('./routes/netease-routes');
const registerQQRoutes = require('./routes/qq-routes');
const registerSourceRoutes = require('./routes/source-routes');
const { qqRequestHeaders, fetchQQPlayUrl, fetchQQLyric } = require('./routes/qq-play');

function registerRoutes(app) {
  // 停用源标注（配置在进程内不可变，注册时算一次即可）。
  // 让 /source-health 能把「人主动关的源」与「上游坏了的源」区分开。
  sourceHealth.setDisabledSources(config.isQQEnabled() ? [] : ['qq']);

  registerSearchRoute(app);
  registerOpsRoutes(app);
  registerNeteaseRoutes(app);
  registerQQRoutes(app);
  registerSourceRoutes(app);
}

module.exports = registerRoutes;
// 旧契约 re-export：单测与 scripts 直接从 './routes' 取这些纯函数/协议件。
module.exports.sanitizeNeteaseParams = helpers.sanitizeNeteaseParams;
module.exports.scrubSecrets = helpers.scrubSecrets;
module.exports.scrubCookieValues = helpers.scrubCookieValues;
module.exports.SENSITIVE_COOKIE_KEYS = helpers.SENSITIVE_COOKIE_KEYS;
module.exports.buildSearchCacheKey = helpers.buildSearchCacheKey;
module.exports.searchCookieDim = helpers.searchCookieDim;
module.exports.stripSearchInternals = helpers.stripSearchInternals;
module.exports.qqRequestHeaders = qqRequestHeaders;
module.exports.fetchQQPlayUrl = fetchQQPlayUrl;
module.exports.fetchQQLyric = fetchQQLyric;
