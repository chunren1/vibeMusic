const express = require('express');
const cors = require('cors');
const crypto = require('crypto');
const qqMusic = require('qq-music-api');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');

const app = express();
const PORT = 3000;

// 中间件
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// QQ音乐Cookie
const qqCookie = require('./config.js');
qqMusic.setCookie(qqCookie);

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

// ==================== LRU 缓存 ====================

class LRUCache {
  constructor(max = 200, ttl = CACHE_TTL) {
    this.max = max;
    this.ttl = ttl;
    this.map = new Map();
  }

  get(key) {
    if (!this.map.has(key)) return null;
    const entry = this.map.get(key);
    if (Date.now() - entry.ts > this.ttl) {
      this.map.delete(key);
      return null;
    }
    // 移至队尾 (最近使用)
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.value;
  }

  set(key, value) {
    if (this.map.has(key)) this.map.delete(key);
    else if (this.map.size >= this.max) {
      // 淘汰最久未使用 (队首)
      const oldest = this.map.keys().next().value;
      this.map.delete(oldest);
    }
    this.map.set(key, { value, ts: Date.now() });
  }

  clear() { this.map.clear(); }
  get size() { return this.map.size; }
}

const searchCache = new LRUCache(200);

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
 * 不同平台字段不同，需要分别处理
 */
function calcPopularity(song) {
  let raw = 0;

  if (song.platform === 'netease') {
    // 网易云有 playCount (播放量) 或 score
    const pc = song._raw?.playCount || song._raw?.score || 0;
    raw = pc > 0 ? Math.log10(pc + 1) / 8 : 0; // 对数归一化
  } else if (song.platform === 'qq') {
    // QQ音乐有 popularity 或 listenCount
    const pop = song._raw?.popularity || song._raw?.listenCount || song._raw?.pay?.pay_play || 0;
    raw = pop > 0 ? Math.min(1, Math.log10(pop + 1) / 6) : 0;
  }

  return Math.min(1, Math.max(0, raw || 0.5)); // 默认 0.5
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
app.get('/search', async (req, res) => {
  try {
    const { keyword, page = 1, size = 20, prefer } = req.query;
    const maxRank = 30; // 各平台获取的最大条数

    if (!keyword) {
      return res.status(400).json({ code: 400, message: 'keyword is required', data: null });
    }

    const kw = keyword.trim();

    // ---- LRU 缓存命中 ----
    const cacheKey = `search:${kw}:${maxRank}`;
    const cached = searchCache.get(cacheKey);
    if (cached) {
      console.log(`[Cache] HIT for "${kw}"`);
      const pageData = paginate(cached, page, size);
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
      s.score = calculateScore(s, kw, idx, neteaseSongs.length, prefer);
      scored.push(s);
    });

    qqSongs.forEach((song, idx) => {
      const s = { ...song, platform: 'qq', _raw: song._raw };
      s.score = calculateScore(s, kw, idx, qqSongs.length, prefer);
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

    // ---- 缓存结果 ----
    searchCache.set(cacheKey, refined);

    // ---- 分页 ----
    const pageData = paginate(refined, page, size);

    toStandardFormat(refined); // 清理内部字段

    res.json({ code: 200, message: 'success', data: pageData });
  } catch (error) {
    console.error('[Search] Error:', error.message);
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
  }
}

// ==================== 辅助: 搜索函数 (增强版) ====================

async function searchNetease(keyword, limit) {
  try {
    const result = await NeteaseCloudMusicApi.cloudsearch({ keywords: keyword, limit, type: 1 });
    if (result.body.code === 200 && result.body.result && result.body.result.songs) {
      return result.body.result.songs.map(s => ({
        id: s.id,
        name: s.name,
        artists: (s.ar || s.artists || []).map(a => a.name).join(' / ') || '未知歌手',
        album: s.al ? s.al.name : (s.album ? s.album.name : ''),
        cover: (s.al && s.al.picUrl) ? s.al.picUrl : (s.album && s.album.picUrl ? s.album.picUrl : ''),
        duration: s.dt || 0,
        _raw: { playCount: s.id ? null : null }, // 网易云 cloudsearch 不返回 playCount，需额外查详情
      }));
    }
    return [];
  } catch (error) {
    console.error('[Netease] Search error:', error.message);
    return [];
  }
}

async function searchQQ(keyword, limit) {
  try {
    const result = await qqMusic.api('search', { key: keyword, limit });
    if (result && result.list && Array.isArray(result.list)) {
      return result.list.map(s => {
        let cover = '';
        if (s.albumcover) {
          cover = s.albumcover;
        } else if (s.albummid) {
          cover = `https://y.gtimg.cn/music/photo_new/T002R300x300M000${s.albummid}.jpg`;
        }
        return {
          id: s.songmid,
          name: s.songname,
          artists: s.singer ? s.singer.map(a => a.name).join(' / ') : '',
          album: s.albumname || '',
          cover: cover,
          duration: s.interval ? s.interval * 1000 : 0,
          _raw: { listenCount: s.listennum || 0 },
        };
      });
    }
    return [];
  } catch (error) {
    console.error('[QQ] Search error:', error.message);
    return [];
  }
}

// ==================== 质量精炼 (保留原逻辑) ====================

const SEARCH_FILTER = {
  minDuration: 50,
  blacklist: ['伴奏', '纯音乐', '有声书', '朗诵', '翻唱', 'dj版', 'remix'],
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

  // 5. 重排 + 截取
  songs.sort((a, b) => (b.score || 0) - (a.score || 0));
  return songs.slice(0, SEARCH_FILTER.maxResults);
}

// ==================== 兼容旧 API 路由 (不变) ====================

app.get('/lyric', async (req, res) => {
  try {
    const { id } = req.query;
    if (!id) return res.status(400).json({ code: 400, message: 'id is required' });
    const result = await NeteaseCloudMusicApi.lyric({ id });
    res.json(result.body);
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.get('/cloudsearch', async (req, res) => {
  try {
    const { keywords, limit = 20, type = 1 } = req.query;
    const result = await NeteaseCloudMusicApi.cloudsearch({ keywords, limit, type });
    res.json(result.body);
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.get('/song/url/v1', async (req, res) => {
  try {
    const { id, level = 'exhigh' } = req.query;
    const result = await NeteaseCloudMusicApi.song_url_v1({ id, level });
    res.json(result.body);
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.get('/song/detail', async (req, res) => {
  try {
    const { ids } = req.query;
    const result = await NeteaseCloudMusicApi.song_detail({ ids });
    res.json(result.body);
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.get('/personalized', async (req, res) => {
  try {
    const { limit = 10 } = req.query;
    const result = await NeteaseCloudMusicApi.personalized({ limit });
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

app.get('/song/url/qq', async (req, res) => {
  try {
    const { id } = req.query;
    const cacheKey = `qq_url:${id}`;
    const cached = searchCache.get(cacheKey);
    if (cached) return res.json({ code: 200, data: cached });

    const result = await qqMusic.api('/song/urls', { id });
    const url = result && result[id] ? result[id] : null;
    const data = [{ id: id, url: url }];
    searchCache.set(cacheKey, data);
    res.json({ code: 200, data });
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

// ==================== 通用代理路由 ====================

app.all('/netease/*', async (req, res) => {
  try {
    const apiPath = req.path.replace('/netease/', '');
    const apiName = apiPath.replace(/\//g, '_');
    const params = { ...req.query, ...req.body };
    if (typeof NeteaseCloudMusicApi[apiName] === 'function') {
      const result = await NeteaseCloudMusicApi[apiName](params);
      res.json(result.body);
    } else {
      res.status(404).json({ code: 404, message: `API ${apiName} not found` });
    }
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message });
  }
});

app.all('/qq/*', async (req, res) => {
  try {
    const path = req.path.replace('/qq/', '');
    const query = { ...req.query, ...req.body };
    const result = await qqMusic.api(path, query);
    res.json({ code: 200, message: 'success', data: result });
  } catch (error) {
    res.status(500).json({ code: 500, message: error.message, data: null });
  }
});

// ==================== 健康检查 ====================

app.get('/health', (req, res) => {
  res.json({
    code: 200,
    message: 'Music API Service v2 (Upgraded Scoring)',
    data: {
      netease: 'available',
      qq: 'available',
      cacheSize: searchCache.size,
      timestamp: new Date().toISOString(),
    },
  });
});

// ==================== 启动 ====================

app.listen(PORT, () => {
  console.log(`Music API Service v2 on http://localhost:${PORT}`);
  console.log('  - 升级: 多维加权评分 + 信息指纹去重 + LRU缓存');
  console.log(`  - 聚合搜索: GET /search?keyword=xxx&prefer=netease`);
  console.log(`  - 健康检查: GET /health (含缓存大小)`);
});
