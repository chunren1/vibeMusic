// ==================== 全部路由（保持原注册顺序） ====================
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const axios = require('axios');
const qqMusic = require('qq-music-api');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');

const config = require('./config-loader');
const cookie = require('./cookie');
const { writeLog } = require('./logger');
const { canRefreshCookie, clientIp } = require('./access');
const { cacheHitTotal, cookieStatusGauge } = require('./metrics');
const {
  SCORE_CFG,
  calculateScore,
  generateFingerprint,
  isSameSong,
  paginate,
  refineResults,
} = require('./scoring');
const { searchNetease, searchQQ, searchMigu, getMiguUrl, searchKugou, getKugouPlayData, getKugouUrl, searchBili, getBiliUrl, isBiliTrackId, searchCache, urlCache, UPSTREAM_TIMEOUT, withTimeout } = require('./search');
const { searchLimiter, urlLimiter } = require('./rate-limiters');

// 500 统一话术：细节只进 writeLog，不回传调用方（与全局错误处理器口径一致）
const GENERIC_500 = '服务繁忙，请稍后重试';

// Express 4 默认 query parser 下 ?k=a&k=b 解析为数组；?k[x]=1 解析为对象。
// 统一取首个标量，缺失/非法由各路由判 400（此前数组直接 .trim() 抛 TypeError → 500 泄漏堆栈文本）。
function firstQuery(v) {
  if (Array.isArray(v)) return v.length > 0 ? v[0] : undefined;
  return v;
}
function queryStr(v) {
  const first = firstQuery(v);
  return typeof first === 'string' ? first : undefined;
}

// ==================== /netease/* 通配路由防护（API-C1） ====================
// 上游 NeteaseCloudMusicApi 的 createOption 会消费 domain/proxy/realIP/ua/crypto/e_r/cookie
// 等保留键：调用方传入 ?domain=http://attacker 即可把携带 MUSIC_U 真实凭证的请求发往
// 任意主机（SSRF + 凭证外发，审计已动态复现）。此处默认拒绝：只放行白名单内的标量参数，
// 服务端 Cookie 仍由 withNeteaseCookie 统一注入；响应体递归剥离凭证字段后再返回。
const NETEASE_PARAM_ALLOWLIST = new Set([
  'keywords', 'keyword', 'limit', 'offset', 'type', 'id', 'ids',
  'level', 'uid', 'idx',
]);
// 保留键按小写比对，拦截 Cookie/Domain/Proxy 等大小写变体
const NETEASE_RESERVED_KEYS = new Set([
  'cookie', 'domain', 'proxy', 'realip', 'real_ip', 'ip', 'ua',
  'crypto', 'e_r', 'url', 'host', 'headers', 'agent',
  'httpagent', 'httpsagent', 'socket', 'method',
]);
const NETEASE_MAX_STR_LEN = 2000;

function sanitizeNeteaseParams(query = {}, body = {}) {
  const merged = { ...(query || {}), ...(body || {}) };
  const out = {};
  for (const [k, v] of Object.entries(merged)) {
    if (NETEASE_RESERVED_KEYS.has(String(k).toLowerCase())) continue;
    if (!NETEASE_PARAM_ALLOWLIST.has(k)) continue;
    if (typeof v === 'string') {
      if (v.length === 0 || v.length > NETEASE_MAX_STR_LEN) continue;
      out[k] = v;
    } else if (typeof v === 'number' && Number.isFinite(v)) {
      out[k] = v;
    } else if (typeof v === 'boolean') {
      out[k] = v;
    }
    // 数组/对象一律丢弃：上游库不期望复合参数，且可绕过标量校验
  }
  return out;
}

// 上游响应可能回显登录态（cookie/MUSIC_U/MUSIC_A/__csrf），递归剥离后再返回调用方
const SECRET_KEY_RE = /^(cookie|music_u|music_a|__csrf|csrf|csrf_token|csrftoken)$/i;

function scrubSecrets(value, depth = 0) {
  if (depth > 10 || value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) value[i] = scrubSecrets(value[i], depth + 1);
    return value;
  }
  for (const k of Object.keys(value)) {
    if (SECRET_KEY_RE.test(k)) { delete value[k]; continue; }
    value[k] = scrubSecrets(value[k], depth + 1);
  }
  return value;
}

// /refresh-qq-cookie 日志脱敏：只记长度 + 失败时 scrubbed stderr 尾部（≤200 字符）。
// 遵循 cookie.js「日志只打长度、绝不打值」规范；凭证原文绝不进日志文件。
function scrubCookieValues(s) {
  return String(s).replace(
    /\b(MUSIC_U|qqmusic_key|qm_keyst|uin|psrf_qqunionid|psrf_qqrefresh_token)=[^;\s]+/gi, '$1=***');
}

// /search 内部字段：绝不进缓存、不出响应。higherQuality 曾存对象引用，
// 等时长自赋值即成循环引用（JSON 序列化抛错 → 500，且毒化缓存）。
// 如今只存标量时长提示，此处做递归兜底剥离（含历史毒化条目与嵌套）。
const SEARCH_INTERNAL_FIELDS = new Set(['higherQuality', 'higherQualityDuration', '_raw', 'finalScore']);

function stripSearchInternals(value, seen = new Set()) {
  if (value === null || typeof value !== 'object') return value;
  if (seen.has(value)) return undefined;
  seen.add(value);
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      const cleaned = stripSearchInternals(value[i], seen);
      if (cleaned === undefined) { value.splice(i--, 1); }
      else value[i] = cleaned;
    }
    return value;
  }
  for (const k of Object.keys(value)) {
    if (SEARCH_INTERNAL_FIELDS.has(k)) { delete value[k]; continue; }
    const cleaned = stripSearchInternals(value[k], seen);
    if (cleaned === undefined) delete value[k];
    else value[k] = cleaned;
  }
  return value;
}

// G1：/search 缓存键的 Cookie 派生维度。userCk 经 resolveNeteaseCookie 归一
// （per-request 用户 Cookie > 共享 NETEASE_COOKIE > ''）；ckDim 只存 sha256
// 摘要前 16 字符，原文与完整摘要绝不进日志/指标；无 Cookie 落 'anon' 共享桶。
function searchCookieDim(req) {
  const userCk = cookie.resolveNeteaseCookie(req);
  if (!userCk) return 'anon';
  return crypto.createHash('sha256').update(userCk).digest('hex').slice(0, 16);
}

function buildSearchCacheKey(kw, maxRank, safePrefer, req) {
  return `search:${kw}:${maxRank}:${safePrefer ?? 'none'}:${searchCookieDim(req)}`;
}

// QQ 上游请求头：Cookie 为可选项，仅在已配置时携带；无 Cookie 直接请求，服务端按无登录态返回
function qqRequestHeaders() {
  const headers = {
    Referer: 'https://y.qq.com',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  };
  const cookieStr = cookie.getQQCookieString();
  if (cookieStr) headers.Cookie = cookieStr;
  return headers;
}

// QQ 播放地址：无 Cookie 可用；VIP/鉴权限制内容返回 null（同形），任何异常都降级为 null，永不抛错
async function fetchQQPlayUrl(id) {
  try {
    const uin = (config.qq && config.qq.uin) || '0';
    const qqmusicKey = (config.qq && config.qq.qqmusic_key) || '';
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
      headers: qqRequestHeaders(),
      timeout: UPSTREAM_TIMEOUT,
    });
    const mi = qqResp.data?.req_0?.data?.midurlinfo;
    const sip = qqResp.data?.req_0?.data?.sip?.[0] || 'https://aqqmusic.tc.qq.com';

    // vkey URL 有时效且部分歌曲 CDN 已失效：候选音质并行探测，取第一个可访问的
    // （此前串行逐个探测，最坏 3×2.5s 叠加；Promise.any 任一命中即返回）
    if (mi) {
      const candidates = [];
      for (const m of mi) {
        if (!m || !m.purl) continue;
        candidates.push(m.purl.includes('://') ? m.purl : sip + '/' + m.purl);
      }
      if (candidates.length > 0) {
        const probeOne = async (candidate) => {
          try {
            const probe = await axios.get(candidate, {
              headers: { 'User-Agent': 'Mozilla/5.0', Range: 'bytes=0-1023' },
              timeout: 2500,
              validateStatus: () => true,
              maxRedirects: 0,
            });
            if (probe.status === 200 || probe.status === 206) return candidate;
            writeLog('api', 'INFO', `[/song/url/qq] ${id} CDN ${probe.status} 失效: ${candidate.slice(0, 80)}`);
            throw new Error(`CDN probe ${probe.status}`);
          } catch (e) {
            if (e.message && e.message.startsWith('CDN probe')) throw e;
            writeLog('api', 'INFO', `[/song/url/qq] ${id} 探测异常: ${e.message}`);
            throw e;
          }
        };
        try {
          return await Promise.any(candidates.map(probeOne));
        } catch (e) {
          writeLog('api', 'INFO', `[/song/url/qq] ${id} 全部音质 CDN 探测失败，降级(null)`);
        }
      }
    }
    return null;
  } catch (error) {
    writeLog('api', 'INFO', `[/song/url/qq] ${id} 取链降级(null): ${error.message}`);
    return null;
  }
}

// QQ 歌词：无 Cookie 可用；缺歌词/异常返回 null，永不抛错
async function fetchQQLyric(songmid) {
  try {
    const uin = (config.qq && config.qq.uin) || '0';
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
      headers: qqRequestHeaders(),
      timeout: UPSTREAM_TIMEOUT,
    });
    const ly = qqResp.data?.req_0?.data?.lyric;
    if (!ly) return null;
    // QQ 返回 base64 编码的 LRC 文本
    return Buffer.from(ly, 'base64').toString('utf8');
  } catch (error) {
    writeLog('api', 'INFO', `[/qq/lyric] ${songmid} 歌词降级(空): ${error.message}`);
    return null;
  }
}

function registerRoutes(app) {
  // ==================== 聚合搜索主入口 ====================

  /**
   * GET /search?keyword=xxx&page=1&size=20&prefer=netease
   */
  app.get('/search', searchLimiter, async (req, res) => {
    try {
      const keywordRaw = queryStr(req.query.keyword);
      const page = firstQuery(req.query.page) ?? 1;
      const size = firstQuery(req.query.size) ?? 20;
      const preferRaw = queryStr(req.query.prefer);
      const maxRank = 30; // 各平台获取的最大条数

      if (keywordRaw === undefined || keywordRaw.trim() === '') {
        return res.status(400).json({ code: 400, message: 'keyword is required', data: null });
      }
      if (keywordRaw.length > 100) {
        return res.status(400).json({ code: 400, message: 'keyword too long (max 100 chars)', data: null });
      }
      // size 上限 100，防内存溢出
      const safeSize = Math.min(Math.max(1, parseInt(size) || 20), 100);
      // prefer 枚举白名单
      const validPrefers = ['netease', 'qq'];
      const safePrefer = validPrefers.includes(preferRaw) ? preferRaw : undefined;

      const kw = keywordRaw.trim();

      // 缓存键含 prefer + Cookie 派生维度：排序受偏好加成影响，不同偏好必须隔离；
      // 网易策略链头两条消费 per-request 用户 Cookie，同词不同用户也必须隔离（G1），
      // 匿名桶（anon）仍共享以保留命中率。
      const cacheKey = buildSearchCacheKey(kw, maxRank, safePrefer, req);
      const cached = searchCache.get(cacheKey);
      if (cached !== undefined) {
        cacheHitTotal.inc({ cache_type: 'search' });
        writeLog('access', 'INFO', `[Cache] HIT for "${kw}"`);
        stripSearchInternals(cached);
        const pageData = paginate(cached, page, safeSize);
        return res.json({ code: 200, message: 'success (cached)', data: pageData });
      }

      // ---- 并行请求上游 ----
      const [neteaseSongs, qqSongs] = await Promise.all([
        searchNetease(kw, maxRank, req),
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
            // 标量质量提示：只记较长时长，绝不存对象引用（等时长自赋值曾致循环引用 → 500）
            existing.higherQualityDuration = Math.max(song.duration || 0, existing.duration || 0);
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

      // ---- 清理内部字段后再缓存（空结果不入库：上游双失败的空数组不值得缓存，
      // 否则上游恢复后仍长期命中 stale 空结果） ----
      stripSearchInternals(refined);
      if (refined.length > 0) {
        searchCache.set(cacheKey, refined);
      }

      // ---- 分页 ----
      const pageData = paginate(refined, page, safeSize);

      res.json({ code: 200, message: 'success', data: pageData });
    } catch (error) {
      writeLog('api', 'ERROR', `[/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: null });
    }
  });

  // ==================== Cookie 手动恢复端点 ====================

  // GET /refresh-qq-cookie — 手动触发浏览器提取 Cookie。
  // 访问控制：管理令牌（?token=/x-admin-token/Bearer）或未配令牌时的本机调用；其余 403。
  // 只返回成功/失败 + 退出码，脚本明细只进服务端日志（不再回传 stdout，防 cookie 前缀外泄）。
  // 镜像内无 scripts/ 时明确 501（Dockerfile 只 COPY server.js/src，宿主机挂载场景才可用）。
  app.get('/refresh-qq-cookie', async (req, res) => {
    if (!canRefreshCookie(req)) {
      writeLog('access', 'WARN', `[/refresh-qq-cookie] 拒绝非授权访问: ${clientIp(req)}`);
      return res.status(403).json({ code: 403, message: 'Forbidden', data: null });
    }
    const scriptPath = path.resolve(__dirname, '..', '..', 'scripts', 'get_qq_cookie.mjs');
    if (!fs.existsSync(scriptPath)) {
      return res.status(501).json({ code: 501, message: 'Cookie 提取脚本在当前镜像中不可用，仅宿主机部署支持', data: null });
    }

    const child = spawn('node', [scriptPath], {
      cwd: path.resolve(__dirname, '..', '..'),
      timeout: 120000,
      env: process.env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });

    child.on('close', async (code) => {
      writeLog('cookie', code === 0 ? 'INFO' : 'ERROR',
        `[/refresh-qq-cookie] 退出码=${code}, outLen=${stdout.length}, errLen=${stderr.length}` +
        (code === 0 ? '' : `, 尾部: ${scrubCookieValues(stderr).slice(-200)}`));
      if (code === 0 && cookie.reloadQQCookie()) {
        cookie.cookieStatus.qq = true;
        cookieStatusGauge.set({ platform: 'qq' }, 1);
        return res.json({ code: 200, message: 'Cookie 提取成功，已自动加载', data: { exitCode: code } });
      }
      return res.status(500).json({ code: 500, message: GENERIC_500, data: { exitCode: code } });
    });

    child.on('error', (err) => {
      writeLog('cookie', 'ERROR', `[/refresh-qq-cookie] 无法启动浏览器: ${err.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: null });
    });
  });

  // Cookie 状态查询端点（同门禁：响应含 qqCookieKeys 计数，匿名不可见）
  app.get('/cookie-status', (req, res) => {
    if (!canRefreshCookie(req)) {
      writeLog('access', 'WARN', `[/cookie-status] 拒绝非授权访问: ${clientIp(req)}`);
      return res.status(403).json({ code: 403, message: 'Forbidden', data: null });
    }
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
      const id = queryStr(req.query.id);
      if (!id) return res.status(400).json({ code: 400, message: 'id is required' });
      if (!/^\d{4,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await withTimeout(
        NeteaseCloudMusicApi.lyric(cookie.withNeteaseCookie({ id }, req)),
        UPSTREAM_TIMEOUT, 'netease/lyric'
      );
      res.json(result.body);
    } catch (error) {
      writeLog('api', 'ERROR', `[/lyric] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });

  app.get('/song/url/v1', async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      const level = queryStr(req.query.level) ?? 'exhigh';
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^\d{4,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await withTimeout(
        NeteaseCloudMusicApi.song_url_v1(cookie.withNeteaseCookie({ id, level }, req)),
        UPSTREAM_TIMEOUT, 'netease/song_url_v1'
      );
      res.json(result.body);
    } catch (error) {
      writeLog('api', 'ERROR', `[/song/url/v1] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });

  app.get('/personalized', async (req, res) => {
    try {
      const limit = firstQuery(req.query.limit) ?? 10;
      const result = await withTimeout(
        NeteaseCloudMusicApi.personalized(cookie.withNeteaseCookie({ limit }, req)),
        UPSTREAM_TIMEOUT, 'netease/personalized'
      );
      res.json(result.body);
    } catch (error) {
      writeLog('api', 'ERROR', `[/personalized] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });

  // ==================== 独立平台搜索 ====================

  app.get('/netease/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      // searchNetease 内部已有 SEARCH_TOTAL_BUDGET 总预算（12s 降级为空），此处不再叠加外层超时
      const songs = await searchNetease(keyword, parseInt(limit), req);
      res.json({ code: 200, data: songs });
    } catch (error) {
      writeLog('api', 'ERROR', `[/netease/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: [] });
    }
  });

  app.get('/qq/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      const songs = await searchQQ(keyword, parseInt(limit));
      res.json({ code: 200, data: songs });
    } catch (error) {
      writeLog('api', 'ERROR', `[/qq/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: [] });
    }
  });

  app.get('/migu/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      const songs = await searchMigu(keyword, parseInt(limit));
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
      const songs = await searchKugou(keyword, safeLimit);
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
      const songs = await searchBili(keyword, safeLimit);
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
      const params = cookie.withNeteaseCookie(sanitizeNeteaseParams(req.query, req.body), req);
      if (typeof NeteaseCloudMusicApi[apiName] === 'function') {
        const result = await withTimeout(
          NeteaseCloudMusicApi[apiName](params),
          UPSTREAM_TIMEOUT, `netease/${apiName}`
        );
        res.json(scrubSecrets(result.body));
      } else {
        res.status(404).json({ code: 404, message: `API ${apiName} not found` });
      }
    } catch (error) {
      writeLog('api', 'ERROR', `[/netease/*] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
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

  // ==================== 酷狗歌词 ====================
  // 复用 getdata 响应内联 lyrics 字段(与封面 img 同响应)；缺歌词/异常 404(与 /qq/lyric 同形)

  app.get('/kugou/lyric', async (req, res) => {
    try {
      const hash = queryStr(req.query.hash);
      const albumId = queryStr(req.query.albumId) ?? queryStr(req.query.album_id) ?? '';
      if (!hash) return res.status(400).json({ code: 400, message: '缺少 hash 参数' });
      if (!/^[a-f0-9]{32}$/i.test(hash)) return res.status(400).json({ code: 400, message: 'invalid hash format' });
      if (albumId && !/^\d{1,20}$/.test(albumId)) return res.status(400).json({ code: 400, message: 'invalid albumId format' });

      const data = await getKugouPlayData(hash, albumId);
      const lrc = data && data.lyrics;
      if (!lrc) return res.status(404).json({ code: 404, message: '未找到歌词' });
      res.json({ code: 200, message: 'success', data: { lyric: lrc } });
    } catch (error) {
      // 兜底：任何意外异常也以干净空形返回，绝不 500
      writeLog('api', 'INFO', `[/kugou/lyric] 兜底降级: ${error.message}`);
      res.status(404).json({ code: 404, message: '未找到歌词' });
    }
  });

  // ==================== QQ 歌单详情 ====================

  app.get('/qq/playlist', async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^\d{3,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await withTimeout(
        qqMusic.api('/songlist', { disstid: id }),
        UPSTREAM_TIMEOUT, 'qq/playlist'
      );
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
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });
}

module.exports = registerRoutes;
module.exports.sanitizeNeteaseParams = sanitizeNeteaseParams;
module.exports.scrubSecrets = scrubSecrets;
module.exports.scrubCookieValues = scrubCookieValues;
module.exports.buildSearchCacheKey = buildSearchCacheKey;
module.exports.searchCookieDim = searchCookieDim;
module.exports.qqRequestHeaders = qqRequestHeaders;
module.exports.stripSearchInternals = stripSearchInternals;
module.exports.fetchQQPlayUrl = fetchQQPlayUrl;
module.exports.fetchQQLyric = fetchQQLyric;