// ==================== 搜索算法配置 (Scoring & Dedup) ====================
const crypto = require('crypto');

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

// ==================== 辅助: 分页 / 清理 ====================

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

module.exports = {
  SCORE_CFG,
  SEARCH_FILTER,
  cleanSongName,
  normalize,
  generateFingerprint,
  calcRelevance,
  normalizeLog,
  calcPopularity,
  calculateScore,
  isSameSong,
  paginate,
  toStandardFormat,
  isBlacklisted,
  refineResults,
};