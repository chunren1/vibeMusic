// 咪咕音乐源适配器（搜索 + 取链）
//
// 2026-10-27 网关重构 pass 2：从 search.js 原样搬出，**行为零变更**。
// 取链仍是"手动跟 302 只取 Location"，绝不下载字节（字节流由后端代理）。

const { LRUCache } = require('lru-cache');
const { upstreamGet } = require('../upstream');
const { writeLog } = require('../logger');
const { okOutcome, failOutcome } = require('../source-health');
const { UPSTREAM_TIMEOUT, logSafe } = require('./shared');

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
    const resp = await upstreamGet(MIGU_SEARCH_URL, {
      label: 'migu/search',
      params: {
        ua: 'Android_migu',
        version: '5.0.1',
        text: keyword,
        pageNo: 1,
        pageSize: limit,
        searchSwitch: JSON.stringify({ song: 1 }),
      },
      headers: { Referer: 'https://music.migu.cn', 'User-Agent': 'Mozilla/5.0' },
      timeoutMs: UPSTREAM_TIMEOUT,
    });
    const body = resp.data;
    if (!body || body.code !== '000000') {
      writeLog('degradation', 'WARN', `咪咕搜索异常: code=${body && body.code}, keyword=${logSafe(keyword)}`);
      return failOutcome('migu', 'upstream-code=' + (body && body.code));
    }
    const list = body.songResultData && body.songResultData.result;
    if (!Array.isArray(list) || list.length === 0) {
      writeLog('degradation', 'WARN', `咪咕搜索空结果: keyword=${logSafe(keyword)}`);
      return [];
    }
    return okOutcome('migu', list.map(mapMiguSong));
  } catch (error) {
    writeLog('degradation', 'ERROR', `咪咕搜索失败: keyword=${logSafe(keyword)}, ${logSafe(error.message)}`);
    return failOutcome('migu', error.message);
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
    const resp = await upstreamGet(MIGU_LISTEN_URL, {
      label: 'migu/listen',
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
      timeoutMs: UPSTREAM_TIMEOUT,
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
    writeLog('degradation', 'ERROR', `咪咕取链失败: contentId=${contentId}, ${logSafe(error.message)}`);
    return null;
  }
}

module.exports = {
  searchMigu,
  mapMiguSong,
  getMiguUrl,
  miguCopyrightCache,
};
