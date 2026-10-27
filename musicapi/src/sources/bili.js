// B站音乐源适配器（phase 1 匿名 guest：搜索 + 取链）
//
// 2026-10-27 网关重构 pass 2：从 search.js 原样搬出，**行为零变更**。
// 文件分工：
//   ./bili-auth.js   —— guest 票据（buvid 双票据 / WBI key）与签名，有状态有 TTL
//   ./bili-title.js  —— 投稿标题解析（视频标题 → 歌名/歌手），纯函数
//   本文件           —— 搜索、pagelist、playurl 取流编排
//
// ID 方案：搜索 emit 纯 bvid；/bili/url 接受 bvid 或 bvid|cid（有 cid 跳过 pagelist）。
//   一律 BV 前缀，绝不裸 aid —— 裸数字 aid 会撞网易云 \d+ 分支。

const { upstreamGet } = require('../upstream');
const { writeLog } = require('../logger');
const { okOutcome, failOutcome } = require('../source-health');
const { UPSTREAM_TIMEOUT, logSafe } = require('./shared');
const { biliHeaders, ensureBiliBuvid, ensureBiliWbiKey, biliSignWbi } = require('./bili-auth');
const { parseBiliTitle } = require('./bili-title');

// ==================== B站 (BILIBILI, phase 1 匿名 guest) ====================
// Verified live contract (2026-09-14, two angles):
//   (i)  Jmiao11/bili-music 2026 guest 路径：SPI 补齐 buvid3+buvid4 双票据
//        (缺 buvid4 会回 v_voucher)，WBI 签名请求 playurl，yt-dlp 仅兜底；
//   (ii) bilibili-API-collect：search/type 视频搜索 + x/player/wbi/playurl
//        DASH 取流 + musicstream_url 音频文档；guohuiyuan/music-lib bilibili
//        搜索/下载/歌词能力(FLAC 为大会员-only，本 phase 忽略)。
// Guest 结论：无 SESSDATA 可拿 132–192k AAC(DASH audio 軌)；
//   取流必须 UA 非空 + Referer *.bilibili.com，否则 403；
//   playurl URL 有效期约 120min(由后端代理，不直传 app)；
//   歌词无 guest  trivial 通道(字幕需登录，player/v2 对匿名返回空)，故不提供 /bili/lyric。
//
// ID 方案：搜索 emit 纯 bvid(如 BV1De411p77r)；/bili/url 接受纯 bvid 或
//   复合 bvid|cid(有 cid 则跳过 pagelist 解析，MusicFree 式 primaryKey 思想)。
//   一律 BV 前缀，绝不裸 aid——裸数字 aid 会撞网易云 \d+ 分支。

const BILI_SEARCH_URL = 'https://api.bilibili.com/x/web-interface/search/type';
const BILI_PAGELIST_URL = 'https://api.bilibili.com/x/player/pagelist';
const BILI_PLAYURL_URL = 'https://api.bilibili.com/x/player/wbi/playurl';
// NAV/SPI/UA/Referer 属于票据层，定义在 ./bili-auth.js（本文件不直接用）。

// 单曲形态过滤(只动搜索过滤；merge/权重/缓存一律不动)：
//   - 时长阈值 8min=480s：单曲通常 2–5min，Live/完整版多在 7min 内；
//     串烧/合集/整场 Live 动辄 10min–数小时。480s 保留稍长单曲(含 Live 版)，
//     拦截典型合集/长视频。duration 缺失(0)不断言，保守保留。
const BILI_SINGLE_MAX_DURATION_MS = 8 * 60 * 1000; // 480000
//   - 合集标题词(保守：只拦明确的合集/串烧/盘点词，不碰“翻唱/现场/Live/MV/完整版”等正常词)。
const BILI_COMPILATION_KEYWORDS = ['串烧', '合集', '盘点', '连播', '联播', 'medley', 'compilation'];
//   - 音乐区分区 tid=3：search/type 本就支持 tids 参数(现发 tids:0=全站，
//     bilibili-API-collect 同款无签模式已验证)，改发 tids:3 只收音乐区，
//     从源头收窄到单曲形态；若上游忽略该参数，时长+标题规则仍独立生效。
const BILI_MUSIC_TID = 3;

/** 纯 bvid 或复合 bvid|cid(有 cid 跳过 view)。裸数字 aid 一律拒绝。 */
function isBiliTrackId(id) {
  if (typeof id !== 'string' || !id) return false;
  const parts = id.split('|');
  if (!/^BV[a-zA-Z0-9]+$/.test(parts[0])) return false;
  if (parts.length === 1) return true;
  if (parts.length === 2 && /^\d{1,20}$/.test(parts[1])) return true;
  return false;
}

function parseBiliDuration(s) {
  if (s == null) return 0;
  if (typeof s === 'number' && Number.isFinite(s)) return Math.round(s * 1000);
  const str = String(s).trim();
  if (/^\d+$/.test(str)) return Number(str) * 1000;
  const parts = str.split(':').map(Number);
  if (parts.some((n) => !Number.isFinite(n) || n < 0)) return 0;
  let secs = 0;
  for (const n of parts) secs = secs * 60 + n;
  return secs * 1000;
}


function mapBiliVideo(s) {
  const bvid = s.bvid != null ? String(s.bvid) : '';
  const aid = s.aid != null ? String(s.aid) : '';
  // 封面：上游 pic 字段恒有值(2026-09-14 live 验证全行非空)，此处只做协议归一；
  // 浏览器直连 hdslb 会被防盗链 403(verified: 外来 Referer→403)，由后端 image-proxy 代取。
  let cover = s.pic != null ? String(s.pic) : '';
  if (cover.startsWith('//')) cover = `https:${cover}`;
  else if (cover.startsWith('http://')) cover = cover.replace('http://', 'https://');
  // 标题：视频标题→标准 {song, artist}(失败回退原样，绝不空)
  const { name, artists } = parseBiliTitle(s.title, s.author);
  return {
    id: bvid,
    name,
    artists,
    album: '',
    cover,
    duration: parseBiliDuration(s.duration),
    vip: false, // guest 统一 false；大会员档本 phase 不触达
    _raw: {
      aid,
      bvid,
      play: Number(s.play) || 0,
      danmaku: Number(s.danmaku) || 0,
      typename: s.typename != null ? String(s.typename) : '',
    },
  };
}

/**
 * 单曲判断：时长超限或标题命中合集词即非单曲。duration<=0(缺失)保守保留。
 * 时长解析复用 parseBiliDuration(调用方 mapBiliVideo 内已做)，此处只做比较。
 */
function isBiliSingle(track) {
  if (!track) return false;
  if (track.duration > BILI_SINGLE_MAX_DURATION_MS) return false;
  const name = String(track.name || '').toLowerCase();
  for (const kw of BILI_COMPILATION_KEYWORDS) {
    if (kw && name.includes(String(kw).toLowerCase())) return false;
  }
  return true;
}

/**
 * B站视频搜索(匿名)：plain search/type 端口(RSSHub/searx 同款无签模式)，
 * 仅靠 buvid Cookie + Referer + UA。失败/异常一律返回 []，永不抛错。
 * 单曲形态：tids=音乐区(3)源头收窄 + 时长(480s)+标题(合集词)客户端过滤。
 */
async function searchBili(keyword, limit) {
  try {
    const buvid = await ensureBiliBuvid();
    const resp = await upstreamGet(BILI_SEARCH_URL, {
      label: 'bili/search',
      params: {
        search_type: 'video',
        keyword,
        page: 1,
        page_size: limit,
        order: 'totalrank',
        duration: 0,
        tids: BILI_MUSIC_TID,
      },
      headers: biliHeaders(buvid),
      timeoutMs: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const body = resp.data;
    if (!body || body.code !== 0) {
      writeLog('degradation', 'WARN', `B站搜索异常: code=${body && body.code}, keyword=${logSafe(keyword)}`);
      return failOutcome('bili', 'upstream-code=' + (body && body.code));
    }
    const list = body.data && body.data.result;
    if (!Array.isArray(list) || list.length === 0) {
      writeLog('degradation', 'WARN', `B站搜索空结果: keyword=${logSafe(keyword)}`);
      return [];
    }
    return okOutcome('bili', list
      .filter((s) => s && /^BV[a-zA-Z0-9]+$/.test(String(s.bvid || '')))
      .map(mapBiliVideo)
      .filter(isBiliSingle)
      .slice(0, limit));
  } catch (error) {
    writeLog('degradation', 'ERROR', `B站搜索失败: keyword=${logSafe(keyword)}, ${logSafe(error.message)}`);
    return failOutcome('bili', error.message);
  }
}

/** pagelist 解析 bvid → { cid(P1), duration(秒) }；失败返回 null，永不抛错。
 * (刻意不用 web-interface/view：其对匿名 egress 回 -412；pagelist 无需 buvid/WBI。) */
async function getBiliView(bvid) {
  try {
    const buvid = await ensureBiliBuvid();
    const resp = await upstreamGet(BILI_PAGELIST_URL, {
      label: 'bili/pagelist',
      params: { bvid, jsonp: 'jsonp' },
      headers: biliHeaders(buvid),
      timeoutMs: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const body = resp.data;
    if (!body || body.code !== 0 || !Array.isArray(body.data) || body.data.length === 0) {
      writeLog('degradation', 'WARN', `B站 pagelist 异常: bvid=${bvid}, code=${body && body.code}`);
      return null;
    }
    const first = body.data[0];
    if (!first || !first.cid) {
      writeLog('degradation', 'WARN', `B站 pagelist 无 cid: bvid=${bvid}`);
      return null;
    }
    return { cid: first.cid, duration: Number(first.duration) || 0 };
  } catch (error) {
    writeLog('degradation', 'ERROR', `B站 pagelist 失败: bvid=${bvid}, ${logSafe(error.message)}`);
    return null;
  }
}

/**
 * B站 guest 取链：pagelist→cid(复合 id 自带 cid 则跳过)→WBI playurl，
 * fnval=16 DASH 取伴音轨带宽最高者(guest 132–192k AAC)。
 * -412(缺 buvid)时刷新双票据重试一次；无链/异常一律返回 null，永不抛错。
 */
async function getBiliUrl(id) {
  try {
    if (!isBiliTrackId(id)) return null;
    const parts = String(id).split('|');
    const bvid = parts[0];
    let cid = parts.length === 2 ? parts[1] : null;
    if (!cid) {
      const view = await getBiliView(bvid);
      if (!view) return null;
      cid = String(view.cid);
    }
    const mixinKey = await ensureBiliWbiKey();
    if (!mixinKey) {
      writeLog('degradation', 'WARN', `B站取链无 WBI key: id=${id}`);
      return null;
    }
    const doPlayurl = async (refreshBuvid) => {
      const buvid = await ensureBiliBuvid(refreshBuvid);
      const base = { bvid, cid, qn: 32, fnval: 16, fnver: 0, fourk: 0 };
      const signed = biliSignWbi(base, mixinKey);
      const resp = await upstreamGet(BILI_PLAYURL_URL, {
        label: 'bili/playurl',
        params: signed,
        headers: biliHeaders(buvid),
        timeoutMs: UPSTREAM_TIMEOUT,
        validateStatus: () => true,
      });
      return resp.data;
    };
    let body = await doPlayurl(false);
    if (body && body.code === -412) {
      // 缺 buvid 风控：刷新双票据重试一次(bili-music 同策略)
      writeLog('degradation', 'INFO', `B站取链 -412 刷新票据重试: id=${id}`);
      body = await doPlayurl(true);
    }
    if (!body || body.code !== 0 || !body.data) {
      writeLog('degradation', 'WARN', `B站取链异常: id=${id}, code=${body && body.code}`);
      return null;
    }
    const audios = body.data.dash && body.data.dash.audio;
    if (!Array.isArray(audios) || audios.length === 0) return null;
    let best = null;
    for (const a of audios) {
      const url = a && (a.baseUrl || a.base_url);
      if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) continue;
      const bw = Number(a.bandwidth) || 0;
      if (!best || bw > best.bw) best = { url, bw };
    }
    return best ? best.url : null;
  } catch (error) {
    writeLog('degradation', 'ERROR', `B站取链失败: id=${id}, ${logSafe(error.message)}`);
    return null;
  }
}

module.exports = {
  searchBili,
  mapBiliVideo,
  parseBiliDuration,
  isBiliSingle,
  BILI_SINGLE_MAX_DURATION_MS,
  BILI_COMPILATION_KEYWORDS,
  BILI_MUSIC_TID,
  getBiliUrl,
  getBiliView,
  isBiliTrackId,
};
