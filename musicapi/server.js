// 加载项目根目录 .env（Cookie/密钥等）
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });

const express = require('express');
const cors = require('cors');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const qqMusic = require('qq-music-api');
const axios = require('axios');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const promClient = require('prom-client');
const { LRUCache } = require('lru-cache');

const app = express();
const PORT = 3000;

// ==================== 速率限制（防滥用/爬取） ====================
const rateLimit = require('express-rate-limit');

const globalLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 100,
  standardHeaders: true,
  legacyHeaders: false,
  message: { code: 429, message: '请求过于频繁，请稍后再试', data: null },
});

const searchLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { code: 429, message: '搜索请求过于频繁，请稍后再试', data: null },
});

const urlLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { code: 429, message: '请求过于频繁，请稍后再试', data: null },
});

// ==================== 日志系统（按天轮转 + 30天自动清理） ====================
const LOG_DIR = path.join(__dirname, 'logs');
if (!fs.existsSync(LOG_DIR)) fs.mkdirSync(LOG_DIR, { recursive: true });

class LogManager {
  constructor(retentionDays = 30) {
    this.retentionDays = retentionDays;
    this.streams = {};
    this.currentDate = '';
    this._rotate();
    this._scheduleMidnightRotation();
  }

  /** 获取当前日期字符串 YYYY-MM-DD */
  _getDate() {
    return new Date().toISOString().slice(0, 10);
  }

  /** 按需轮转：日期变了就切文件 */
  _rotate() {
    const date = this._getDate();
    if (date === this.currentDate) return;
    this.currentDate = date;

    // 关闭旧流
    for (const key of Object.keys(this.streams)) {
      try { this.streams[key].end(); } catch (e) { /* ignore */ }
    }
    this.streams = {};

    // 打开新流（带日期后缀）
    for (const name of ['api-errors', 'cookie-monitor', 'degradation', 'access']) {
      const filePath = path.join(LOG_DIR, `${name}.${date}.log`);
      this.streams[name] = fs.createWriteStream(filePath, { flags: 'a' });
    }

    this._cleanupOldLogs();
  }

  /** 调度午夜轮转（精确到次日 00:00） */
  _scheduleMidnightRotation() {
    const now = new Date();
    const msToMidnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 0) - now;
    setTimeout(() => {
      this._rotate();
      setInterval(() => this._rotate(), 24 * 60 * 60 * 1000);
    }, msToMidnight);
  }

  /** 删除 retentionDays 天前的日志文件 */
  _cleanupOldLogs() {
    const cutoff = Date.now() - this.retentionDays * 24 * 60 * 60 * 1000;
    try {
      const files = fs.readdirSync(LOG_DIR);
      for (const file of files) {
        // 匹配 date-suffixed 日志：xxx.2026-06-01.log
        if (/^.+\.[12]\d{3}-\d{2}-\d{2}\.log$/.test(file)) {
          const filePath = path.join(LOG_DIR, file);
          const stat = fs.statSync(filePath);
          if (stat.mtimeMs < cutoff) {
            fs.unlinkSync(filePath);
          }
        }
      }
    } catch (e) {
      console.error('[LogManager] 清理过期日志失败:', e.message);
    }
  }

  write(category, level, message) {
    this._rotate(); // 按需轮转
    const stream = this.streams[category];
    if (stream) {
      const timestamp = new Date().toISOString();
      const line = `[${timestamp}] [${level}] ${message}\n`;
      // 使用异步写入避免阻塞事件循环
      fs.appendFile(path.join(LOG_DIR, `${category}.${this.currentDate}.log`), line, () => {});
    }
  }
}

const logManager = new LogManager(30);

function writeLog(category, level, message) {
  logManager.write(category, level, message);
  if (category === 'api' || category === 'cookie') console.log(`[${new Date().toISOString()}] [${level}] ${message}`);
}

// ==================== 中间件 ====================
app.use(cors({
  origin: process.env.CORS_ORIGIN || 'http://localhost:5173',
  credentials: true
}));
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: true, limit: '1mb' }));
app.use(globalLimiter);  // 全局限流

// 访问日志中间件
app.use((req, res, next) => {
  // 跳过静态资源请求的日志
  if (!req.url.startsWith('/favicon')) {
    writeLog('access', 'INFO', `${req.method} ${req.originalUrl}`);
  }
  next();
});

// ==================== Prometheus 监控指标 ====================
const register = new promClient.Registry();
promClient.collectDefaultMetrics({ register, prefix: 'musicapi_' });

// 自定义指标
const httpRequestTotal = new promClient.Counter({
  name: 'musicapi_http_requests_total',
  help: 'HTTP 请求总数',
  labelNames: ['method', 'path', 'status'],
  registers: [register],
});

const httpRequestDuration = new promClient.Histogram({
  name: 'musicapi_http_request_duration_seconds',
  help: 'HTTP 请求耗时（秒）',
  labelNames: ['method', 'path'],
  buckets: [0.01, 0.05, 0.1, 0.3, 0.5, 1, 3, 5, 10],
  registers: [register],
});

const cacheHitTotal = new promClient.Counter({
  name: 'musicapi_cache_hits_total',
  help: '缓存命中次数',
  labelNames: ['cache_type'],
  registers: [register],
});

const cookieStatusGauge = new promClient.Gauge({
  name: 'musicapi_cookie_status',
  help: 'Cookie 状态 (1=正常, 0=降级)',
  labelNames: ['platform'],
  registers: [register],
});

const upGauge = new promClient.Gauge({
  name: 'musicapi_up',
  help: '服务存活 (1=运行中)',
  registers: [register],
});
upGauge.set(1);

// Prometheus HTTP 指标中间件（记录请求耗时和数量）
app.use((req, res, next) => {
  const pathLabel = req.route ? req.route.path : req.path;
  const end = httpRequestDuration.startTimer({ method: req.method, path: pathLabel });
  res.on('finish', () => {
    end();
    httpRequestTotal.inc({ method: req.method, path: pathLabel, status: res.statusCode });
  });
  next();
});

// 根路由（健康检查）
app.get('/', (req, res) => {
  res.json({ service: 'vibeMusic API', version: '3.0', status: 'running', endpoints: ['/netease/search', '/qq/search', '/lyric', '/personalized', '/cookie-status', '/health'] });
});

app.get('/health', (req, res) => {
  res.json({
    code: 200,
    message: 'Music API Service v3 (Unified Cookie + SLA)',
    data: {
      netease: cookieStatus.netease ? 'available' : 'degraded',
      qq: cookieStatus.qq ? 'available' : 'degraded',
      cacheSize: searchCache.size,
      uptime: process.uptime(),
      timestamp: new Date().toISOString(),
    },
  });
});

// Prometheus 指标暴露端点
app.get('/metrics', async (req, res) => {
  try {
    res.set('Content-Type', register.contentType);
    res.end(await register.metrics());
  } catch (err) {
    res.status(500).end(err.message);
  }
});

// favicon 占位（避免 404 日志）
app.get('/favicon.ico', (req, res) => res.status(204).end());

// ==================== Cookie 统一管理 ====================
const config = require('./config.js');

// QQ音乐 Cookie（进程级全局设置）
qqMusic.setCookie(config.qq);
writeLog('cookie', 'INFO', 'QQ音乐 Cookie 已加载');

// 网易云 Cookie（注入到每次 API 调用的请求参数中）
const NETEASE_COOKIE = config.netease;
writeLog('cookie', 'INFO', `网易云 Cookie 已加载 (长度: ${NETEASE_COOKIE ? NETEASE_COOKIE.length : 0})`);

/** 给网易云 API 参数注入 cookie */
function withNeteaseCookie(extra = {}) {
  return { ...extra, cookie: NETEASE_COOKIE };
}

// ==================== Cookie 存活监控（随 API 启动自动运行） ====================
let cookieStatus = { netease: true, qq: true };
// Prometheus gauge 初始值：假设 Cookie 可用，checkCookies 会更新为实际值
cookieStatusGauge.set({ platform: 'netease' }, 1);
cookieStatusGauge.set({ platform: 'qq' }, 1);

async function checkCookies() {
  writeLog('cookie', 'INFO', '🔄 Cookie 存活检查开始...');
  
  // 检查网易云
  try {
    const neRes = await NeteaseCloudMusicApi.cloudsearch(withNeteaseCookie({ keywords: '周杰伦', limit: 1, type: 1 }));
    if (neRes.body.code === 200 && neRes.body.result) {
      cookieStatus.netease = true;
      cookieStatusGauge.set({ platform: 'netease' }, 1);
      writeLog('cookie', 'INFO', '✅ 网易云 Cookie 正常');
    } else {
      cookieStatus.netease = false;
      cookieStatusGauge.set({ platform: 'netease' }, 0);
      writeLog('cookie', 'ERROR', `❌ 网易云 Cookie 异常: ${JSON.stringify(neRes.body).slice(0, 200)}`);
    }
  } catch (e) {
    cookieStatus.netease = false;
    cookieStatusGauge.set({ platform: 'netease' }, 0);
    writeLog('cookie', 'ERROR', `❌ 网易云 Cookie 检查失败: ${e.message}`);
  }

  // 检查QQ（含自动恢复）
  await checkQQCookie();
}

// ==================== QQ Cookie 检查 + 手动恢复引导 ====================

async function checkQQCookie() {
  try {
    const qqRes = await qqMusic.api('search', { key: '周杰伦', limit: 1 });
    if (qqRes && qqRes.list && qqRes.list.length > 0) {
      cookieStatus.qq = true;
      cookieStatusGauge.set({ platform: 'qq' }, 1);
      writeLog('cookie', 'INFO', '✅ QQ音乐 Cookie 正常');
      return;
    }
    failQQCookie('无搜索结果');
  } catch (e) {
    failQQCookie(e.message);
  }
}

function failQQCookie(reason) {
  cookieStatus.qq = false;
  cookieStatusGauge.set({ platform: 'qq' }, 0);
  writeLog('cookie', 'ERROR', `❌ QQ音乐 Cookie 异常: ${reason}`);
  writeLog('cookie', 'WARN', '💡 请在终端运行: node scripts/get_qq_cookie.mjs  或调用 GET /refresh-qq-cookie');
}

// GET /refresh-qq-cookie — 手动触发浏览器提取 Cookie（需有桌面环境）
app.get('/refresh-qq-cookie', async (req, res) => {
  res.setHeader('Content-Type', 'text/plain; charset=utf-8');
  const { spawn } = require('child_process');
  const scriptPath = path.resolve(__dirname, '..', 'scripts', 'get_qq_cookie.mjs');

  res.write('⏳ 正在打开浏览器提取 QQ Cookie...\n');

  const child = spawn('node', [scriptPath], {
    cwd: path.resolve(__dirname, '..'),
    timeout: 120000,
    env: process.env,
    // 关键：继承父进程 stdio，让 Playwright 有终端上下文可以打开浏览器
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let stdout = '';
  child.stdout.on('data', (d) => { stdout += d.toString(); res.write(d); });
  child.stderr.on('data', (d) => { stdout += d.toString(); });

  child.on('close', async (code) => {
    if (code === 0 && reloadQQCookie()) {
      cookieStatus.qq = true;
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
  if (reloadQQCookie()) {
    res.json({ code: 200, message: 'Cookie 已从 .env 重新加载', data: { qqCookieKeys: Object.keys(config.qq).length } });
  } else {
    res.status(500).json({ code: 500, message: '重载失败，请检查 .env 文件' });
  }
});

function reloadQQCookie() {
  try {
    const fs = require('fs');
    const envPath = path.resolve(__dirname, '..', '.env');
    const envContent = fs.readFileSync(envPath, 'utf8');
    const match = envContent.match(/MUSIC_QQ_COOKIE=(.+)/);
    if (!match) {
      writeLog('cookie', 'ERROR', '.env 中未找到 MUSIC_QQ_COOKIE');
      return false;
    }
    const cookieRaw = match[1].trim();
    const cookieJson = JSON.parse(cookieRaw);
    config.qq = cookieJson;
    qqMusic.setCookie(cookieJson);  // ← 关键：重新注入到 qq-music-api
    writeLog('cookie', 'INFO', `重载 QQ Cookie: ${Object.keys(cookieJson).length} 个字段`);
    return true;
  } catch (e) {
    writeLog('cookie', 'ERROR', `重载 Cookie 失败: ${e.message}`);
    return false;
  }
}

// 防止 qq-music-api 内部未捕获异常导致进程崩溃
process.on('unhandledRejection', (reason) => {
  writeLog('api', 'ERROR', `[unhandledRejection] ${reason?.message || reason}`);
});

// 启动时检查一次（容错：即使崩溃也不影响启动），之后每 15 分钟检查
(async () => { try { await checkCookies(); } catch (e) { writeLog('cookie', 'ERROR', `Cookie check crashed: ${e.message}`); } })();
setInterval(() => { checkCookies().catch(e => writeLog('cookie', 'ERROR', `Cookie timer failed: ${e.message}`)); }, 15 * 60 * 1000);

// Cookie 状态查询端点
app.get('/cookie-status', (req, res) => {
  const qqKeys = Object.keys(config.qq).length;
  res.json({
    code: 200,
    data: { ...cookieStatus, qqCookieKeys: qqKeys },
    tip: cookieStatus.qq ? null : 'Cookie 过期，请运行: node scripts/get_qq_cookie.mjs',
    timestamp: new Date().toISOString(),
  });
});

// ==================== 上游 API 超时控制 ====================
const UPSTREAM_TIMEOUT = 10000; // 10s

function withTimeout(promise, ms, label) {
  let timer
  const timeoutPromise = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`Upstream timeout: ${label} (${ms}ms)`)), ms)
  })
  return Promise.race([promise, timeoutPromise]).finally(() => clearTimeout(timer))
}

// ==================== 搜索算法配置 (Scoring & Dedup) ====================

const SCORE_CFG = {
  // 多维度加权评分
  weightRelevance: 0.4,      // 相关性权重
  weightPopularity: 0.3,     // 热度权重
  weightRawRank: 0.3,        // 原始排名权重

  // 平台权重 (可配置)
  platformWeights: {
    netease: 1.0,
    qq: 0.9,
  },

  // 加成
  sameSongBonus: 0.3,        // 同名同歌手加成
  platformPreferBonus: 0.2,  // 用户偏好平台加成
};

// 正则：剥离歌曲名后缀 (Live/Remix/Explicit/Cover 等)
const CLEANUP_RE = /\s*[\(（]\s*(Live|Remix|Explicit|Cover|伴奏|纯音乐|翻唱|DJ版|Radio.?Edit|Acoustic|Instrumental|feat\..*?|ft\..*?)\s*[\)）]\s*/gi;
const PUNCTUATION_RE = /[、，。！？；：""''（）《》【】\s\u00A0]+/g;

const CACHE_TTL = 5 * 60 * 1000; // 5分钟

// ==================== LRU 缓存（使用 lru-cache 库） ====================

const searchCache = new LRUCache({
  max: 200,
  ttl: CACHE_TTL,
  updateAgeOnGet: true,
});

const urlCache = new LRUCache({
  max: 200,
  ttl: 10 * 60 * 1000,
  updateAgeOnGet: true,
});

// ==================== 数据清洗工具 ====================

/**
 * 清洗歌曲名：去除 (Live)/(Remix)/(Explicit)/feat.xxx 等干扰后缀
 */
function cleanSongName(raw) {
  if (!raw) return '';
  let cleaned = raw.replace(CLEANUP_RE, '').trim();
  cleaned = cleaned.replace(/\s+/g, ' '); // 合并多余空格
  return cleaned;
}

/**
 * 提取关键词首字符/拼音无关 → 仅做标准化
 */
function normalize(s) {
  if (!s) return '';
  return s.replace(PUNCTUATION_RE, ' ').trim().toLowerCase();
}

// ==================== 信息指纹 (Fingerprint) ====================

/**
 * 生成去重指纹：清洗后取 歌曲名+歌手名+专辑名 的 MD5
 */
function generateFingerprint(song) {
  const cn = cleanSongName(song.name || '');
  const ca = normalize(song.artists || '');
  const al = normalize(song.album || '');
  const seed = `${cn}||${ca}||${al}`;
  return crypto.createHash('md5').update(seed).digest('hex');
}

// ==================== 相关性得分 ====================

function calcRelevance(songName, songArtists, keyword) {
  const kw = normalize(keyword);
  if (!kw) return 0;

  const name = normalize(songName || '');
  const artists = normalize(songArtists || '');

  let score = 0;

  // 歌曲名精确匹配
  if (name === kw) score = 1.0;
  // 歌曲名前缀匹配
  else if (name.startsWith(kw)) score = 0.8;
  // 歌曲名包含匹配
  else if (name.includes(kw)) score = 0.5;
  else score = 0.2;

  // 歌手名包含关键词额外加分 (上限 1.0)
  if (artists.includes(kw)) score = Math.min(1.0, score + 0.3);

  return score;
}

// ==================== 热度得分 ====================

/**
 * 归一化热度 (0~1)
 * 统一对数归一化函数，两平台用相同参考值对齐
 */
function normalizeLog(value, reference = 500000) {
  return Math.min(1, Math.log10((value || 0) + 1) / Math.log10(reference + 1));
}

function calcPopularity(song) {
  if (song.platform === 'netease') {
    const pc = song._raw?.playCount || 0;
    return normalizeLog(pc, 1000000);
  } else if (song.platform === 'qq') {
    const pop = song._raw?.listenCount || song._raw?.popularity || 0;
    return normalizeLog(pop, 1000000);
  }
  return 0.5; // 默认中等热度
}

// ==================== 核心：多维度加权评分 ====================

function calculateScore(song, keyword, index, total, userPerferPlatform) {
  const platform = song.platform || 'netease';

  // 1. 相关性得分
  const relevance = calcRelevance(song.name, song.artists, keyword);

  // 2. 热度得分
  const popularity = calcPopularity(song);

  // 3. 原始排名得分 (index 从 0 开始, index+1 = rank)
  const rank = index + 1;
  const rawRankScore = total > 0 ? (total - rank + 1) / total : (1 / Math.max(rank, 1));

  // 4. 加权合成
  let score = (relevance * SCORE_CFG.weightRelevance)
            + (popularity * SCORE_CFG.weightPopularity)
            + (rawRankScore * SCORE_CFG.weightRawRank);

  // 5. 平台权重
  score *= (SCORE_CFG.platformWeights[platform] || 1.0);

  // 6. 同名同歌手加成 (由合并逻辑单独加)
  // 这里不处理，交给 merge 阶段

  // 7. 用户偏好加成
  if (userPerferPlatform && platform === userPerferPlatform) {
    score += SCORE_CFG.platformPreferBonus;
  }

  return Math.max(0, Math.round(score * 10000) / 10000);
}

// ==================== 同名同歌手检测 ====================

function isSameSong(a, b) {
  const na = cleanSongName(a.name || '');
  const nb = cleanSongName(b.name || '');
  const aa = normalize(a.artists || '');
  const ab = normalize(b.artists || '');
  return na === nb && aa === ab;
}

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

// ==================== 辅助: 分页 ====================

function paginate(list, page, size) {
  const p = Math.max(1, parseInt(page) || 1);
  const s = Math.max(1, parseInt(size) || 20);
  const total = list.length;
  const start = (p - 1) * s;
  return {
    total,
    page: p,
    size: s,
    list: list.slice(start, start + s),
  };
}

function toStandardFormat(list) {
  for (const s of list) {
    delete s._raw;
    delete s.finalScore;
    // vip 字段保留，不做删除
  }
}

// ==================== 辅助: 搜索函数 (增强版) ====================

async function searchNetease(keyword, limit) {
  const extractSongs = (result) => {
    if (!result || result.body.code !== 200) return null
    const songs = result.body?.result?.songs || result.body?.result?.songs
    if (!songs?.length) return null
    return songs.map(s => {
      // 封面提取：兼容多种字段 + HTTP→HTTPS 升级(移动端HTTPS页面不拦截)
      let cover = ''
      if (s.al && s.al.picUrl) {
        cover = s.al.picUrl
      } else if (s.album && s.album.picUrl) {
        cover = s.album.picUrl
      } else if (s.album && s.album.blurPicUrl) {
        cover = s.album.blurPicUrl
      } else if (s.al && s.al.pic_str) {
        cover = `https://p2.music.126.net/${s.al.pic_str}.jpg`
      } else if (s.al && s.al.pic) {
        cover = `https://p2.music.126.net/${s.al.pic}.jpg`
      }
      // 网易云 CDN 支持 HTTPS，强制升级避免移动端混合内容拦截
      if (cover.startsWith('http://')) {
        cover = cover.replace('http://', 'https://')
      }
      return {
        id: s.id, name: s.name,
        artists: (s.ar || s.artists || []).map(a => a.name).join(' / ') || '未知歌手',
        album: s.al ? s.al.name : (s.album ? s.album.name : ''),
        cover,
        duration: s.dt || 0,
        vip: (s.fee === 1 || s.fee === 4 || s.fee === 8 || s.st === -1),
        _raw: { playCount: s.pop || 0 },
      }
    })
  }

  // 尝试一个 API 调用（带重试）
  const call = async (apiFn, params, name) => {
    for (let i = 0; i <= 2; i++) {
      try {
        const r = await apiFn(params)
        const songs = extractSongs(r)
        if (songs) return songs
        if (i < 2) await new Promise(r => setTimeout(r, 500 * (i + 1)))
      } catch (e) {
        if (i < 2) {
          await new Promise(r => setTimeout(r, 500 * (i + 1)))
        } else {
          throw e
        }
      }
    }
    return []
  }

  const makeParam = (withCookie) => {
    const base = { keywords: keyword, limit, type: 1 }
    return withCookie ? withNeteaseCookie(base) : base
  }

  // 策略链: cloudsearch(cookie) → search(cookie) → cloudsearch(无cookie) → search(无cookie)
  const strategies = [
    ['cloudsearch', true],
    ['search', true],
    ['cloudsearch', false],
    ['search', false],
  ]

  for (const [fnName, withCookie] of strategies) {
    const fn = NeteaseCloudMusicApi[fnName]
    if (!fn) continue
    try {
      const result = await withTimeout(
        call(fn, makeParam(withCookie), fnName + (withCookie ? '+cookie' : '-cookie')),
        UPSTREAM_TIMEOUT, 'netease/' + fnName
      )
      if (result.length > 0) return result
    } catch (e) {
      // 继续下一种策略（仅所有策略失败时打印一次）
    }
  }

  console.error('[Netease] 所有搜索策略均失败，请检查 Cookie / 网络')
  return []
}

async function searchQQ(keyword, limit) {
  // 使用经典 c.y.qq.com 搜索接口（无需 Cookie），u.y.qq.com 新版接口需登录已弃用
  const searchUrl = `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${encodeURIComponent(keyword)}&format=json&p=1&n=${limit}&cr=1&aggr=1`;
  try {
    const resp = await axios.get(searchUrl, {
      headers: { Referer: 'https://y.qq.com', 'User-Agent': 'Mozilla/5.0' },
      timeout: UPSTREAM_TIMEOUT,
    });
    const data = resp.data;
    if (data.code !== 0 || !data.data?.song?.list) {
      writeLog('degradation', 'WARN', `QQ搜索异常: code=${data.code}, keyword=${keyword}`);
      return [];
    }
    return data.data.song.list.map(s => {
      let cover = '';
      if (s.albummid) {
        cover = `https://y.gtimg.cn/music/photo_new/T002R300x300M000${s.albummid}.jpg`;
      }
      return {
        id: s.songmid,
        name: s.songname,
        artists: s.singer ? s.singer.map(a => a.name).join(' / ') : '',
        album: s.albumname || '',
        cover: cover,
        duration: s.interval ? s.interval * 1000 : 0,
        vip: !!(s.pay && s.pay.pay_play),
        _raw: { listenCount: s.listennum || 0 },
      };
    });
  } catch (error) {
    writeLog('degradation', 'ERROR', `QQ搜索失败: keyword=${keyword}, ${error.message}`);
    return [];
  }
}

// ==================== 质量精炼 (保留原逻辑) ====================

const SEARCH_FILTER = {
  minDuration: 50,
  blacklist: ['伴奏', '纯音乐', '有声书', '朗诵', '翻唱', 'dj版', 'remix', '铃声', '口水版', '抖音版', '现场版', 'demo', '教学', 'ktv版'],
  penalty: 0.8,
  nameMatchBonus: 0.4,
  artistMatchBonus: 0.2,
  albumMatchBonus: 0.1,
  exactMatchBonus: 0.2,
  longSongBonus: 0.1,
  maxResults: 80,
};

function isBlacklisted(song) {
  return SEARCH_FILTER.blacklist.some(word =>
    (song.name && song.name.toLowerCase().includes(word)) ||
    (song.album && song.album.toLowerCase().includes(word))
  );
}

function refineResults(songs, keyword) {
  if (!songs || !songs.length) return [];

  // 1. 过滤试听版 (<50秒)
  const before = songs.length;
  songs = songs.filter(song => {
    if (song.duration && song.duration < SEARCH_FILTER.minDuration * 1000) return false;
    return true;
  });
  if (songs.length < before) console.log(`[Refine] 过滤试听版: ${before - songs.length}首`);

  // 2. 黑名单降权
  songs.forEach(song => {
    if (isBlacklisted(song)) {
      song.score = (song.score || 0) * (1 - SEARCH_FILTER.penalty);
    }
  });

  // 3. 搜索词匹配加分
  const kw = keyword.toLowerCase();
  songs.forEach(song => {
    let bonus = 0;
    if (song.name && song.name.toLowerCase().includes(kw)) bonus += SEARCH_FILTER.nameMatchBonus;
    if (song.artists && song.artists.toLowerCase().includes(kw)) bonus += SEARCH_FILTER.artistMatchBonus;
    if (song.album && song.album.toLowerCase().includes(kw)) bonus += SEARCH_FILTER.albumMatchBonus;
    if (song.name && song.name.replace(/\s+/g, '').toLowerCase() === kw.replace(/\s+/g, '')) {
      bonus += SEARCH_FILTER.exactMatchBonus;
    }
    song.score += bonus;
  });

  // 4. 长歌曲加分
  songs.forEach(song => {
    if (!isBlacklisted(song) && song.duration > 120000) {
      song.score += SEARCH_FILTER.longSongBonus;
    }
  });

  // 5. 加分后重排 + 截取
  songs.sort((a, b) => (b.score || 0) - (a.score || 0));
  return songs.slice(0, SEARCH_FILTER.maxResults);
}

// ==================== 兼容旧 API 路由 (不变) ====================

app.get('/lyric', async (req, res) => {
  try {
    const { id } = req.query;
    if (!id) return res.status(400).json({ code: 400, message: 'id is required' });
    if (!/^\d{4,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
    const result = await NeteaseCloudMusicApi.lyric(withNeteaseCookie({ id }));
    res.json(result.body);
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.get('/cloudsearch', async (req, res) => {
  try {
    const { keywords, limit = 20, type = 1 } = req.query;
    if (!keywords) return res.status(400).json({ code: 400, message: '缺少 keywords 参数' });
    const result = await NeteaseCloudMusicApi.cloudsearch(withNeteaseCookie({ keywords, limit, type }));
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
    const result = await NeteaseCloudMusicApi.song_url_v1(withNeteaseCookie({ id, level }));
    res.json(result.body);
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.get('/song/detail', async (req, res) => {
  try {
    const { ids } = req.query;
    if (!ids) return res.status(400).json({ code: 400, message: '缺少 ids 参数' });
    const result = await NeteaseCloudMusicApi.song_detail(withNeteaseCookie({ ids }));
    res.json(result.body);
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.get('/personalized', async (req, res) => {
  try {
    const { limit = 10 } = req.query;
    const result = await NeteaseCloudMusicApi.personalized(withNeteaseCookie({ limit }));
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

    // 直接调 QQ API，带上 qqmusic_key 做 authst（qq-music-api 的 /urls 路由缺少 authst）
    const uin = config.qq.uin || '0';
    const qqmusicKey = config.qq.qqmusic_key || '';
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
      headers: { Referer: 'https://y.qq.com' },
      timeout: UPSTREAM_TIMEOUT,
    });
    const mi = qqResp.data?.req_0?.data?.midurlinfo;
    const sip = qqResp.data?.req_0?.data?.sip?.[0] || 'https://aqqmusic.tc.qq.com';
    let url = null;
    if (mi && mi[0] && mi[0].purl) {
      // 如果有 purl，拼完整 CDN URL
      url = mi[0].purl.includes('://') ? mi[0].purl : sip + '/' + mi[0].purl;
    }
    const data = [{ id, url }];
    urlCache.set(cacheKey, data);
    res.json({ code: 200, data });
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

// ==================== 通用代理路由（限制已知方法） ====================

// 允许的网易云 API 方法白名单（从 config.js 读取）
const ALLOWED_NETEASE_APIS = new Set(config.neteaseApis);

app.all('/netease/*', async (req, res) => {
  try {
    const apiPath = req.path.replace('/netease/', '');
    const apiName = apiPath.replace(/\//g, '_');
    if (!ALLOWED_NETEASE_APIS.has(apiName)) {
      return res.status(403).json({ code: 403, message: `API ${apiName} not allowed` });
    }
    const params = withNeteaseCookie({ ...req.query, ...req.body });
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


// ==================== QQ 音乐 API 代理已收窄为具体路由（/qq/search、/qq/playlist、/song/url/qq），不再提供通用 /qq/* 代理

// ==================== 全局错误处理 ====================

app.use((err, req, res, next) => {
  writeLog('api', 'ERROR', `[${req.method} ${req.path}] ${err.message}`);
  res.status(500).json({ code: 500, message: 'Internal Server Error' });
});

// ==================== 启动 ====================

const server = app.listen(PORT, () => {
  console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
  console.log(`  vibeMusic API v3`);
  console.log(`  http://localhost:${PORT}`);
  console.log(`  Cookie: 统一管理 (网易云 + QQ)`);
  console.log(`  监控: 每小时自动检查 (GET /cookie-status)`);
  console.log(`  日志: ./logs/*.YYYY-MM-DD.log (按天轮转，保留30天)`);
  console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
});
// HTTP keep-alive: 复用 TCP 连接，减少后端 → 网关握手开销
server.keepAliveTimeout = 65000; // 略大于 nginx 默认 60s
server.headersTimeout = 66000;
