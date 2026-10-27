// ==================== QQ 音乐路由（搜索 / 取链 / 歌词 / 歌单）====================
//
// 2026-10-27 网关重构 pass 3：从 routes.js 原样搬出，**行为零变更**。
// 上游协议细节在 ./qq-play.js（vkey 探活 / 歌词 base64 / 请求头），本文件只管
// 参数校验 + 缓存口径 + 响应信封。QQ 源可通过 MUSICAPI_QQ_ENABLED=false 整体停用，
// 停用时搜索由熔断闸门直接短路为空（不报错），取链/歌词仍按无 Cookie 口径降级。
const { writeLog } = require('../logger');
const sourceHealth = require('../source-health');
const { searchQQ, urlCache, UPSTREAM_TIMEOUT } = require('../search');
const { upstreamGet } = require('../upstream');
const { urlLimiter } = require('../rate-limiters');
const { qqRequestHeaders, fetchQQPlayUrl, fetchQQLyric } = require('./qq-play');
const { GENERIC_500, firstQuery, queryStr } = require('./helpers');

function registerQQRoutes(app) {
  app.get('/qq/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      const songs = await sourceHealth.guard('qq', () => searchQQ(keyword, parseInt(limit)));
      res.json({ code: 200, data: songs });
    } catch (error) {
      writeLog('api', 'ERROR', `[/qq/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: [] });
    }
  });

  // ==================== QQ音乐URL (缓存) ====================

  app.get('/song/url/qq', urlLimiter, async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^[a-zA-Z0-9]{10,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const cacheKey = `qq_url:${id}`;
      const cached = urlCache.get(cacheKey);
      if (cached) return res.json({ code: 200, data: cached });

      const url = await fetchQQPlayUrl(id);
      const data = [{ id, url }];
      // 只缓存成功结果：url:null（VIP/无版权/临时失败）在 60s 内可能恢复，不缓存
      if (url) urlCache.set(cacheKey, data);
      res.json({ code: 200, data });
    } catch (error) {
      // 兜底：任何意外异常也以降级空形返回，绝不因缺 Cookie 而 500
      writeLog('api', 'INFO', `[/song/url/qq] 兜底降级: ${error.message}`);
      res.json({ code: 200, data: [{ id: queryStr(req.query.id), url: null }] });
    }
  });

  // ==================== QQ 歌词 ====================

  app.get('/qq/lyric', async (req, res) => {
    try {
      const songmid = queryStr(req.query.songmid);
      if (!songmid) return res.status(400).json({ code: 400, message: '缺少 songmid 参数' });
      if (!/^[a-zA-Z0-9]{10,20}$/.test(songmid)) return res.status(400).json({ code: 400, message: 'invalid songmid format' });

      const lrc = await fetchQQLyric(songmid);
      if (!lrc) return res.status(404).json({ code: 404, message: '未找到歌词' });
      res.json({ code: 200, data: { lyric: lrc } });
    } catch (error) {
      // 兜底：任何意外异常也以干净空形返回，绝不因缺 Cookie 而 500
      writeLog('api', 'INFO', `[/qq/lyric] 兜底降级: ${error.message}`);
      res.status(404).json({ code: 404, message: '未找到歌词' });
    }
  });

  // ==================== QQ 歌单详情 ====================

  app.get('/qq/playlist', async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^\d{3,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await upstreamGet('https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg', {
        label: 'qq/playlist',
        params: { type: 1, utf8: 1, disstid: id, loginUin: 0 },
        headers: { ...qqRequestHeaders(), Referer: 'https://y.qq.com/n/yqq/playlist' },
        timeoutMs: UPSTREAM_TIMEOUT,
      });
      // 直连上游的返回格式：{ cdlist: [ {...} ] }（此前 qq-music-api 会再包一层 data）
      const pl = result.data?.cdlist?.[0] || result.data?.data?.cdlist?.[0];
      if (!pl) return res.status(404).json({ code: 404, message: '歌单不存在' });
      res.json({
        code: 200,
        data: {
          id: String(pl.dissid || id),
          name: pl.dissname || '',
          description: pl.desc || '',
          coverUrl: pl.logo || '',
          creator: { name: pl.nickname || '', avatar: pl.headurl || '' },
          playCount: pl.listennum || 0,
          songCount: (pl.songlist || []).length,
          source: 'qq',
          songs: (pl.songlist || []).slice(0, 100).map(s => ({
            id: String(s.songmid || s.songid || s.id),
            name: s.songname || '',
            artist: (s.singer || []).map(sg => sg.name).join('/') || '',
            album: s.albumname || '',
            coverUrl: s.albummid ? `https://y.gtimg.cn/music/photo_new/T002R300x300M000${s.albummid}.jpg` : '',
            duration: s.interval || 0,
          })),
        },
      });
    } catch (e) {
      writeLog('api', 'ERROR', `[/qq/playlist] ${e.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });
}

module.exports = registerQQRoutes;
