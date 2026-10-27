// 酷狗 Lite profile 的协议常量 + 设备身份 + 签名算法（kugou.js 的支撑模块）
//
// 2026-10-27 网关重构 pass 2：从 search.js 原样搬出，**行为零变更**。
// 为什么单独成文件：这些常量是**上游协议的一部分**（盐/版本号/pid 全是"旋转风险"项），
// 和搜索/取链逻辑混放会让 kugou.js 超过 250 纯 LOC；而它们本身只服务签名与请求头。
//
// 维护提醒：KUGOU_APPID / CLIENTVER / SALT 跟随上游客户端版本，变则全网关签名错（152）。
// 改动前先看 kugou.js 顶部那段 live contract 注释。

const crypto = require('crypto');
const { writeLog } = require('../logger');

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

module.exports = {
  KUGOU_GATEWAY_URL,
  KUGOU_SEARCH_PATH,
  KUGOU_SEARCH_ROUTER,
  KUGOU_URL_PATH,
  KUGOU_URL_ROUTER,
  KUGOU_APPID,
  KUGOU_SEARCH_CLIENTVER,
  KUGOU_URL_CLIENTVER,
  KUGOU_PID,
  KUGOU_CMD,
  KUGOU_PIDVERSION,
  KUGOU_PAGE_ID,
  KUGOU_PPAGE_ID,
  KUGOU_SSA_FLAG,
  KUGOU_PROBE_TIMEOUT,
  kugouDevice,
  kugouAndroidSignature,
  kugouUrlKey,
  kugouHeaders,
};
