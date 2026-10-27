// ==================== 单源搜索/取链路由（咪咕 / 酷狗 / B站）====================
//
// 2026-10-27 网关重构 pass 3：从 routes.js 原样搬出，**行为零变更**。
// 三个源同构：search 无鉴权匿名 + 失败 data:[] 兜底；url 走 60s 硬过期缓存、
// 只缓存成功结果、异常降级 url:null（绝不 500）。差异只在参数形态与上游口径：
//   咪咕  —— id(copyrightId) → 302 签名 URL，绑定出口 IP 且短时效，故意不进缓存
//   酷狗  —— hash(32 hex) + albumId + level(low/standard；high/super 为 VIP 档直接 null)
//   B站   —— id 为纯 bvid 或复合 bvid|cid
const { writeLog } = require('../logger');
const sourceHealth = require('../source-health');
const {
  searchMigu, getMiguUrl, searchKugou, getKugouUrl, searchBili, getBiliUrl, isBiliTrackId, urlCache,
} = require('../search');
const { fetchKugouLyric, parseKugouLyricDuration } = require('../kugou-lyric');
const { urlLimiter } = require('../rate-limiters');
const { GENERIC_500, firstQuery, queryStr } = require('./helpers');

function registerSourceRoutes(app) {
  // ==================== 咪咕搜索 ====================

  app.get('/migu/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      const songs = await sourceHealth.guard('migu', () => searchMigu(keyword, parseInt(limit)));
      res.json({ code: 200, data: songs });
    } catch (error) {
      writeLog('api', 'ERROR', `[/migu/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: [] });
    }
  });

  // ==================== 咪咕播放地址 ====================
  // 返回 302 Location 签名 URL 字符串（后端代理字节流，绝不直传 app）；
  // 无版权/异常时 url=null（与 /song/url/qq 同形 {code, data:[{id, url}]}）。
  // 注意：签名 URL 绑定本机出口 IP 且短时效，故意不进 urlCache。

  app.get('/migu/url', urlLimiter, async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      const copyrightId = queryStr(req.query.copyrightId);
      const toneFlag = queryStr(req.query.toneFlag) ?? 'PQ';
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^\d{4,30}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const url = await getMiguUrl(id, copyrightId, toneFlag || 'PQ');
      res.json({ code: 200, data: [{ id, url }] });
    } catch (error) {
      writeLog('api', 'ERROR', `[/migu/url] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });

  // ==================== 酷狗搜索 ====================
  // 无 Cookie 匿名；limit 上限 50(上游 pagesize 口径)，失败 data:[] 兜底(与 /qq/search 同形)

  app.get('/kugou/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      if (keyword.length > 100) return res.status(400).json({ code: 400, message: 'keyword too long (max 100 chars)' });
      const safeLimit = Math.min(Math.max(1, parseInt(limit) || 20), 50);
      const songs = await sourceHealth.guard('kugou', () => searchKugou(keyword, safeLimit));
      res.json({ code: 200, message: 'success', data: songs });
    } catch (error) {
      writeLog('api', 'ERROR', `[/kugou/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: [] });
    }
  });

  // ==================== 酷狗播放地址 (缓存) ====================
  // 无版权/异常时 url=null（与 /song/url/qq 同形 {code, data:[{id, url}]}）；
  // 只缓存成功结果；high/super 为 VIP 档，phase 1 直接 null 不请求上游。

  app.get('/kugou/url', urlLimiter, async (req, res) => {
    try {
      const hash = queryStr(req.query.hash);
      const albumId = queryStr(req.query.albumId) ?? queryStr(req.query.album_id) ?? '';
      const level = queryStr(req.query.level) ?? 'low';
      if (!hash) return res.status(400).json({ code: 400, message: '缺少 hash 参数' });
      if (!/^[a-f0-9]{32}$/i.test(hash)) return res.status(400).json({ code: 400, message: 'invalid hash format' });
      if (albumId && !/^\d{1,20}$/.test(albumId)) return res.status(400).json({ code: 400, message: 'invalid albumId format' });
      if (!['low', 'standard', 'high', 'super'].includes(level)) return res.status(400).json({ code: 400, message: 'invalid level' });
      if (level === 'high' || level === 'super') {
        return res.json({ code: 200, message: 'success', data: [{ id: hash, url: null }] });
      }
      const cacheKey = `kugou_url:${hash}:${level}`;
      const cached = urlCache.get(cacheKey);
      if (cached) return res.json({ code: 200, message: 'success (cached)', data: cached });

      const url = await getKugouUrl(hash, albumId, level);
      const data = [{ id: hash, url }];
      // 只缓存成功结果：url:null 在 60s 内可能恢复，不缓存(与 /song/url/qq 同策略)
      if (url) urlCache.set(cacheKey, data);
      res.json({ code: 200, message: 'success', data });
    } catch (error) {
      // 兜底：任何意外异常也以降级空形返回，绝不 500
      writeLog('api', 'INFO', `[/kugou/url] 兜底降级: ${error.message}`);
      res.json({ code: 200, message: 'success', data: [{ id: queryStr(req.query.hash), url: null }] });
    }
  });

  // ==================== B站搜索 ====================
  // 匿名 guest；limit 上限 50(上游 page_size 口径)，失败 data:[] 兜底(与 /qq/search 同形)

  app.get('/bili/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      if (keyword.length > 100) return res.status(400).json({ code: 400, message: 'keyword too long (max 100 chars)' });
      const safeLimit = Math.min(Math.max(1, parseInt(limit) || 20), 50);
      const songs = await sourceHealth.guard('bili', () => searchBili(keyword, safeLimit));
      res.json({ code: 200, message: 'success', data: songs });
    } catch (error) {
      writeLog('api', 'ERROR', `[/bili/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: [] });
    }
  });

  // ==================== B站播放地址 (缓存) ====================
  // id 接受纯 bvid 或复合 bvid|cid；无版权/异常时 url=null
  // (与 /song/url/qq 同形 {code, data:[{id, url}]})；只缓存成功结果；
  // playurl URL 约 120min 有效，60s 硬过期复用 qq/kugou 口径。

  app.get('/bili/url', urlLimiter, async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!isBiliTrackId(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const cacheKey = `bili_url:${id}`;
      const cached = urlCache.get(cacheKey);
      if (cached) return res.json({ code: 200, message: 'success (cached)', data: cached });

      const url = await getBiliUrl(id);
      const data = [{ id, url }];
      // 只缓存成功结果：url:null 在 60s 内可能恢复，不缓存(与 /song/url/qq 同策略)
      if (url) urlCache.set(cacheKey, data);
      res.json({ code: 200, message: 'success', data });
    } catch (error) {
      // 兜底：任何意外异常也以降级空形返回，绝不 500
      writeLog('api', 'INFO', `[/bili/url] 兜底降级: ${error.message}`);
      res.json({ code: 200, message: 'success', data: [{ id: queryStr(req.query.id), url: null }] });
    }
  });

  // ==================== 酷狗歌词 ====================
  // 官方两步接口（krcs 搜候选 → lyrics 下 base64 LRC），实现见 src/kugou-lyric.js。
  // 失败/无候选一律 404(与 /qq/lyric 同形)，绝不 500；400 校验保持不变。

  app.get('/kugou/lyric', async (req, res) => {
    try {
      const hash = queryStr(req.query.hash);
      const albumId = queryStr(req.query.albumId) ?? queryStr(req.query.album_id) ?? '';
      if (!hash) return res.status(400).json({ code: 400, message: '缺少 hash 参数' });
      if (!/^[a-f0-9]{32}$/i.test(hash)) return res.status(400).json({ code: 400, message: 'invalid hash format' });
      if (albumId && !/^\d{1,20}$/.test(albumId)) return res.status(400).json({ code: 400, message: 'invalid albumId format' });

      // duration 可选（毫秒或秒，见 parseKugouLyricDuration）：用于在候选里挑时长最接近的官方版本
      const hit = await fetchKugouLyric(hash, parseKugouLyricDuration(req.query.duration));
      if (!hit || !hit.lyric) return res.status(404).json({ code: 404, message: '未找到歌词' });
      // song/singer 便于前端核对选中的版本（契约里为可选字段，后端只读 data.lyric）
      res.json({ code: 200, message: 'success', data: { lyric: hit.lyric, song: hit.song, singer: hit.singer } });
    } catch (error) {
      // 兜底：任何意外异常也以干净空形返回，绝不 500
      writeLog('api', 'INFO', `[/kugou/lyric] 兜底降级: ${error.message}`);
      res.status(404).json({ code: 404, message: '未找到歌词' });
    }
  });
}

module.exports = registerSourceRoutes;
