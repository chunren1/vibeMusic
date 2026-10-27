// 搜索聚合层 —— 缓存 + 各源适配器的统一出口
//
// 2026-10-27 网关重构 pass 2：本文件原本 1,145 行，五个源全挤在这里（远超单文件 250 纯 LOC 约束）。
// 现在按源物理拆分，本文件只剩**两件事**：
//   ① 两个进程内缓存（searchCache / urlCache）—— 有生命周期语义，且只有 routes 与这里用；
//   ② re-export 各源符号，保持旧契约 —— routes.js 与全部单测仍从 './search' 取，
//      调用点一行都不用改（拆分不该逼着 20 个调用点跟着改 import）。
//
// 源文件分工：
//   sources/netease.js    网易云（全仓唯一 require 第三方库的隔离墙）
//   sources/qq.js         QQ 音乐搜索 + 健康探针
//   sources/migu.js       咪咕搜索 + 取链
//   sources/kugou.js      酷狗搜索 + 取链（v5 Lite 签名）
//   sources/bili.js       B站搜索 + 取流编排
//   sources/bili-auth.js  B站 guest 票据与 WBI 签名
//   sources/bili-title.js B站投稿标题解析（纯函数）
//   sources/shared.js     无状态基元（超时常量 / withTimeout / logSafe）
//
// 注意：这里**不要**再写业务逻辑。新源一律新建 sources/<name>.js，在本文件只加一行 require + re-export。

const { LRUCache } = require('lru-cache');

// 超时常量与 withTimeout 已下沉到 sources/shared.js（避免 sources/* ↔ search.js 循环 require）；
// 这里 re-export 保持旧契约 —— routes.js 仍从 './search' 取这两个名字。
const { UPSTREAM_TIMEOUT, SEARCH_TOTAL_BUDGET, withTimeout } = require('./sources/shared');

// 各源适配器。全部 re-export，调用方（routes.js / 单测）无需感知拆分。
const { searchNetease } = require('./sources/netease');
const { searchQQ, mapQQSong, buildQQSearchRequest, probeQQSearch } = require('./sources/qq');
const { searchMigu, mapMiguSong, getMiguUrl, miguCopyrightCache } = require('./sources/migu');
const {
  searchKugou, mapKugouSong, getKugouPlayData, getKugouUrl,
  kugouDevice, kugouAndroidSignature, kugouUrlKey, pickKugouUrls,
} = require('./sources/kugou');
const {
  searchBili, mapBiliVideo, parseBiliDuration, isBiliSingle,
  BILI_SINGLE_MAX_DURATION_MS, BILI_COMPILATION_KEYWORDS, BILI_MUSIC_TID,
  getBiliUrl, getBiliView, isBiliTrackId,
} = require('./sources/bili');
// parseBiliTitle 在 bili-title.js，但旧契约从 './search' 取，这里照样转出。
const { parseBiliTitle } = require('./sources/bili-title');

// ==================== LRU 缓存（使用 lru-cache 库） ====================

const CACHE_TTL = 5 * 60 * 1000; // 5分钟

const searchCache = new LRUCache({
  // G1：BYOC 键基数随用户增长（每用户一 ckDim），200 会把匿名热门词挤出去 → 提到 500。
  max: 500,
  ttl: CACHE_TTL,
  updateAgeOnGet: true,
});

const urlCache = new LRUCache({
  max: 200,
  // QQ vkey URL 有效期仅数分钟（实测几分钟内返回 403），缓存 60s 足够覆盖重试/刷新，
  // 避免缓存过期 URL 导致播放失败；网易云 URL 不走此缓存
  ttl: 60 * 1000,
  // 硬过期：get 不刷新 TTL。vkey URL 自生成时刻起单调走向失效，
  // sliding refresh 会让高频命中的条目常驻（过期 URL 不死 → 持续 403），
  // 故此处必须禁用 updateAgeOnGet。
  updateAgeOnGet: false,
});

module.exports = {
  UPSTREAM_TIMEOUT,
  SEARCH_TOTAL_BUDGET,
  withTimeout,
  searchCache,
  urlCache,
  searchNetease,
  buildQQSearchRequest,
  probeQQSearch,
  searchQQ,
  mapQQSong,
  searchMigu,
  mapMiguSong,
  getMiguUrl,
  miguCopyrightCache,
  searchKugou,
  mapKugouSong,
  getKugouPlayData,
  getKugouUrl,
  kugouDevice,
  kugouAndroidSignature,
  kugouUrlKey,
  pickKugouUrls,
  searchBili,
  mapBiliVideo,
  parseBiliTitle,
  parseBiliDuration,
  isBiliSingle,
  BILI_SINGLE_MAX_DURATION_MS,
  BILI_COMPILATION_KEYWORDS,
  BILI_MUSIC_TID,
  getBiliUrl,
  getBiliView,
  isBiliTrackId,
};
