// ==================== 全部路由（保持原注册顺序） ====================
const path = require('path');
const { spawn } = require('child_process');
const axios = require('axios');
const qqMusic = require('qq-music-api');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');

const config = require('./config-loader');
const cookie = require('./cookie');
const { writeLog } = require('./logger');
const { cacheHitTotal, cookieStatusGauge } = require('./metrics');
const {
  SCORE_CFG,
  calculateScore,
  generateFingerprint,
  isSameSong,
  paginate,
  toStandardFormat,
  refineResults,
} = require('./scoring');
const { searchNetease, searchQQ, searchCache, urlCache, UPSTREAM_TIMEOUT } = require('./search');
const { searchLimiter, urlLimiter } = require('./rate-limiters');

function registerRoutes(app) {
  // ==================== 聚合搜索主入口 ====================

  /**
   * GET /search?keyword=xxx&page=1&size=20&prefer=netease
   */
  app.get('/search', searchLimiter, async (req, res) => {
    try {
      const { keyword, page = 1, size = 20, prefer } = req.query;
      const maxRank = 30; // 各平台获取的最大条数

      if (!keyword) {
        return res.status(400).json({ code: 400, message: 'keyword is required', data: null });
      }
      if (keyword.length > 100) {
        return res.status(400).json({ code: 400, message: 'keyword too long (max 100 chars)', data: null });
      }
      // size 上限 100，防内存溢出
      const safeSize = Math.min(Math.max(1, parseInt(size) || 20), 100);
      // prefer 枚举白名单
      const validPrefers = ['netease', 'qq'];
      const safePrefer = validPrefers.includes(prefer) ? prefer : undefined;

      const kw = keyword.trim();

      // ---- LRU 缓存命中 ----
      const cacheKey = `search:${kw}:${maxRank}`;
      const cached = searchCache.get(cacheKey);
      if (cached) {
        cacheHitTotal.inc({ cache_type: 'search' });
        writeLog('access', 'INFO', `[Cache] HIT for "${kw}"`);
        const pageData = paginate(cached, page, safeSize);
        return res.json({ code: 200, message: 'success (cached)', data: pageData });
      }

      // ---- 并行请求上游 ----
      const [neteaseSongs, qqSongs] = await Promise.all([
        searchNetease(kw, maxRank),
        searchQQ(kw, maxRank),
      ]);

      // ---- 计算得分 ----
      const scored = [];

      neteaseSongs.forEach((song, idx) => {
        const s = { ...song, platform: 'netease', _raw: song._raw };
        s.score = calculateScore(s, kw, idx, neteaseSongs.length, safePrefer);
        scored.push(s);
      });

      qqSongs.forEach((song, idx) => {
        const s = { ...song, platform: 'qq', _raw: song._raw };
        s.score = calculateScore(s, kw, idx, qqSongs.length, safePrefer);
        scored.push(s);
      });

      // ---- 信息指纹去重 ----
      const deduped = [];
      const seen = new Map();

      // 按得分降序排列后再去重，确保得分高的优先保留
      scored.sort((a, b) => b.score - a.score);

      for (const song of scored) {
        const fp = generateFingerprint(song);

        if (seen.has(fp)) {
          const existing = deduped[seen.get(fp)];
          // 同名同歌手加成
          if (isSameSong(existing, song)) {
            existing.score += SCORE_CFG.sameSongBonus;
            existing.sourcePlatforms = [...(existing.sourcePlatforms || [existing.platform]), song.platform];
            existing.higherQuality = song.duration > (existing.duration || 0) ? song : existing;
          }
          // 保留高分的
          if (song.score > existing.score) {
            deduped[seen.get(fp)] = { ...song, sourcePlatforms: [song.platform] };
          }
        } else {
          seen.set(fp, deduped.length);
          deduped.push({ ...song, sourcePlatforms: [song.platform] });
        }
      }

      // ---- 最终排序 ----
      deduped.sort((a, b) => b.score - a.score);

      // ---- 质量过滤 ----
      const refined = refineResults(deduped, kw);

      // ---- 清理内部字段后再缓存 ----
      toStandardFormat(refined);
      searchCache.set(cacheKey, refined);

      // ---- 分页 ----
      const pageData = paginate(refined, page, safeSize);

      res.json({ code: 200, message: 'success', data: pageData });
    } catch (error) {
      writeLog('api', 'ERROR', `[/search] ${error.message}`);
      res.status(500).json({ code: 500, message: error.message, data: null });
    }
  });

  // ==================== Cookie 手动恢复端点 ====================

  // GET /refresh-qq-cookie — 手动触发浏览器提取 Cookie（需有桌面环境）
  app.get('/refresh-qq-cookie', async (req, res) => {
    res.setHeader('Content-Type', 'text/plain; charset=utf-8');
    const scriptPath = path.resolve(__dirname, '..', '..', 'scripts', 'get_qq_cookie.mjs');

    res.write('⏳ 正在打开浏览器提取 QQ Cookie...\n');

    const child = spawn('node', [scriptPath], {
      cwd: path.resolve(__dirname, '..', '..'),
      timeout: 120000,
      env: process.env,
      // 关键：继承父进程 stdio，让 Playwright 有终端上下文可以打开浏览器
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let stdout = '';
    child.stdout.on('data', (d) => { stdout += d.toString(); res.write(d); });
    child.stderr.on('data', (d) => { stdout += d.toString(); });

    child.on('close', async (code) => {
      if (code === 0 && cookie.reloadQQCookie()) {
        cookie.cookieStatus.qq = true;
        cookieStatusGauge.set({ platform: 'qq' }, 1);
        res.write('\n✅ Cookie 提取成功! 已自动加载，无需重启。\n');
      } else {
        res.write(`\n❌ 脚本退出码: ${code}\n`);
        res.write('请在终端手动运行: node scripts/get_qq_cookie.mjs\n');
      }
      res.end();
    });

    child.on('error', (err) => {
      res.write(`\n❌ 无法启动浏览器: ${err.message}\n`);
      res.write('请在终端手动运行: node scripts/get_qq_cookie.mjs\n');
      res.end();
    });
  });

  // POST /cookie/reload — 手动重载 .env 中的 Cookie（无需重启 musicapi）
  app.post('/cookie/reload', (req, res) => {
    if (cookie.reloadQQCookie()) {
      res.json({ code: 200, message: 'Cookie 已从 .env 重新加载', data: { qqCookieKeys: Object.keys(config.qq).length } });
    } else {
      res.status(500).json({ code: 500, message: '重载失败，请检查 .env 文件' });
    }
  });

  // Cookie 状态查询端点
  app.get('/cookie-status', (req, res) => {
    const qqKeys = Object.keys(config.qq).length;
    res.json({
      code: 200,
      data: { ...cookie.cookieStatus, qqCookieKeys: qqKeys },
      tip: cookie.cookieStatus.qq ? null : 'Cookie 过期，请运行: node scripts/get_qq_cookie.mjs',
      timestamp: new Date().toISOString(),
    });
  });

  // ==================== 兼容旧 API 路由 (不变) ====================

  app.get('/lyric', async (req, res) => {
    try {
      const { id } = req.query;
      if (!id) return res.status(400).json({ code: 400, message: 'id is required' });
      if (!/^\d{4,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await NeteaseCloudMusicApi.lyric(cookie.withNeteaseCookie({ id }));
      res.json(result.body);
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  app.get('/cloudsearch', async (req, res) => {
    try {
      const { keywords, limit = 20, type = 1 } = req.query;
      if (!keywords) return res.status(400).json({ code: 400, message: '缺少 keywords 参数' });
      const result = await NeteaseCloudMusicApi.cloudsearch(cookie.withNeteaseCookie({ keywords, limit, type }));
      res.json(result.body);
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  app.get('/song/url/v1', async (req, res) => {
    try {
      const { id, level = 'exhigh' } = req.query;
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^\d{4,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await NeteaseCloudMusicApi.song_url_v1(cookie.withNeteaseCookie({ id, level }));
      res.json(result.body);
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  app.get('/song/detail', async (req, res) => {
    try {
      const { ids } = req.query;
      if (!ids) return res.status(400).json({ code: 400, message: '缺少 ids 参数' });
      const result = await NeteaseCloudMusicApi.song_detail(cookie.withNeteaseCookie({ ids }));
      res.json(result.body);
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  app.get('/personalized', async (req, res) => {
    try {
      const { limit = 10 } = req.query;
      const result = await NeteaseCloudMusicApi.personalized(cookie.withNeteaseCookie({ limit }));
      res.json(result.body);
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  // ==================== 独立平台搜索 ====================

  app.get('/netease/search', async (req, res) => {
    try {
      const { keyword, limit = 20 } = req.query;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      const songs = await searchNetease(keyword, parseInt(limit));
      res.json({ code: 200, data: songs });
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message, data: [] });
    }
  });

  app.get('/qq/search', async (req, res) => {
    try {
      const { keyword, limit = 20 } = req.query;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      const songs = await searchQQ(keyword, parseInt(limit));
      res.json({ code: 200, data: songs });
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message, data: [] });
    }
  });

  // ==================== QQ音乐URL (缓存) ====================

  app.get('/song/url/qq', urlLimiter, async (req, res) => {
    try {
      const { id } = req.query;
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^[a-zA-Z0-9]{10,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const cacheKey = `qq_url:${id}`;
      const cached = urlCache.get(cacheKey);
      if (cached) return res.json({ code: 200, data: cached });

      // 直接调 QQ API，带上完整 Cookie 头（2026-08: 仅 authst 参数已被服务端忽略，必须携带 Cookie 才能拿到 purl）
      const uin = config.qq.uin || '0';
      const qqmusicKey = config.qq.qqmusic_key || '';
      const qqCookieStr = cookie.getQQCookieString();
      const reqData = JSON.stringify({
        req_0: {
          module: 'vkey.GetVkeyServer', method: 'CgiGetVkey',
          param: {
            filename: [`M800${id}.mp3`, `M500${id}.mp3`, `C400${id}.m4a`],
            guid: '126548448', songmid: [id], songtype: [0],
            uin, loginflag: 1, platform: '20', authst: qqmusicKey,
          },
        },
        comm: { uin, format: 'json', ct: 19, cv: 0 },
      });
      const qqResp = await axios.get('https://u.y.qq.com/cgi-bin/musicu.fcg', {
        params: { format: 'json', data: reqData },
        headers: {
          Referer: 'https://y.qq.com',
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
          Cookie: qqCookieStr,
        },
        timeout: UPSTREAM_TIMEOUT,
      });
      const mi = qqResp.data?.req_0?.data?.midurlinfo;
      const sip = qqResp.data?.req_0?.data?.sip?.[0] || 'https://aqqmusic.tc.qq.com';

      // vkey URL 有时效且部分歌曲 CDN 已失效，逐个音质探测 CDN 可用性，取第一个可访问的
      let url = null;
      if (mi) {
        for (const m of mi) {
          if (!m || !m.purl) continue;
          const candidate = m.purl.includes('://') ? m.purl : sip + '/' + m.purl;
          try {
            const probe = await axios.get(candidate, {
              headers: { 'User-Agent': 'Mozilla/5.0', Range: 'bytes=0-1023' },
              timeout: 2500,
              validateStatus: () => true,
              maxRedirects: 0,
            });
            if (probe.status === 200 || probe.status === 206) { url = candidate; break; }
            writeLog('api', 'INFO', `[/song/url/qq] ${id} ${m.filename} CDN ${probe.status} 失效, 尝试下一音质`);
          } catch (e) {
            writeLog('api', 'INFO', `[/song/url/qq] ${id} ${m.filename} 探测异常: ${e.message}`);
          }
        }
      }
      const data = [{ id, url }];
      urlCache.set(cacheKey, data);
      res.json({ code: 200, data });
    } catch (error) {
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  // ==================== 通用代理路由（限制已知方法） ====================

  // 允许的网易云 API 方法白名单（从 config 读取）
  const ALLOWED_NETEASE_APIS = new Set(config.neteaseApis);

  app.all('/netease/*', async (req, res) => {
    try {
      const apiPath = req.path.replace('/netease/', '');
      const apiName = apiPath.replace(/\//g, '_');
      if (!ALLOWED_NETEASE_APIS.has(apiName)) {
        return res.status(403).json({ code: 403, message: `API ${apiName} not allowed` });
      }
      const params = cookie.withNeteaseCookie({ ...req.query, ...req.body });
      if (typeof NeteaseCloudMusicApi[apiName] === 'function') {
        const result = await NeteaseCloudMusicApi[apiName](params);
        res.json(result.body);
      } else {
        res.status(404).json({ code: 404, message: `API ${apiName} not found` });
      }
    } catch (error) {
      writeLog('api', 'ERROR', `[/netease/*] ${error.message}`);
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  // ==================== QQ 歌词 ====================

  app.get('/qq/lyric', async (req, res) => {
    try {
      const { songmid } = req.query;
      if (!songmid) return res.status(400).json({ code: 400, message: '缺少 songmid 参数' });
      if (!/^[a-zA-Z0-9]{10,20}$/.test(songmid)) return res.status(400).json({ code: 400, message: 'invalid songmid format' });

      // 复用 /song/url/qq 的 Cookie 构建方式（2026-08: 必须携带 Cookie 才能拿到歌词）
      const uin = config.qq.uin || '0';
      const qqCookieStr = cookie.getQQCookieString();
      const reqData = JSON.stringify({
        req_0: {
          module: 'music.musichallSong.PlayLyricInfo',
          method: 'GetPlayLyricInfo',
          param: { songMID: songmid, songID: 0, songType: 0, needNewOffset: 1 },
        },
        comm: { uin, format: 'json', ct: 19, cv: 0 },
      });
      const qqResp = await axios.get('https://u.y.qq.com/cgi-bin/musicu.fcg', {
        params: { format: 'json', data: reqData },
        headers: {
          Referer: 'https://y.qq.com',
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
          Cookie: qqCookieStr,
        },
        timeout: UPSTREAM_TIMEOUT,
      });
      const ly = qqResp.data?.req_0?.data?.lyric;
      if (!ly) return res.status(404).json({ code: 404, message: '未找到歌词' });
      // QQ 返回 base64 编码的 LRC 文本
      const lrc = Buffer.from(ly, 'base64').toString('utf8');
      res.json({ code: 200, data: { lyric: lrc } });
    } catch (error) {
      writeLog('api', 'ERROR', `[/qq/lyric] ${error.message}`);
      res.status(500).json({ code: 500, message: error.message });
    }
  });

  // ==================== QQ 歌单详情 ====================

  app.get('/qq/playlist', async (req, res) => {
    try {
      const { id } = req.query;
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^\d{3,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await qqMusic.api('/songlist', { disstid: id });
      // qq-music-api 返回格式: { code, data: { cdlist: [...] } }
      const pl = result?.data?.cdlist?.[0] || result?.cdlist?.[0];
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
      res.status(500).json({ code: 500, message: e.message });
    }
  });
}

module.exports = registerRoutes;