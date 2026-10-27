// 酷狗音乐源适配器（K2b v5/url Lite 匿名：搜索 + 取链）
//
// 2026-10-27 网关重构 pass 2：从 search.js 原样搬出，**行为零变更**。
// 文件分工：
//   ./kugou-sign.js —— Lite 协议常量（appid/clientver/盐）+ 设备三元组 + 签名算法
//   本文件          —— 搜索归一化、v5 取链、CDN 探活选优
// getKugouPlayData 的签名被既有单测锁定（v5 无内联歌词，lyrics 恒空串）。

const { upstreamGet } = require('../upstream');
const { writeLog } = require('../logger');
const { okOutcome, failOutcome } = require('../source-health');
const { UPSTREAM_TIMEOUT, logSafe } = require('./shared');
const {
  KUGOU_GATEWAY_URL, KUGOU_SEARCH_PATH, KUGOU_SEARCH_ROUTER,
  KUGOU_URL_PATH, KUGOU_URL_ROUTER, KUGOU_APPID,
  KUGOU_SEARCH_CLIENTVER, KUGOU_URL_CLIENTVER,
  KUGOU_PID, KUGOU_CMD, KUGOU_PIDVERSION,
  KUGOU_PAGE_ID, KUGOU_PPAGE_ID, KUGOU_SSA_FLAG, KUGOU_PROBE_TIMEOUT,
  kugouDevice, kugouAndroidSignature, kugouUrlKey, kugouHeaders,
} = require('./kugou-sign');

// ==================== 酷狗 (KUGOU, K2b v5/url Lite 匿名) ====================
// Verified live contract (2026-09-14, domestic egress, anonymous):
//   Search: GET https://gateway.kugou.com/v2/search/song
//     params {appid=3116, clientver=11520, clienttime(sec), dfid=-, mid=<MID>,
//       uuid=-, token='', userid=0, keyword, page=1, pagesize=30,
//       platform=AndroidFilter, tag=em, iscorrection=1, privilegefilter=0,
//       area_code=1, topicfull=1} + signature=MD5(LiteSalt+sorted(k=v,无分隔)+LiteSalt),
//     header x-router: complexsearch.kugou.com + Android UA
//     → {status:1, data:{lists:[...], total}}；单曲项含 FileHash + AlbumID +
//     MixSongID/ID + HQFileHash/SQFileHash + Privilege + Duration(秒)。
//     实测 Privilege 分布：0(免费)/8(免费,128k 可播)/10(付费, v5 status=2
//     fail_process pkg/buy)；网关只收录 0/8(沿用 v3 时代 privilege 0/8 口径)。
//   Play: GET https://gateway.kugou.com/v5/url (Lite profile)
//     params {hash=lower(FileHash), album_id, album_audio_id=0(网关 routes.js 冻结,
//       无 MixSongID 透传字段；上游接受 0, live 验证 status=1 且 CDN URL 内嵌
//       所请求 hash), area_code=1, quality=128/320, behavior=play, IsFreePart=0,
//       pid=411, cmd=26, pidversion=3001, page_id=967177915,
//       ppage_id='356753938,823673182,967485191', ssa_flag=is_fromtrack,
//       cdnBackup=1, clientver=11430, appid=3116, mid, uuid=-, dfid=-,
//       clienttime(sec)} + key=MD5(lower(hash)+KeySalt+appid+MID+userid)
//     + signature=MD5(LiteSalt+sorted(k=v,含 key,不含 signature)+LiteSalt),
//     header x-router: trackercdn.kugou.com + Android UA + dfid/mid/clienttime 头
//     → 平铺 {status:1, url:[...], backupUrl:[...], bitRate, timeLength, ...}
//     (无 data 包裹；status=2=无链/需付费)。实测 url[0](fs.youthandroid2) 偶发
//     502 而同批 url[1]/backupUrl[0](fs.youthandroid) 206，故网关按序 Range 探活
//     首个 200/206 者返回；全灭则回落首个候选(可能瞬时抖动，不缓存由路由层定)。
// 音质档：low→quality=128；standard→先 quality=320(免费 HQ 曲 tracker 自动升,
//   128-only 曲回 status=2)再回落 128；high/super 为 VIP 档，phase 1 直接 null。
// quality→hash 配对(128→FileHash, 320→HQFileHash, flac→SQFileHash)phase-1 仅记录
// 于 _raw，不切换 hash(免费曲切 quality 即可升，见 kugou_sdk 0.2.9 实测要点)。
// 歌词：v5 响应无内联歌词字段，故 getKugouPlayData().lyrics 恒为空串。
//   /kugou/lyric 现已改走独立两步歌词接口（src/kugou-lyric.js，krcs 搜候选 → lyrics 下 base64 LRC），
//   不再依赖本文件的内联字段；getKugouPlayData 保留原签名与原有行为（既有单测锁定）。
// 上游文档版本：MakcRe/KuGouMusicApi 2026-02(module/song_url.js, util/helper.js
//   signatureAndroidParams/signKey, util/request.js 默认参数/头/UA, util/config.json
//   liteAppid) + kugou_sdk 0.2.9 docs/protocol/{search,song}.md。
// 20028 本次请求需要验证 → 需 POST userservice.kugou.com/risk/v2/r_register_dev
//   注册 dfid 后重试；phase-1 未 live 撞见 20028，故仅留常量与注释，未实现。


function stripKugouEm(s) {
  return String(s != null ? s : '').replace(/<em[^>]*>/gi, '').replace(/<\/em>/gi, '');
}

function mapKugouSong(s) {
  const fileHash = s.FileHash != null ? String(s.FileHash) : (s.hash != null ? String(s.hash) : '');
  let name = stripKugouEm(s.OriSongName || s.SongName || s.songname || '').trim();
  if (!name) {
    // 仅合成串(歌手 - 歌名)时剥前缀取纯歌名
    const fileName = stripKugouEm(s.FileName || s.filename || '');
    const dash = fileName.indexOf(' - ');
    name = dash >= 0 ? fileName.slice(dash + 3).trim() : fileName.trim();
  }
  let artists = stripKugouEm(s.SingerName || s.singername || '').trim();
  if (!artists && Array.isArray(s.Singers)) {
    artists = s.Singers.map((x) => stripKugouEm(x && x.name)).filter(Boolean).join(' / ');
  }
  // v3 时代 singername 用 、分隔，多歌手统一为 ' / '
  artists = artists.replace(/[、;；]/g, ' / ');
  const coverTpl = s.AlbumImage != null && s.AlbumImage !== ''
    ? String(s.AlbumImage)
    : (s.Image != null ? String(s.Image) : '');
  const durationSec = Number(s.Duration != null ? s.Duration : s.duration);
  return {
    id: fileHash,
    name,
    artists,
    album: s.AlbumName != null ? String(s.AlbumName) : (s.album_name ?? ''),
    cover: coverTpl ? coverTpl.replace('{size}', '400') : '',
    duration: Number.isFinite(durationSec) ? Math.round(durationSec * 1000) : 0,
    vip: false, // 搜索期统一 false；Privilege 非 0/8(付费档)已在 searchKugou 过滤
    _raw: {
      albumId: s.AlbumID != null ? String(s.AlbumID) : (s.album_id != null ? String(s.album_id) : ''),
      albumAudioId: s.MixSongID != null ? String(s.MixSongID) : (s.ID != null ? String(s.ID) : ''),
      hashHQ: s.HQFileHash != null ? String(s.HQFileHash) : '',
      hashSQ: s.SQFileHash != null ? String(s.SQFileHash) : '',
    },
  };
}

async function searchKugou(keyword, limit) {
  try {
    const device = kugouDevice();
    const clienttime = String(Math.floor(Date.now() / 1000));
    const params = {
      appid: KUGOU_APPID,
      clientver: KUGOU_SEARCH_CLIENTVER,
      clienttime,
      dfid: device.dfid,
      mid: device.mid,
      uuid: '-',
      token: '',
      userid: '0',
      keyword,
      page: '1',
      pagesize: String(limit),
      platform: 'AndroidFilter',
      tag: 'em',
      iscorrection: '1',
      privilegefilter: '0',
      area_code: '1',
      topicfull: '1',
    };
    params.signature = kugouAndroidSignature(params);
    const resp = await upstreamGet(KUGOU_GATEWAY_URL + KUGOU_SEARCH_PATH, {
      label: 'kugou/search',
      params,
      headers: kugouHeaders(KUGOU_SEARCH_ROUTER, device, clienttime),
      timeoutMs: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const body = resp.data;
    if (!body || body.status !== 1) {
      writeLog('degradation', 'WARN', `酷狗搜索异常: status=${body && body.status}, keyword=${logSafe(keyword)}`);
      return failOutcome('kugou', 'upstream-status=' + (body && body.status));
    }
    const list = body.data && body.data.lists;
    if (!Array.isArray(list) || list.length === 0) {
      writeLog('degradation', 'WARN', `酷狗搜索空结果: keyword=${logSafe(keyword)}`);
      return [];
    }
    return okOutcome('kugou', list
      .filter((s) => s && /^[a-f0-9]{32}$/i.test(String(s.FileHash || s.hash || ''))
        && (s.Privilege === 0 || s.Privilege === 8))
      .map(mapKugouSong));
  } catch (error) {
    writeLog('degradation', 'ERROR', `酷狗搜索失败: keyword=${logSafe(keyword)}, ${logSafe(error.message)}`);
    return failOutcome('kugou', error.message);
  }
}

/**
 * 取酷狗 v5 原始节点：status!=1/异常一律返回 null，永不抛错。
 * (v5 平铺响应 {status:1, url:[], backupUrl:[]}；防御性兼容 data 包裹形态。)
 */
async function getKugouV5Data(hash, albumId, quality) {
  try {
    const device = kugouDevice();
    const clienttime = String(Math.floor(Date.now() / 1000));
    const h = String(hash).toLowerCase();
    const params = {
      hash: h,
      album_id: albumId && /^\d+$/.test(String(albumId)) ? String(albumId) : '0',
      album_audio_id: '0', // routes.js 冻结、无 MixSongID 透传字段；上游接受 0(live 验证)
      area_code: '1',
      quality: String(quality),
      behavior: 'play',
      IsFreePart: '0',
      pid: KUGOU_PID,
      cmd: KUGOU_CMD,
      pidversion: KUGOU_PIDVERSION,
      page_id: KUGOU_PAGE_ID,
      ppage_id: KUGOU_PPAGE_ID,
      ssa_flag: KUGOU_SSA_FLAG,
      cdnBackup: '1',
      clientver: KUGOU_URL_CLIENTVER,
      appid: KUGOU_APPID,
      mid: device.mid,
      uuid: '-',
      dfid: device.dfid,
      clienttime,
    };
    params.key = kugouUrlKey(h, device.mid);
    params.signature = kugouAndroidSignature(params);
    const resp = await upstreamGet(KUGOU_GATEWAY_URL + KUGOU_URL_PATH, {
      label: 'kugou/url',
      params,
      headers: kugouHeaders(KUGOU_URL_ROUTER, device, clienttime),
      timeoutMs: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const body = resp.data;
    const node = (body && typeof body === 'object' && body.data && typeof body.data === 'object'
      && (body.data.url || body.data.backupUrl)) ? body.data : body;
    if (!node || node.status !== 1) {
      // 20028 本次请求需要验证 → 走 KUGOU_REGISTER_DEV_URL 注册 dfid 后重试(phase-1 未实现，见顶部注释)
      writeLog('degradation', 'WARN', `酷狗取链异常: hash=${hash}, quality=${quality}, status=${node && node.status}`);
      return null;
    }
    return node;
  } catch (error) {
    writeLog('degradation', 'ERROR', `酷狗取链失败: hash=${hash}, quality=${quality}, ${logSafe(error.message)}`);
    return null;
  }
}

/** v5 节点 → 有序去重候选 URL(url[] 优先，其次 backupUrl[])。纯函数，便于单测。 */
function pickKugouUrls(node) {
  if (!node || typeof node !== 'object') return [];
  const out = [];
  for (const k of ['url', 'backupUrl']) {
    const v = node[k];
    if (Array.isArray(v)) {
      for (const u of v) {
        if (typeof u === 'string' && /^https?:\/\//i.test(u) && !out.includes(u)) out.push(u);
      }
    } else if (typeof v === 'string' && /^https?:\/\//i.test(v) && !out.includes(v)) {
      out.push(v);
    }
  }
  return out;
}

/** CDN Range 探活：首字节 200/206 即视为可播。永不抛错。 */
async function probeKugouCdn(url) {
  try {
    const resp = await upstreamGet(url, {
      label: 'kugou/cdn-probe',
      headers: { Range: 'bytes=0-1', 'User-Agent': 'Mozilla/5.0' },
      timeoutMs: KUGOU_PROBE_TIMEOUT,
      validateStatus: () => true,
      responseType: 'arraybuffer',
    });
    return resp.status === 200 || resp.status === 206;
  } catch {
    return false;
  }
}

async function getKugouVerifiedUrl(hash, albumId, quality) {
  const node = await getKugouV5Data(hash, albumId, quality);
  if (!node) return null;
  const candidates = pickKugouUrls(node);
  for (const u of candidates) {
    // eslint-disable-next-line no-await-in-loop
    if (await probeKugouCdn(u)) return u;
  }
  // 候选存在但探活全灭(多为 CDN 瞬时 502)：回落首个候选，不在此处缓存(由路由层按非 null 入缓存)
  return candidates.length > 0 ? candidates[0] : null;
}

/**
 * 取酷狗播放地址：low→quality=128 探活首个；
 * standard→先 quality=320(若暴露)否则回落 128；无链/异常一律返回 null，永不抛错。
 */
async function getKugouUrl(hash, albumId, level = 'low') {
  try {
    if (level === 'standard') {
      const hi = await getKugouVerifiedUrl(hash, albumId, '320');
      if (hi) return hi;
    }
    return await getKugouVerifiedUrl(hash, albumId, '128');
  } catch (error) {
    writeLog('degradation', 'ERROR', `酷狗取链失败: hash=${hash}, level=${level}, ${logSafe(error.message)}`);
    return null;
  }
}

/**
 * 酷狗 v5 播放数据适配（签名冻结：既有单测依赖，勿改勿删）。
 * v5 响应无内联歌词字段 → lyrics 恒为 ''；/kugou/lyric 的歌词已改走
 * src/kugou-lyric.js 的两步接口，本函数保留原有行为。
 */
async function getKugouPlayData(hash, albumId) {
  const node = await getKugouV5Data(hash, albumId, '128');
  if (!node) return null;
  const urls = pickKugouUrls(node);
  return {
    play_url: urls[0] || '',
    play_backup_url: urls[1] || '',
    lyrics: '',
    img: '',
  };
}


module.exports = {
  searchKugou,
  mapKugouSong,
  getKugouPlayData,
  getKugouUrl,
  kugouDevice,
  kugouAndroidSignature,
  kugouUrlKey,
  pickKugouUrls,
};
