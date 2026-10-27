// ==================== QQ 音乐 Cookie：检查 / 重载 / 请求头 ====================
//
// 2026-10-27 网关重构 pass 3：从 cookie.js 拆出，**行为零变更**。
// 无 Cookie 是**正常模式**（QQ 搜索/取链/歌词均支持无登录态），不是异常：
// 因此本文件所有失败分支都要先判 hasQQCookie()，绝不能把"没配"打成 ERROR。
const path = require('path');
const config = require('./config-loader');
const { writeLog } = require('./logger');
const { cookieStatus } = require('./cookie-slots');
const { cookieStatusGauge } = require('./metrics');

/** 是否配置了 QQ Cookie（无 Cookie = 正常无登录模式，不视为异常） */
function hasQQCookie() {
  return !!config.qq && Object.keys(config.qq).length > 0;
}

if (hasQQCookie()) {
  writeLog('cookie', 'INFO', `QQ音乐 Cookie 已加载 (${Object.keys(config.qq).length} 个字段)`);
} else {
  writeLog('cookie', 'INFO', 'QQ音乐 Cookie 未配置，以无 Cookie 模式运行（正常情况，无需恢复）');
}

async function checkQQCookie() {
  // 无 Cookie 是正常运行模式：QQ 搜索/取链/歌词均支持无 Cookie，直接标记可用，不打 ERROR、不引导恢复
  if (!hasQQCookie()) {
    cookieStatus.qq = true;
    cookieStatusGauge.set({ platform: 'qq' }, 1);
    writeLog('cookie', 'INFO', 'QQ音乐无 Cookie 模式（正常，无需恢复）');
    return;
  }
  try {
    // 与真实搜索同一条直连接口（search.js probeQQSearch），只校验期望结构；
    // 空结果属上游正常返回，不标记失效（此前按"有结果"判定，风控空列表时误报）。
    // 延迟 require：search.js 顶层依赖本模块，顶层互引会形成循环。
    const { probeQQSearch } = require('./search');
    const probe = await probeQQSearch();
    if (probe.ok) {
      cookieStatus.qq = true;
      cookieStatusGauge.set({ platform: 'qq' }, 1);
      writeLog('cookie', 'INFO', probe.empty ? '✅ QQ音乐链路正常（结构有效，本次空结果）' : '✅ QQ音乐 Cookie 正常');
      return;
    }
    failQQCookie(probe.error || '直连接口结构异常');
  } catch (e) {
    failQQCookie(e.message);
  }
}

function failQQCookie(reason) {
  // 无 Cookie 模式下不视为失败：保持可用，不打 ERROR、不引导恢复
  if (!hasQQCookie()) {
    cookieStatus.qq = true;
    cookieStatusGauge.set({ platform: 'qq' }, 1);
    writeLog('cookie', 'INFO', `QQ音乐无 Cookie 模式（${reason}，属正常情况）`);
    return;
  }
  cookieStatus.qq = false;
  cookieStatusGauge.set({ platform: 'qq' }, 0);
  writeLog('cookie', 'ERROR', `❌ QQ音乐 Cookie 异常: ${reason}`);
  writeLog('cookie', 'WARN', '💡 请在终端运行: node scripts/get_qq_cookie.mjs  或调用 GET /refresh-qq-cookie');
}

/** 从环境变量 / .env 重新加载 QQ Cookie（无需重启 musicapi；未配置时静默 no-op，绝不抛错） */
function reloadQQCookie() {
  try {
    // 优先进程环境变量（server.js 已用 dotenv 加载根 .env），缺失再读文件
    const fromEnv = process.env.MUSIC_QQ_COOKIE;
    if (fromEnv) {
      const cookieJson = JSON.parse(fromEnv.trim());
      config.qq = cookieJson;
      writeLog('cookie', 'INFO', `重载 QQ Cookie: ${Object.keys(cookieJson).length} 个字段`);
      return true;
    }
    const fs = require('fs');
    const envPath = path.resolve(__dirname, '..', '..', '.env');
    let envContent;
    try {
      envContent = fs.readFileSync(envPath, 'utf8');
    } catch (e) {
      writeLog('cookie', 'INFO', '.env 不存在且无 MUSIC_QQ_COOKIE，跳过重载（无 Cookie 模式正常）');
      return false;
    }
    const match = envContent.match(/MUSIC_QQ_COOKIE=(.+)/);
    if (!match) {
      writeLog('cookie', 'INFO', '.env 中未找到 MUSIC_QQ_COOKIE，跳过重载（无 Cookie 模式正常）');
      return false;
    }
    const cookieRaw = match[1].trim();
    const cookieJson = JSON.parse(cookieRaw);
    config.qq = cookieJson;
    writeLog('cookie', 'INFO', `重载 QQ Cookie: ${Object.keys(cookieJson).length} 个字段`);
    return true;
  } catch (e) {
    writeLog('cookie', 'ERROR', `重载 Cookie 失败: ${e.message}`);
    return false;
  }
}

/** 构建 QQ Cookie 请求头字符串（/song/url/qq 与 /qq/lyric 共用） */
function getQQCookieString() {
  return Object.entries(config.qq || {})
    .filter(([, v]) => typeof v === 'string' && v && !String(v).includes(','))
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

module.exports = {
  hasQQCookie,
  checkQQCookie,
  failQQCookie,
  reloadQQCookie,
  getQQCookieString,
};
