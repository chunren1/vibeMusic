// B站 guest 票据与 WBI 签名（bili.js 的支撑模块）
//
// 2026-10-27 网关重构 pass 2：从 search.js 原样搬出，**行为零变更**。
// 为什么单独成文件：票据获取（SPI 双票据 / nav 取图 key）与签名是**有状态 + 有 TTL**的一块，
// 和取流/搜索逻辑混在一起会让 bili.js 超过 250 纯 LOC；且它只被 bili.js 使用。
//
// 状态语义：buvid 缓存 24h、mixin key 缓存 12h（官方轮换周期）。
// 获取失败一律**沿用旧票据**而不是清空 —— 上游抖动不该把可用票据丢掉。

const crypto = require('crypto');
const { upstreamGet } = require('../upstream');

// 票据层用到的上游端点与请求身份（原在 search.js 的 B站常量段，随函数一起搬过来）。
const BILI_NAV_URL = 'https://api.bilibili.com/x/web-interface/nav';
const BILI_SPI_URL = 'https://api.bilibili.com/x/frontend/finger/spi';
const BILI_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const BILI_REFERER = 'https://www.bilibili.com/';
const { writeLog } = require('../logger');
const { UPSTREAM_TIMEOUT, logSafe } = require('./shared');

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
    const resp = await upstreamGet(BILI_SPI_URL, {
      label: 'bili/spi',
      headers: { Referer: BILI_REFERER, 'User-Agent': BILI_UA },
      timeoutMs: UPSTREAM_TIMEOUT,
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
    writeLog('degradation', 'ERROR', `B站 buvid SPI 失败: ${logSafe(error.message)}`);
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
    const resp = await upstreamGet(BILI_NAV_URL, {
      label: 'bili/nav',
      headers: biliHeaders(buvid),
      timeoutMs: UPSTREAM_TIMEOUT,
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
    writeLog('degradation', 'ERROR', `B站 WBI key 获取失败: ${logSafe(error.message)}`);
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

module.exports = {
  biliHeaders,
  ensureBiliBuvid,
  ensureBiliWbiKey,
  biliSignWbi,
};
