// ==================== Cookie 统一管理 ====================
const path = require('path');
const qqMusic = require('qq-music-api');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const config = require('./config');
const { writeLog } = require('./logger');
const { cookieStatusGauge } = require('./metrics');

// QQ音乐 Cookie（进程级全局设置）
qqMusic.setCookie(config.qq);
writeLog('cookie', 'INFO', 'QQ音乐 Cookie 已加载');

// 网易云 Cookie（注入到每次 API 调用的请求参数中）
const NETEASE_COOKIE = config.netease;
writeLog('cookie', 'INFO', `网易云 Cookie 已加载 (长度: ${NETEASE_COOKIE ? NETEASE_COOKIE.length : 0})`);

// Cookie 存活状态（随 API 启动自动运行）
let cookieStatus = { netease: true, qq: true };
// Prometheus gauge 初始值：假设 Cookie 可用，checkCookies 会更新为实际值
cookieStatusGauge.set({ platform: 'netease' }, 1);
cookieStatusGauge.set({ platform: 'qq' }, 1);

/** 给网易云 API 参数注入 cookie */
function withNeteaseCookie(extra = {}) {
  return { ...extra, cookie: NETEASE_COOKIE };
}

async function checkCookies() {
  writeLog('cookie', 'INFO', '🔄 Cookie 存活检查开始...');

  // 检查网易云
  try {
    const neRes = await NeteaseCloudMusicApi.cloudsearch(withNeteaseCookie({ keywords: '周杰伦', limit: 1, type: 1 }));
    if (neRes.body.code === 200 && neRes.body.result) {
      cookieStatus.netease = true;
      cookieStatusGauge.set({ platform: 'netease' }, 1);
      writeLog('cookie', 'INFO', '✅ 网易云 Cookie 正常');
    } else {
      cookieStatus.netease = false;
      cookieStatusGauge.set({ platform: 'netease' }, 0);
      writeLog('cookie', 'ERROR', `❌ 网易云 Cookie 异常: ${JSON.stringify(neRes.body).slice(0, 200)}`);
    }
  } catch (e) {
    cookieStatus.netease = false;
    cookieStatusGauge.set({ platform: 'netease' }, 0);
    writeLog('cookie', 'ERROR', `❌ 网易云 Cookie 检查失败: ${e.message}`);
  }

  // 检查QQ（含自动恢复）
  await checkQQCookie();
}

// ==================== QQ Cookie 检查 + 手动恢复引导 ====================

async function checkQQCookie() {
  try {
    const qqRes = await qqMusic.api('search', { key: '周杰伦', limit: 1 });
    if (qqRes && qqRes.list && qqRes.list.length > 0) {
      cookieStatus.qq = true;
      cookieStatusGauge.set({ platform: 'qq' }, 1);
      writeLog('cookie', 'INFO', '✅ QQ音乐 Cookie 正常');
      return;
    }
    failQQCookie('无搜索结果');
  } catch (e) {
    failQQCookie(e.message);
  }
}

function failQQCookie(reason) {
  cookieStatus.qq = false;
  cookieStatusGauge.set({ platform: 'qq' }, 0);
  writeLog('cookie', 'ERROR', `❌ QQ音乐 Cookie 异常: ${reason}`);
  writeLog('cookie', 'WARN', '💡 请在终端运行: node scripts/get_qq_cookie.mjs  或调用 GET /refresh-qq-cookie');
}

/** 从 .env 重新加载 QQ Cookie（无需重启 musicapi） */
function reloadQQCookie() {
  try {
    const fs = require('fs');
    const envPath = path.resolve(__dirname, '..', '..', '.env');
    const envContent = fs.readFileSync(envPath, 'utf8');
    const match = envContent.match(/MUSIC_QQ_COOKIE=(.+)/);
    if (!match) {
      writeLog('cookie', 'ERROR', '.env 中未找到 MUSIC_QQ_COOKIE');
      return false;
    }
    const cookieRaw = match[1].trim();
    const cookieJson = JSON.parse(cookieRaw);
    config.qq = cookieJson;
    qqMusic.setCookie(cookieJson);  // ← 关键：重新注入到 qq-music-api
    writeLog('cookie', 'INFO', `重载 QQ Cookie: ${Object.keys(cookieJson).length} 个字段`);
    return true;
  } catch (e) {
    writeLog('cookie', 'ERROR', `重载 Cookie 失败: ${e.message}`);
    return false;
  }
}

/** 构建 QQ Cookie 请求头字符串（/song/url/qq 与 /qq/lyric 共用） */
function getQQCookieString() {
  return Object.entries(config.qq)
    .filter(([, v]) => typeof v === 'string' && v && !String(v).includes(','))
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

module.exports = {
  cookieStatus,
  withNeteaseCookie,
  checkCookies,
  checkQQCookie,
  failQQCookie,
  reloadQQCookie,
  getQQCookieString,
};