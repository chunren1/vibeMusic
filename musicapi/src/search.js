// ==================== 上游 API 搜索代理 ====================
const axios = require('axios');
const crypto = require('crypto');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const { LRUCache } = require('lru-cache');
const { withNeteaseCookie } = require('./cookie');
const { writeLog } = require('./logger');

// ==================== 上游 API 超时控制 ====================
const UPSTREAM_TIMEOUT = 10000; // 10s（单次上游调用上限）
// 搜索策略链总预算：网易云 4 策略串行（各 10s）最坏约 40s，
// 此处设总预算 12s 早退——降级顺序不变，只是超时后不再继续试剩余策略。
const SEARCH_TOTAL_BUDGET = 12000; // 12s

function withTimeout(promise, ms, label) {
  let timer
  const timeoutPromise = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`Upstream timeout: ${label} (${ms}ms)`)), ms)
  })
  return Promise.race([promise, timeoutPromise]).finally(() => clearTimeout(timer))
}

// ==================== LRU 缓存（使用 lru-cache 库） ====================

const CACHE_TTL = 5 * 60 * 1000; // 5分钟

const searchCache = new LRUCache({
  max: 200,
  ttl: CACHE_TTL,
  updateAgeOnGet: true,
});

const urlCache = new LRUCache({
  max: 200,
  // QQ vkey URL 有效期仅数分钟（实测几分钟内返回 403），缓存 60s 足够覆盖重试/刷新，
  // 避免缓存过期 URL 导致播放失败；网易云 URL 不走此缓存
  ttl: 60 * 1000,
  // 硬过期：get 不刷新 TTL。vkey URL 自生成时刻起单调走向失效，
  // sliding refresh 会让高频命中的条目常驻（过期 URL 不死 → 持续 403），
  // 故此处必须禁用 updateAgeOnGet。
  updateAgeOnGet: false,
});

// ==================== 辅助: 搜索函数 (增强版) ====================

async function searchNetease(keyword, limit, req) {
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
    return withCookie ? withNeteaseCookie(base, req) : base
  }

  // 策略链: cloudsearch(cookie) → search(cookie) → cloudsearch(无cookie) → search(无cookie)
  // 总预算 SEARCH_TOTAL_BUDGET 内串行降级，超时即早退（降级顺序不变）。
  const strategies = [
    ['cloudsearch', true],
    ['search', true],
    ['cloudsearch', false],
    ['search', false],
  ]

  const runStrategies = (async () => {
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
  })()

  try {
    return await withTimeout(runStrategies, SEARCH_TOTAL_BUDGET, 'netease/search-total')
  } catch (e) {
    writeLog('degradation', 'ERROR', `网易云搜索总预算超时(${SEARCH_TOTAL_BUDGET}ms)，降级为空: ${e.message}`)
    return []
  }
}

function mapQQSong(s) {
  let cover = '';
  const albummid = s.album?.mid || s.albummid;
  if (albummid) {
    cover = `https://y.gtimg.cn/music/photo_new/T002R300x300M000${albummid}.jpg`;
  }
  return {
    id: s.mid ?? s.songmid,
    name: s.name ?? s.songname,
    artists: s.singer ? s.singer.map(a => a.name).join(' / ') : '',
    album: s.album?.name ?? s.albumname ?? '',
    cover: cover,
    duration: s.interval ? s.interval * 1000 : 0,
    vip: !!(s.pay && s.pay.pay_play),
    _raw: { listenCount: s.listennum || 0 },
  };
}

// QQ 直连接口请求构造（searchQQ 与健康探针共用同一链路，避免检查与真实搜索口径不一致）
function buildQQSearchRequest(keyword, limit) {
  const reqData = JSON.stringify({
    comm: { g_tk: 5381, uin: 0, format: 'json', platform: 'h5' },
    search: {
      module: 'music.search.SearchCgiService',
      method: 'DoSearchForQQMusicDesktop',
      param: { query: keyword, page_num: 1, page_size: limit, search_type: 0 },
    },
  });
  return {
    url: 'https://u.y.qq.com/cgi-bin/musicu.fcg',
    params: { format: 'json', data: reqData },
    headers: { Referer: 'https://y.qq.com', 'User-Agent': 'Mozilla/5.0' },
  };
}

async function searchQQ(keyword, limit) {
  const { url, params, headers } = buildQQSearchRequest(keyword, limit);
  try {
    const resp = await axios.get(url, {
      params,
      headers,
      timeout: UPSTREAM_TIMEOUT,
    });
    const list = resp.data?.search?.data?.body?.song?.list;
    if (!Array.isArray(list) || list.length === 0) {
      writeLog('degradation', 'WARN', `QQ搜索异常: code=${resp.data?.code}, keyword=${keyword}`);
      return [];
    }
    return list.map(mapQQSong);
  } catch (error) {
    writeLog('degradation', 'ERROR', `QQ搜索失败: keyword=${keyword}, ${error.message}`);
    return [];
  }
}

// 健康探针：与 searchQQ 同一一条直连接口，只校验期望结构（song 节点为对象），
// 空结果不视为失效——上游风控偶发空列表时不再误报 Cookie 失效。
async function probeQQSearch() {
  const { url, params, headers } = buildQQSearchRequest('周杰伦', 1);
  try {
    const resp = await withTimeout(
      axios.get(url, { params, headers, timeout: UPSTREAM_TIMEOUT }),
      UPSTREAM_TIMEOUT, 'qq/health-probe'
    );
    const song = resp.data?.search?.data?.body?.song;
    if (song && typeof song === 'object') {
      const list = song.list;
      return { ok: true, empty: !Array.isArray(list) || list.length === 0 };
    }
    return { ok: false, empty: true };
  } catch (error) {
    return { ok: false, empty: true, error: error.message };
  }
}

// ==================== 咪咕 (MIGU) ====================
// Verified live contract (2026-09-10, from prod cloud IP):
//   Search: GET pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do
//     ?ua=Android_migu&version=5.0.1&text={kw}&pageNo=1&pageSize=N&searchSwitch={"song":1}
//     + Referer https://music.migu.cn → {code:"000000", songResultData:{totalCount, result:[...]}}
//   Stream: GET app.pd.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do?... → free track 302 Location
//     (signed mp3 URL, IP-bound to caller → 必须服务端代理，绝不直传 app);
//     VIP/无版权 → 200 JSON {"code":"200000","info":"暂不提供试听地址"}.

const MIGU_SEARCH_URL = 'https://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do';
const MIGU_LISTEN_URL = 'https://app.pd.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do';
// 匿名试听 userId（verified live contract；无登录态要求）
const MIGU_USER_ID = '15548614588710179085069';
const MIGU_CHANNEL = '014000D';

// contentId → copyrightId 解析缓存：searchMigu 填充，getMiguUrl 回查。
// listenSong.do 需要 copyrightId，但搜索→播放链路只透传 sourceId(=contentId)，
// 此处做服务端侧回填，避免改动 app 与 DB schema。
const miguCopyrightCache = new LRUCache({
  max: 500,
  ttl: 30 * 60 * 1000,
  updateAgeOnGet: true,
});

function mapMiguSong(s) {
  const singers = Array.isArray(s.singers)
    ? s.singers.map(x => x && x.name).filter(Boolean).join(' / ')
    : '';
  let cover = '';
  if (Array.isArray(s.imgItems) && s.imgItems.length > 0) {
    cover = s.imgItems[0].img || '';
  }
  const contentId = s.contentId != null ? String(s.contentId) : '';
  const copyrightId = s.copyrightId != null ? String(s.copyrightId) : '';
  if (contentId && copyrightId) miguCopyrightCache.set(contentId, copyrightId);
  return {
    id: contentId,
    name: s.name ?? '',
    artists: singers,
    album: '', // search payload 无专辑字段，统一 ""
    cover,
    duration: 0, // search payload 无单曲时长，统一 0（可用性由播放链路决定）
    vip: false, // 搜索期未知是否付费，统一 false；播放期以 200000 无试听地址为准
    _raw: { resourceType: s.resourceType ?? '', copyrightId },
  };
}

async function searchMigu(keyword, limit) {
  try {
    const resp = await axios.get(MIGU_SEARCH_URL, {
      params: {
        ua: 'Android_migu',
        version: '5.0.1',
        text: keyword,
        pageNo: 1,
        pageSize: limit,
        searchSwitch: JSON.stringify({ song: 1 }),
      },
      headers: { Referer: 'https://music.migu.cn', 'User-Agent': 'Mozilla/5.0' },
      timeout: UPSTREAM_TIMEOUT,
    });
    const body = resp.data;
    if (!body || body.code !== '000000') {
      writeLog('degradation', 'WARN', `咪咕搜索异常: code=${body && body.code}, keyword=${keyword}`);
      return [];
    }
    const list = body.songResultData && body.songResultData.result;
    if (!Array.isArray(list) || list.length === 0) {
      writeLog('degradation', 'WARN', `咪咕搜索空结果: keyword=${keyword}`);
      return [];
    }
    return list.map(mapMiguSong);
  } catch (error) {
    writeLog('degradation', 'ERROR', `咪咕搜索失败: keyword=${keyword}, ${error.message}`);
    return [];
  }
}

/**
 * 取咪咕播放地址：手动跟 302（只校验 Location 存在且为 http(s)，不下载），
 * 返回 Location URL 字符串交由后端代理字节流；无版权/异常一律返回 null，永不抛错。
 */
async function getMiguUrl(contentId, copyrightId, toneFlag = 'PQ') {
  try {
    if (contentId == null || contentId === '') return null;
    const cid = String(contentId);
    let cpid = copyrightId != null ? String(copyrightId) : '';
    if (!cpid) cpid = miguCopyrightCache.get(cid) || '';
    const resp = await axios.get(MIGU_LISTEN_URL, {
      params: {
        toneFlag: toneFlag || 'PQ',
        resourceType: 2,
        contentId: cid,
        copyrightId: cpid,
        userId: MIGU_USER_ID,
        netType: '00',
        channel: MIGU_CHANNEL,
      },
      headers: { Referer: 'https://music.migu.cn', 'User-Agent': 'Mozilla/5.0' },
      timeout: UPSTREAM_TIMEOUT,
      maxRedirects: 0, // 手动处理 302：只取 Location，不自动下载
      validateStatus: () => true,
    });
    const status = resp.status;
    if (status >= 300 && status < 400) {
      const loc = resp.headers && resp.headers.location;
      if (typeof loc === 'string' && /^https?:\/\//i.test(loc)) return loc;
      writeLog('degradation', 'WARN', `咪咕302无有效Location: contentId=${cid}, status=${status}`);
      return null;
    }
    const body = resp.data;
    if (body && (body.code === '200000' || body.code === 200000)) {
      writeLog('degradation', 'INFO', `咪咕无试听地址(付费/无版权): contentId=${cid}`);
      return null;
    }
    writeLog('degradation', 'WARN', `咪咕取链异常: contentId=${cid}, status=${status}`);
    return null;
  } catch (error) {
    writeLog('degradation', 'ERROR', `咪咕取链失败: contentId=${contentId}, ${error.message}`);
    return null;
  }
}

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
// 歌词：v5 响应无内联歌词字段 → /kugou/lyric 恒 404(形状与 /qq/lyric 同形)。
// 上游文档版本：MakcRe/KuGouMusicApi 2026-02(module/song_url.js, util/helper.js
//   signatureAndroidParams/signKey, util/request.js 默认参数/头/UA, util/config.json
//   liteAppid) + kugou_sdk 0.2.9 docs/protocol/{search,song}.md。
// 20028 本次请求需要验证 → 需 POST userservice.kugou.com/risk/v2/r_register_dev
//   注册 dfid 后重试；phase-1 未 live 撞见 20028，故仅留常量与注释，未实现。

const KUGOU_GATEWAY_URL = 'https://gateway.kugou.com';
const KUGOU_SEARCH_PATH = '/v2/search/song';
const KUGOU_SEARCH_ROUTER = 'complexsearch.kugou.com';
const KUGOU_URL_PATH = '/v5/url';
const KUGOU_URL_ROUTER = 'trackercdn.kugou.com';
// Lite profile 身份(概念版)：旋转风险——跟随客户端版本升级，变则全网关 152/签名错
const KUGOU_APPID = '3116'; // MakcRe util/config.json liteAppid
const KUGOU_SEARCH_CLIENTVER = '11520'; // 概念版 5.2.2 versionCode (kugou_sdk search.md)
const KUGOU_URL_CLIENTVER = '11430'; // MakcRe module/song_url.js 硬编码 clientver/version
// Lite 签名盐：旋转风险高——服务端可随时换盐，换则 signature/key 全错，需跟进上游文档
const KUGOU_ANDROID_SALT = 'LnT6xpN3khm36zse0QzvmgTZ3waWdRSA'; // MakcRe signatureAndroidParams lite 分支；kugou_sdk signing.rs android_salt(Lite)
const KUGOU_KEY_SALT = '185672dd44712f60bb1736df5a377e82'; // MakcRe signKey lite 分支；kugou_sdk sign_key key_salt(Lite)
const KUGOU_UA = 'Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi'; // MakcRe request.js 默认 UA
const KUGOU_PID = '411'; // Lite pid (Standard=2)
const KUGOU_CMD = '26';
const KUGOU_PIDVERSION = '3001';
const KUGOU_PAGE_ID = '967177915'; // Lite page_id (MakcRe song_url.js)
const KUGOU_PPAGE_ID = '356753938,823673182,967485191'; // Lite ppage_id
const KUGOU_SSA_FLAG = 'is_fromtrack';
const KUGOU_REGISTER_DEV_URL = 'https://userservice.kugou.com/risk/v2/r_register_dev'; // 仅 20028 时启用，phase-1 未实现
const KUGOU_PROBE_TIMEOUT = 5000; // CDN Range 探活单次上限(短，逐个试)

// 设备三元组：KUGOU_GUID/KUGOU_MID/KUGOU_DFID 环境变量持久化(运维侧一次设定)；
// 未设则进程内生成一次并全程复用——绝不逐请求再生(上游把频繁变动三元组判风控)。
let kugouDeviceCache = null;
function kugouDevice() {
  if (!kugouDeviceCache) {
    const guid = process.env.KUGOU_GUID
      || crypto.createHash('md5').update(crypto.randomUUID()).digest('hex');
    const mid = process.env.KUGOU_MID
      || BigInt(`0x${guid}`).toString(10); // 十进制串(BigInteger(MD5hex(GUID),16))
    kugouDeviceCache = { guid, mid, dfid: process.env.KUGOU_DFID || '-' };
    if (!process.env.KUGOU_GUID || !process.env.KUGOU_MID) {
      writeLog('degradation', 'WARN', '酷狗设备三元组未持久化(缺 KUGOU_GUID/KUGOU_MID)，已进程内生成；请固化到环境变量以跨重启稳定');
    }
  }
  return kugouDeviceCache;
}

function kugouMd5(s) {
  return crypto.createHash('md5').update(String(s), 'utf8').digest('hex');
}

// Android 签名：MD5(salt + 按 key ASCII 排序的 k=v 无分隔拼接 + salt)。
// v5 场景对含 key、不含 signature 的全参数集计算(GET 无 body)。
function kugouAndroidSignature(params) {
  const str = Object.keys(params).sort().map((k) => `${k}=${params[k]}`).join('');
  return kugouMd5(`${KUGOU_ANDROID_SALT}${str}${KUGOU_ANDROID_SALT}`);
}

// v5 key：MD5(lower(hash) + KeySalt + appid + mid + userid)，匿名 userid=0。
function kugouUrlKey(hashLower, mid) {
  return kugouMd5(`${hashLower}${KUGOU_KEY_SALT}${KUGOU_APPID}${mid}0`);
}

function kugouHeaders(router, device, clienttime) {
  return {
    'x-router': router,
    'User-Agent': KUGOU_UA,
    dfid: device.dfid,
    clienttime,
    mid: device.mid,
  };
}

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
    const resp = await axios.get(KUGOU_GATEWAY_URL + KUGOU_SEARCH_PATH, {
      params,
      headers: kugouHeaders(KUGOU_SEARCH_ROUTER, device, clienttime),
      timeout: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const body = resp.data;
    if (!body || body.status !== 1) {
      writeLog('degradation', 'WARN', `酷狗搜索异常: status=${body && body.status}, keyword=${keyword}`);
      return [];
    }
    const list = body.data && body.data.lists;
    if (!Array.isArray(list) || list.length === 0) {
      writeLog('degradation', 'WARN', `酷狗搜索空结果: keyword=${keyword}`);
      return [];
    }
    return list
      .filter((s) => s && /^[a-f0-9]{32}$/i.test(String(s.FileHash || s.hash || ''))
        && (s.Privilege === 0 || s.Privilege === 8))
      .map(mapKugouSong);
  } catch (error) {
    writeLog('degradation', 'ERROR', `酷狗搜索失败: keyword=${keyword}, ${error.message}`);
    return [];
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
    const resp = await axios.get(KUGOU_GATEWAY_URL + KUGOU_URL_PATH, {
      params,
      headers: kugouHeaders(KUGOU_URL_ROUTER, device, clienttime),
      timeout: UPSTREAM_TIMEOUT,
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
    writeLog('degradation', 'ERROR', `酷狗取链失败: hash=${hash}, quality=${quality}, ${error.message}`);
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
    const resp = await axios.get(url, {
      headers: { Range: 'bytes=0-1', 'User-Agent': 'Mozilla/5.0' },
      timeout: KUGOU_PROBE_TIMEOUT,
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
    writeLog('degradation', 'ERROR', `酷狗取链失败: hash=${hash}, level=${level}, ${error.message}`);
    return null;
  }
}

/**
 * 兼容 routes.js /kugou/lyric 的取链入口(该文件冻结，签名保持不变)。
 * v5 响应无内联歌词字段，恒返回 lyrics:'' → 路由按既有形状 404(与 /qq/lyric 同形)。
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
const BILI_NAV_URL = 'https://api.bilibili.com/x/web-interface/nav';
const BILI_SPI_URL = 'https://api.bilibili.com/x/frontend/finger/spi';
const BILI_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const BILI_REFERER = 'https://www.bilibili.com/';

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

// WBI 签名混淆表(固定，官方算法)；mixin key 缓存 12h(官方轮换周期)。
const BILI_MIXIN_TAB = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52];
const BILI_WBI_TTL = 12 * 60 * 60 * 1000;
const BILI_BUVID_TTL = 24 * 60 * 60 * 1000;

let biliBuvidCache = null; // { b3, b4, at }
let biliWbiCache = null; // { mixinKey, at }

function biliHeaders(buvid) {
  const headers = { Referer: BILI_REFERER, 'User-Agent': BILI_UA };
  if (buvid && (buvid.b3 || buvid.b4)) {
    const parts = [];
    if (buvid.b3) parts.push(`buvid3=${buvid.b3}`);
    if (buvid.b4) parts.push(`buvid4=${buvid.b4}`);
    headers.Cookie = parts.join('; ');
  }
  return headers;
}

/** SPI 补齐 buvid3+buvid4 双票据(缺任一项即视为失效，与 bili-music 同策略)。 */
async function ensureBiliBuvid(forceRefresh = false) {
  if (!forceRefresh && biliBuvidCache && Date.now() - biliBuvidCache.at < BILI_BUVID_TTL
    && biliBuvidCache.b3 && biliBuvidCache.b4) {
    return biliBuvidCache;
  }
  try {
    const resp = await axios.get(BILI_SPI_URL, {
      headers: { Referer: BILI_REFERER, 'User-Agent': BILI_UA },
      timeout: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const b3 = resp.data && resp.data.data && resp.data.data.b_3;
    const b4 = resp.data && resp.data.data && resp.data.data.b_4;
    if (typeof b3 === 'string' && b3 && typeof b4 === 'string' && b4) {
      biliBuvidCache = { b3, b4, at: Date.now() };
      return biliBuvidCache;
    }
    writeLog('degradation', 'WARN', 'B站 buvid SPI 返回缺票据，沿用旧票据/空票据');
  } catch (error) {
    writeLog('degradation', 'ERROR', `B站 buvid SPI 失败: ${error.message}`);
  }
  return biliBuvidCache || { b3: '', b4: '', at: 0 };
}

function biliMixinKey(imgKey, subKey) {
  const orig = `${imgKey}${subKey}`;
  let out = '';
  for (const i of BILI_MIXIN_TAB) out += orig[i] || '';
  return out.slice(0, 32);
}

/** nav 取 WBI 图 key → mixin key(缓存 12h，失败沿用旧 key)。 */
async function ensureBiliWbiKey(forceRefresh = false) {
  if (!forceRefresh && biliWbiCache && Date.now() - biliWbiCache.at < BILI_WBI_TTL) {
    return biliWbiCache.mixinKey;
  }
  try {
    const buvid = await ensureBiliBuvid();
    const resp = await axios.get(BILI_NAV_URL, {
      headers: biliHeaders(buvid),
      timeout: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const imgUrl = resp.data && resp.data.data && resp.data.data.wbi_img
      && resp.data.data.wbi_img.img_url;
    const subUrl = resp.data && resp.data.data && resp.data.data.wbi_img
      && resp.data.data.wbi_img.sub_url;
    const imgKey = typeof imgUrl === 'string' ? imgUrl.split('/').pop().replace(/\.png$/, '') : '';
    const subKey = typeof subUrl === 'string' ? subUrl.split('/').pop().replace(/\.png$/, '') : '';
    if (imgKey && subKey) {
      biliWbiCache = { mixinKey: biliMixinKey(imgKey, subKey), at: Date.now() };
      return biliWbiCache.mixinKey;
    }
    writeLog('degradation', 'WARN', 'B站 nav 未返回 WBI 图 key，沿用旧 key');
  } catch (error) {
    writeLog('degradation', 'ERROR', `B站 WBI key 获取失败: ${error.message}`);
  }
  return biliWbiCache ? biliWbiCache.mixinKey : null;
}

/** WBI 签名：参数排序 + 过滤 !"'( )* + wts，query+mixin md5 得 w_rid。 */
function biliSignWbi(params, mixinKey) {
  const wts = Math.round(Date.now() / 1000);
  const all = { ...params, wts };
  const keys = Object.keys(all).sort();
  const filtered = {};
  for (const k of keys) filtered[k] = String(all[k]).replace(/[!'()*]/g, '');
  const query = new URLSearchParams(filtered).toString();
  const wRid = crypto.createHash('md5').update(query + mixinKey).digest('hex');
  return { ...filtered, w_rid: wRid, wts };
}

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

function stripBiliEm(s) {
  return String(s != null ? s : '').replace(/<em[^>]*>/gi, '').replace(/<\/em>/gi, '');
}

// ==================== B站视频标题解析 ====================
// 背景：B站投稿标题是“视频标题”(Uploader 自由填写)，直接透传会导致
//   歌名=整句标题、歌手=UP主(如 name='【Hi-Res】｜《晴天》- 周杰伦…'、artists='VV音乐局')。
// parseBiliTitle 把常见投稿格式解析为标准 {song, artist}：
//   - 《歌名》优先：'梦然-《少年》官方版'→少年/梦然；'周杰伦——《晴天》'→晴天/周杰伦；
//     '歌手《歌名》'→按 pre 取歌手；多个《》并存(如广告混剪)判歧义→回退原样(由播放量门控沉底)。
//   - 无《》时按分隔符拆两段：'歌名 - 歌手翻唱'(Y 尾为翻唱/cover/演唱)→歌名/歌手；
//     '歌名完整版 - 歌手'(X 尾为纯版本词)→歌名/歌手；任一段等于 UP 主名→另一段为歌名、
//     UP 主为歌手；否则按最常见的'歌手 - 歌名'假设(Y 尾版本词照剥)。
//     切分只认两侧带空格的半角连字符(保 'Hi-Res' 不裂)与全角/长破折号。
//   - 【…】/[…]/(…)/〈…〉包标签：仅当括号内命中 tag 词(翻唱/cover/live/官方/MV/OST/4K/无损…)
//     才剥除(保守：'【晴天】周杰伦'这类不剥，避免误删歌名)；数学粗体等杂体经 NFKC 归一。
//   - 歌手候选校验：长度 1–12、拒指示词/视频词(这/那/才是/原版/合集/「」引号…)，
//     不合格→回退 UP 主(如'这才是《晴天》原版MV！'→晴天/UP主)。
//   - 绝不返回空 name/artists：解析失败一律回退现行行为(name=去 em 标签原标题、artists=UP主)。
function parseBiliTitle(rawTitle, author) {
  const fallbackName = stripBiliEm(rawTitle).trim();
  const fallbackArtist = author != null ? String(author) : '';
  if (!fallbackName) return { name: '', artists: fallbackArtist };
  // OST 元数据书名号先剥：'她说 (电视剧《北上》主题曲)'中《北上》是剧名非歌名，
  // 仅剥紧邻主题曲/片尾曲/插曲/OST/OP/ED 的《》(真歌名《》不受影响)
  const t = stripBiliOstBooks(fallbackName.normalize('NFKC'));

  // 切分：ascii 连字符要求至少一侧空格(保 'Hi-Res' 类词内连字符不断裂)，
  // 全角/长破折号恒为分隔符。'》- 周'这类半角形态同样切开。
  const parts = t.split(/\s+[–—－—-]\s*|\s*[–—－—-]\s+|[–—－—]+/).map((p) => p.trim()).filter(Boolean);

  const withBook = parts.map((p) => ({ part: p, books: matchBookTitles(p) }));
  const hitParts = withBook.filter((x) => x.books.length > 0);
  if (hitParts.length === 1 && hitParts[0].books.length === 1) {
    const song = cleanBiliToken(hitParts[0].books[0]);
    if (song && song.length <= 40) {
      const idx = parts.indexOf(hitParts[0].part);
      const artist = pickBiliArtist(parts, idx, song, fallbackArtist);
      if (artist) return { name: song, artists: artist };
    }
    return { name: fallbackName, artists: fallbackArtist };
  }
  if (hitParts.length > 1 || hitParts.some((x) => x.books.length > 1)) {
    return { name: fallbackName, artists: fallbackArtist }; // 多《》并存判歧义，回退
  }

  // 无《》：两段式 dash 规则(先角色词，再版本词，最后默认歌手前置)
  if (parts.length === 2) {
    const [x, y] = parts.map((p) => stripBiliTags(p).trim());
    // '歌名 - 歌手翻唱'：Y 尾为演唱者角色词 → X=歌，剥词后 Y=歌手
    const ySinger = stripBiliSuffix(y, BILI_SINGER_ROLE_RE);
    if (ySinger.stripped && ySinger.token) {
      const song = cleanBiliToken(x);
      if (song && song.length <= 40) return { name: song, artists: ySinger.token };
    }
    if (x === fallbackArtist && y) return { name: cleanBiliToken(y) || fallbackName, artists: fallbackArtist };
    if (y === fallbackArtist && x) return { name: cleanBiliToken(x) || fallbackName, artists: fallbackArtist };
    // '歌名完整版 - 歌手'：X 尾为纯版本词(非演唱角色)→X=歌，Y=歌手
    const xVer = stripBiliSuffix(x, BILI_VERSION_RE);
    if (xVer.stripped && xVer.token && !stripBiliSuffix(y, BILI_ROLE_SUFFIX_RE).stripped) {
      const artist = cleanBiliToken(y);
      if (xVer.token.length <= 40 && artist && isBiliArtistSane(artist)) {
        return { name: xVer.token, artists: artist };
      }
    }
    // 默认：最常见的'歌手 - 歌名'假设(Y 尾版本词照剥，如'…晴天MV 2160P修复版'→'晴天')
    const artist = cleanBiliToken(stripBiliSuffix(x, BILI_ROLE_SUFFIX_RE).token);
    const song = cleanBiliToken(stripBiliSuffix(y, BILI_ROLE_SUFFIX_RE).token);
    if (artist && song && isBiliArtistSane(artist) && song.length <= 40) {
      return { name: song, artists: artist };
    }
    return { name: fallbackName, artists: fallbackArtist };
  }

  // 单段：剥包标签 + 尾部版本词(如'少年完整版'→'少年')，歌手回退 UP 主
  if (parts.length === 1) {
    const song = cleanBiliToken(stripBiliSuffix(stripBiliTags(parts[0]).trim(), BILI_ROLE_SUFFIX_RE).token);
    if (song && song.length <= 40) return { name: song, artists: fallbackArtist };
  }
  return { name: fallbackName, artists: fallbackArtist };
}

/** 提取《…》书名号片段(内部 1–30 字，Lyrics 引用尾巴超长的不算)。 */
function matchBookTitles(s) {
  const out = [];
  const re = /《([^》]{1,30})》/g;
  let m;
  while ((m = re.exec(s)) !== null) out.push(m[1].trim());
  return out.filter(Boolean);
}

/** 剥紧邻 OST 类词的《》(剧名/番名)，真歌名《》保留。 */
function stripBiliOstBooks(s) {
  return String(s)
    .replace(/《[^》]{1,30}》(?=[\s:：]{0,4}(主题曲|片尾曲|插曲|OST|OP|ED|预告|先导))/g, '')
    .replace(/(主题曲|片尾曲|插曲|OST|OP|ED)[\s:：]{0,4}《[^》]{1,30}》/g, '$1');
}

// 包标签 tag 词：命中才剥括号(保守，避免'【晴天】'这类歌名括号被误删)
const BILI_TAG_WORDS = ['翻唱', 'cover', 'live', '现场', '官方', '正式版', '完整版', '完整',
  'mv', 'm/v', 'ost', '主题曲', '片尾曲', '插曲', 'op', 'ed', '4k', '修复', '无损', 'hi-res',
  'hires', '杜比', '高清', 'hd', '歌词', '字幕', 'remix', 'dj', '伴奏', '纯享', '直拍',
  '饭拍', '安利', '循环', '试听', '先行', '预告', '单曲', 'ep', 'pv'];
const BILI_BRACKET_RE = /【([^【】]{1,30})】|\[([^\[\]]{1,30})\]|\(([^()]{1,30})\)|〈([^〈〉]{1,30})〉/g;

/** 剥除命中 tag 词的包标签括号；不命中则原样保留。 */
function stripBiliTags(s) {
  let prev;
  let out = String(s);
  do {
    prev = out;
    out = out.replace(BILI_BRACKET_RE, (full, a, b, c, d) => {
      const inner = (a ?? b ?? c ?? d ?? '').toLowerCase();
      if (!inner.trim()) return '';
      if (BILI_TAG_WORDS.some((w) => inner.includes(w))) return '';
      return full;
    });
  } while (out !== prev);
  return out;
}

// 尾部角色/版本词：'周杰伦翻唱'→'周杰伦'、'晴天MV 2160P修复版'→'晴天'
const BILI_ROLE_SUFFIX_RE = /(翻唱|cover|演唱|live|现场版?|官方版?|正式版|完整版|mv|m\/v|修复版|无损版?|高清版?|歌词版?|字幕版|主题曲|片尾曲|插曲|ost|试听版|先行曲|预告版|\d{3,4}p|4k|hd)$/i;
// 演唱者角色词('歌名 - 歌手翻唱'判定用)：仅翻唱/cover/演唱。
// live/现场刻意排除：'歌手 - 歌名live'中 live 绝大多数是版本后缀(歌手前置更常见)，
// 若 live 也判演唱者会导致其被反转为歌名；裸'歌名 - 歌手live'(无括号)为已知残留歧义，
// 按歌手前置假设处理(括号形态【Live】仍经包标签正常剥除)。
const BILI_SINGER_ROLE_RE = /(翻唱|cover|演唱)$/i;
// 纯版本词('歌名完整版 - 歌手'判定用)：不含演唱角色词
const BILI_VERSION_RE = /(官方版?|正式版|完整版|mv|m\/v|修复版|无损版?|高清版?|歌词版?|字幕版|主题曲|片尾曲|插曲|ost|试听版|先行曲|预告版|\d{3,4}p|4k|hd)$/i;

/** 迭代剥指定尾部词；返回 {token, stripped}。 */
function stripBiliSuffix(s, re) {
  let token = String(s).trim();
  let stripped = false;
  let prev;
  do {
    prev = token;
    token = token.replace(re, '').trim();
    token = token.replace(/[-–—·•\s]+$/, '').trim();
    if (token !== prev) stripped = true;
  } while (token !== prev && token);
  return { token, stripped };
}

/** 兼容旧名：默认按全量角色/版本词表剥离。 */
function stripBiliRoleSuffix(s) {
  return stripBiliSuffix(s, BILI_ROLE_SUFFIX_RE);
}

// 歌手候选校验：拒指示词/视频残留词与引号标点
const BILI_ARTIST_STOP_RE = /[这那哪最才是否了在有和与及？！?!「」『』"'"‘’“”…·・—…]|原版|才是|视频|合集|盘点|连播|串烧|混剪|广告|循环|单曲|电影|电视剧|连续剧|动漫|游戏/;
function isBiliArtistSane(a) {
  if (!a || a.length > 12) return false;
  return !BILI_ARTIST_STOP_RE.test(a);
}

/** emoji/杂符清理(保守：只清 emoji 与控制符，保留 CJK/标点供上层校验)。 */
function cleanBiliToken(s) {
  return String(s != null ? s : '')
    .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE00}-\u{FEFF}\u{2000}-\u{206F}]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 在 dash 切分 parts 中为含《》的 part 找邻位歌手：先左后右，不合格回退 UP 主。 */
function pickBiliArtist(parts, idx, song, fallbackArtist) {
  const order = [];
  if (idx > 0) order.push(parts[idx - 1]);
  if (idx < parts.length - 1) order.push(parts[idx + 1]);
  // 单 part 内'歌手《歌名》尾巴'形态：pre/post 同为候选(pre 优先)
  if (parts.length === 1) {
    const m = /^([\s\S]*?)《[^》]{1,30}》([\s\S]*)$/.exec(parts[0]);
    if (m) order.push(m[1], m[2]);
  }
  for (const cand of order) {
    let c = stripBiliTags(cand);
    c = c.split(/[｜|]/).filter((seg) => stripBiliTags(seg).trim()).join(' ');
    c = cleanBiliToken(stripBiliSuffix(c.trim(), BILI_ROLE_SUFFIX_RE).token);
    c = c.replace(/^[-–—·•\s'\"‘’“”]+|[-–—·•\s'\"‘’“”]+$/g, '').trim();
    if (c && c !== song && isBiliArtistSane(c)) return c;
  }
  return fallbackArtist || '';
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
    const resp = await axios.get(BILI_SEARCH_URL, {
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
      timeout: UPSTREAM_TIMEOUT,
      validateStatus: () => true,
    });
    const body = resp.data;
    if (!body || body.code !== 0) {
      writeLog('degradation', 'WARN', `B站搜索异常: code=${body && body.code}, keyword=${keyword}`);
      return [];
    }
    const list = body.data && body.data.result;
    if (!Array.isArray(list) || list.length === 0) {
      writeLog('degradation', 'WARN', `B站搜索空结果: keyword=${keyword}`);
      return [];
    }
    return list
      .filter((s) => s && /^BV[a-zA-Z0-9]+$/.test(String(s.bvid || '')))
      .map(mapBiliVideo)
      .filter(isBiliSingle)
      .slice(0, limit);
  } catch (error) {
    writeLog('degradation', 'ERROR', `B站搜索失败: keyword=${keyword}, ${error.message}`);
    return [];
  }
}

/** pagelist 解析 bvid → { cid(P1), duration(秒) }；失败返回 null，永不抛错。
 * (刻意不用 web-interface/view：其对匿名 egress 回 -412；pagelist 无需 buvid/WBI。) */
async function getBiliView(bvid) {
  try {
    const buvid = await ensureBiliBuvid();
    const resp = await axios.get(BILI_PAGELIST_URL, {
      params: { bvid, jsonp: 'jsonp' },
      headers: biliHeaders(buvid),
      timeout: UPSTREAM_TIMEOUT,
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
    writeLog('degradation', 'ERROR', `B站 pagelist 失败: bvid=${bvid}, ${error.message}`);
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
      const resp = await axios.get(BILI_PLAYURL_URL, {
        params: signed,
        headers: biliHeaders(buvid),
        timeout: UPSTREAM_TIMEOUT,
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
    writeLog('degradation', 'ERROR', `B站取链失败: id=${id}, ${error.message}`);
    return null;
  }
}

module.exports = {
  UPSTREAM_TIMEOUT,
  SEARCH_TOTAL_BUDGET,
  withTimeout,
  searchCache,
  urlCache,
  searchNetease,
  buildQQSearchRequest,
  probeQQSearch,
  searchQQ,
  mapQQSong,
  searchMigu,
  mapMiguSong,
  getMiguUrl,
  miguCopyrightCache,
  searchKugou,
  mapKugouSong,
  getKugouPlayData,
  getKugouUrl,
  kugouDevice,
  kugouAndroidSignature,
  kugouUrlKey,
  pickKugouUrls,
  searchBili,
  mapBiliVideo,
  parseBiliTitle,
  parseBiliDuration,
  isBiliSingle,
  BILI_SINGLE_MAX_DURATION_MS,
  BILI_COMPILATION_KEYWORDS,
  BILI_MUSIC_TID,
  getBiliUrl,
  getBiliView,
  isBiliTrackId,
};