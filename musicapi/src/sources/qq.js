// QQ 音乐源适配器（搜索 + 健康探针）
//
// 2026-10-27 网关重构 pass 2：从 search.js 原样搬出，**行为零变更**。
// 与网易云同一套口径：失败记 failOutcome、成功记 okOutcome、空结果不记（见文件内注释）。
//
// 循环 require 提醒：本文件被 search.js 加载时，cookie.js 可能尚未执行完
// （cookie.js → cookie-probe.js → sources/netease.js → cookie.js 这条链）。
// 因此 getQQCookieString 一律**调用时**再 require，不要提到顶层解构。

const { upstreamGet } = require('../upstream');
const { writeLog } = require('../logger');
const { okOutcome, failOutcome } = require('../source-health');
const { UPSTREAM_TIMEOUT, withTimeout, logSafe } = require('./shared');
const config = require('../config-loader');

/** cookie.js 延迟加载（见文件头循环 require 说明）。 */
function cookieHelper() {
  return require('../cookie');
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

/**
 * QQ 搜索必须带登录态。
 *
 * <p>2026-11-13 A/B 实测：同一 IP、同一时刻，匿名请求上游照常回 code:0，
 * 但 song.list 恒为 0 条；带上 Cookie 后连发 5 个关键词（间隔 300ms）全部拿到 15 条。
 * 也就是说搜索不是被限频，而是**匿名返回空**——线上「QQ 永远 0 首」的根因在此。
 *
 * <p>Cookie 走 cookieHelper() 在调用时取（原因见文件头循环 require 说明），
 * 取不到时按匿名继续：宁可少一个源，也不要让搜索整体失败。
 */
function attachQQCookie(headers) {
  try {
    const cookieStr = cookieHelper().getQQCookieString();
    if (cookieStr) headers.Cookie = cookieStr;
  } catch (e) {
    writeLog('cookie', 'WARN', `QQ 搜索附加 Cookie 失败，按匿名继续: ${e.message}`);
  }
  return headers;
}

async function searchQQ(keyword, limit) {
  // QQ 源总开关（2026-10-27）：会员到期后该源大量曲目不可播，运营上先整体跳过。
  // 拦在这里而不是 routes 调用点 —— 聚合搜索与单源端点 /qq/search 都走本函数，一处覆盖。
  // 代码、端点、取链、歌词全部保留；MUSICAPI_QQ_ENABLED=true 重启即恢复。
  // 刻意不记账（既不算成功也不算失败）：停用不是故障，不该污染熔断计数与告警。
  if (!config.isQQEnabled()) return [];

  const { url, params, headers } = buildQQSearchRequest(keyword, limit);
  attachQQCookie(headers);
  try {
    const resp = await upstreamGet(url, {
      label: 'qq/search',
      params,
      headers,
      timeoutMs: UPSTREAM_TIMEOUT,
    });
    const list = resp.data?.search?.data?.body?.song?.list;
    // 口径修正（2026-10-27）：这里原本只有一条分支，把「上游报错」和「查无结果」混为一谈。
    // 实测 QQ 的成功响应就是 code:0（见 test/search-qq.test.js 的 NEW_SAMPLE），
    // 而「查无结果」在旧生产 18 小时里出现了 11 次（周杰伦/陈奕迅/民谣/轻音乐…）。
    // 若一律记失败，连续 3 个冷门词就会把 QQ 熔断 60s，属于典型误熔断。
    // 现在按码分流：成功码 + 空列表 = 空结果（不计账），其余码 = 真异常（计账）。
    const codeOk = resp.data?.code === 0 || resp.data?.code === '0' || resp.data?.code === '000000';
    if (!Array.isArray(list) || list.length === 0) {
      if (codeOk) {
        writeLog('degradation', 'WARN', `QQ搜索空结果: keyword=${logSafe(keyword)}`);
        return [];
      }
      writeLog('degradation', 'WARN', `QQ搜索异常: code=${resp.data?.code}, keyword=${logSafe(keyword)}`);
      return failOutcome('qq', 'upstream-code=' + (resp.data?.code ?? 'null'));
    }
    return okOutcome('qq', list.map(mapQQSong));
  } catch (error) {
    writeLog('degradation', 'ERROR', `QQ搜索失败: keyword=${logSafe(keyword)}, ${logSafe(error.message)}`);
    return failOutcome('qq', error.message);
  }
}

// 健康探针：与 searchQQ 同一一条直连接口，只校验期望结构（song 节点为对象），
// 空结果不视为失效——上游风控偶发空列表时不再误报 Cookie 失效。
async function probeQQSearch() {
  const { url, params, headers } = buildQQSearchRequest('周杰伦', 1);
  // 健康探针必须与真实搜索同口径：不补 Cookie，探针会因匿名请求恒空而误报
  // "✅ QQ音乐 Cookie 正常"（历史上就是这样掩盖了搜索不带 Cookie 的问题）。
  attachQQCookie(headers);
  try {
    const resp = await withTimeout(
      upstreamGet(url, { label: 'qq/health-probe', params, headers, timeoutMs: UPSTREAM_TIMEOUT }),
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

module.exports = {
  mapQQSong,
  buildQQSearchRequest,
  searchQQ,
  probeQQSearch,
};
