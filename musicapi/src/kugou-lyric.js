// ==================== 酷狗歌词（krcs 搜索 + lyrics 下载 两步） ====================
// 背景：v5/url 响应没有内联歌词字段，所以 getKugouPlayData().lyrics 恒为 ''，
//   /kugou/lyric 曾因此恒 404。官方歌词是独立的**两步**接口（2026-10-25 本机直连实测）：
//   1) GET krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=<hash>
//      → {status:200, candidates:[{id, accesskey, singer, song, duration, product_from, ...}]}
//   2) GET lyrics.kugou.com/download?ver=1&client=pc&id=&accesskey=&fmt=lrc&charset=utf8
//      → {status:200, fmt:'lrc', content:'<base64 编码的 LRC>'}（content 是 base64，需 decode）
// 实测样例：hash 6BC433A2C149FFC43F8309C73A537601 → id=741307774 /
//   accesskey=8D735CF26880D11E9F70B8953F206FBE → 200，LRC 900 字符。
//
// 边界：本模块只负责"取歌词"，不改 search.js 的 getKugouPlayData（该函数签名冻结）。
// 失败一律返回 null（由路由降级 404），绝不抛错；日志只记 hash/id 长度与状态码，
// 绝不落 accesskey / content 原文（与 routes.js scrubCookieValues 同一思路）。
const { upstreamGet } = require('./upstream');
const { writeLog } = require('./logger');

const KUGOU_LYRIC_SEARCH_URL = 'https://krcs.kugou.com/search';
const KUGOU_LYRIC_DOWNLOAD_URL = 'https://lyrics.kugou.com/download';
// 单步 6s：两步最坏 12s，低于网关其它上游的 10s 单次上限总预算量级，
// 上游挂住时路由会先于 axios 全局超时返回 404，不拖住网关。
const KUGOU_LYRIC_TIMEOUT = 6000;
// 歌词是纯文本（LRC 通常 <10KB），512KB 足够且防上游异常大响应。
const KUGOU_LYRIC_MAX_BYTES = 512 * 1024;
// 官方歌词站要"浏览器味"：无 UA 会被拒（krcs 必带 UA + Referer，实测缺一即空 candidates）。
const KUGOU_LYRIC_HEADERS = {
  'User-Agent': 'Mozilla/5.0',
  Referer: 'https://www.kugou.com/',
};
// id / accesskey 只允许字母数字：既防注入到 query 的怪字符，也顺带过滤脏候选。
const SAFE_TOKEN_RE = /^[A-Za-z0-9]{1,64}$/;

/** 日志单行化：剥离 CR/LF，防止上游文案伪造日志行（CWE-117，同 search.js logSafe）。 */
function oneLine(value) {
  return String(value == null ? '' : value).replace(/[\r\n]+/g, ' ');
}

/** 候选是否 UGC（用户上传的翻唱/DJ 版）。product_from 缺失按非 UGC 处理（保守）。 */
function isUgcCandidate(candidate) {
  return String(candidate && candidate.product_from ? candidate.product_from : '').toLowerCase() === 'ugc';
}

/** 候选与目标时长的距离（秒）。目标未知或候选无时长 → Infinity（不参与时长比较）。 */
function durationDelta(candidate, targetSec) {
  if (!(targetSec > 0)) return Infinity;
  const dur = Number(candidate && candidate.duration);
  if (!Number.isFinite(dur) || dur <= 0) return Infinity;
  return Math.abs(dur - targetSec);
}

/**
 * 候选选择（纯函数，便于单测）。tie-break 顺序：
 *   1) 官方优先：product_from !== 'ugc' 排在 UGC 前面；
 *   2) 时长最接近请求 duration（秒）；目标/候选时长任一缺失时该维度视为 Infinity，不参与比较；
 *   3) 仍并列 → 保持上游原始顺序（即取第一条）。
 * 全部候选都缺 id/accesskey 时返回 null。
 */
function pickKugouLyricCandidate(candidates, durationSec = 0) {
  const target = Number(durationSec) > 0 ? Number(durationSec) : 0;
  const list = (Array.isArray(candidates) ? candidates : [])
    .map((candidate, index) => ({ candidate, index }))
    .filter(({ candidate }) => candidate
      && SAFE_TOKEN_RE.test(String(candidate.id == null ? '' : candidate.id))
      && SAFE_TOKEN_RE.test(String(candidate.accesskey == null ? '' : candidate.accesskey)));
  if (list.length === 0) return null;
  list.sort((a, b) => {
    const ugcA = isUgcCandidate(a.candidate);
    const ugcB = isUgcCandidate(b.candidate);
    if (ugcA !== ugcB) return ugcA ? 1 : -1;
    const deltaA = durationDelta(a.candidate, target);
    const deltaB = durationDelta(b.candidate, target);
    if (deltaA !== deltaB) return deltaA - deltaB;
    return a.index - b.index;
  });
  return list[0].candidate;
}

/**
 * 请求侧 duration 归一化：网关内部时长统一毫秒（搜索 emit 240000），
 * 但也兼容按秒传（240）。>=10000 视为毫秒（歌曲毫秒值必 >30000），否则按秒。
 * 非法/缺省 → 0（= 不按时长筛选，退化为官方优先 + 第一条）。
 */
function parseKugouLyricDuration(value) {
  const raw = Array.isArray(value) ? value[0] : value;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return n >= 10000 ? Math.round(n / 1000) : Math.round(n);
}

/** 第一步：按 hash 找候选。status!=200/candidates 非数组 → null。 */
async function findKugouLyricCandidate(hash, durationSec) {
  const resp = await upstreamGet(KUGOU_LYRIC_SEARCH_URL, {
    label: 'kugou/lyric-search',
    params: {
      ver: 1,
      man: 'yes',
      client: 'mobi',
      keyword: '',
      duration: '',
      hash,
    },
    headers: KUGOU_LYRIC_HEADERS,
    timeoutMs: KUGOU_LYRIC_TIMEOUT,
    maxBytes: KUGOU_LYRIC_MAX_BYTES,
    validateStatus: () => true,
  });
  const body = resp.data;
  if (!body || body.status !== 200 || !Array.isArray(body.candidates)) {
    writeLog('degradation', 'WARN',
      `酷狗歌词搜索异常: hash=${hash}, status=${body && body.status}, candidates=${body && Array.isArray(body.candidates) ? body.candidates.length : 'n/a'}`);
    return null;
  }
  return pickKugouLyricCandidate(body.candidates, durationSec);
}

/** 第二步：按 id+accesskey 下 LRC，content 为 base64。空/异常 → null。 */
async function downloadKugouLyric(candidate) {
  const resp = await upstreamGet(KUGOU_LYRIC_DOWNLOAD_URL, {
    label: 'kugou/lyric',
    params: {
      ver: 1,
      client: 'pc',
      id: String(candidate.id),
      accesskey: String(candidate.accesskey),
      fmt: 'lrc',
      charset: 'utf8',
    },
    headers: KUGOU_LYRIC_HEADERS,
    timeoutMs: KUGOU_LYRIC_TIMEOUT,
    maxBytes: KUGOU_LYRIC_MAX_BYTES,
    validateStatus: () => true,
  });
  const body = resp.data;
  if (!body || body.status !== 200 || typeof body.content !== 'string' || body.content === '') {
    writeLog('degradation', 'WARN',
      `酷狗歌词下载异常: id=${candidate.id}, status=${body && body.status}, contentLen=${typeof (body && body.content) === 'string' ? body.content.length : 0}`);
    return null;
  }
  const lrc = Buffer.from(body.content, 'base64').toString('utf8').replace(/^\uFEFF/, '');
  // 只去 BOM，不做 trim：保留上游原始换行/缩进，避免改坏 LRC 解析器
  return lrc.trim() === '' ? null : lrc;
}

/**
 * 取酷狗歌词。成功 → {lyric, song, singer, duration}；任何失败 → null（永不抛错）。
 * @param {string} hash 32 位 hex FileHash
 * @param {number} [duration=0] 期望时长（秒，见 parseKugouLyricDuration），用于候选择优
 */
async function fetchKugouLyric(hash, duration = 0) {
  const h = String(hash == null ? '' : hash).trim();
  if (!/^[a-f0-9]{32}$/i.test(h)) return null;
  const durationSec = Number(duration) > 0 ? Number(duration) : 0;
  try {
    const candidate = await findKugouLyricCandidate(h, durationSec);
    if (!candidate) {
      writeLog('degradation', 'INFO', `酷狗歌词无可用候选: hash=${h}`);
      return null;
    }
    const lyric = await downloadKugouLyric(candidate);
    if (!lyric) return null;
    // 只记长度/元信息，不记 accesskey、不记 LRC 原文
    writeLog('api', 'INFO',
      `酷狗歌词命中: hash=${h}, id=${candidate.id}, ugc=${isUgcCandidate(candidate)}, lrcLen=${lyric.length}, songLen=${oneLine(candidate.song).length}`);
    return {
      lyric,
      song: oneLine(candidate.song).trim(),
      singer: oneLine(candidate.singer).trim(),
      duration: Number(candidate.duration) || 0,
    };
  } catch (error) {
    // 上游超时/网络/HTTP 全在这里收口：路由拿 null 降级 404，绝不 500
    writeLog('degradation', 'WARN', `酷狗歌词失败: hash=${h}, ${oneLine(error && error.message)}`);
    return null;
  }
}

module.exports = {
  fetchKugouLyric,
  pickKugouLyricCandidate,
  parseKugouLyricDuration,
  isUgcCandidate,
  KUGOU_LYRIC_SEARCH_URL,
  KUGOU_LYRIC_DOWNLOAD_URL,
  KUGOU_LYRIC_TIMEOUT,
};
