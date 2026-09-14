// ==================== Cookie 统一管理 ====================
const path = require('path');
const qqMusic = require('qq-music-api');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const config = require('./config-loader');
const { writeLog } = require('./logger');
const { cookieStatusGauge } = require('./metrics');

// QQ音乐 Cookie（进程级全局设置；Cookie 为可选项，无 Cookie 即无登录模式，属正常情况）
qqMusic.setCookie(config.qq || {});
if (hasQQCookie()) {
  writeLog('cookie', 'INFO', `QQ音乐 Cookie 已加载 (${Object.keys(config.qq).length} 个字段)`);
} else {
  writeLog('cookie', 'INFO', 'QQ音乐 Cookie 未配置，以无 Cookie 模式运行（正常情况，无需恢复）');
}

// 网易云 Cookie（注入到每次 API 调用的请求参数中）
const NETEASE_COOKIE = config.netease;
writeLog('cookie', 'INFO', `网易云 Cookie 已加载 (长度: ${NETEASE_COOKIE ? NETEASE_COOKIE.length : 0})`);

// Cookie 存活状态（随 API 启动自动运行）
let cookieStatus = { netease: true, qq: true };
// Prometheus gauge 初始值：假设 Cookie 可用，checkCookies 会更新为实际值
cookieStatusGauge.set({ platform: 'netease' }, 1);
cookieStatusGauge.set({ platform: 'qq' }, 1);

/** 是否配置了 QQ Cookie（无 Cookie = 正常无登录模式，不视为异常） */
function hasQQCookie() {
  return !!config.qq && Object.keys(config.qq).length > 0;
}

/** 给网易云 API 参数注入 cookie；未配置时省略该字段（不下发空串，上游按无登录态处理） */
function withNeteaseCookie(extra = {}, req) {
  const effective = resolveNeteaseCookie(req);
  if (!effective) return { ...extra };
  return { ...extra, cookie: effective };
}

// ==================== per-request 用户 Cookie 覆盖（内部 BYOC） ====================
// 信任边界：唯一调用方是内网后端（vibeMusic-backend 经本网关代理），
// X-Vibe-User-Cookie 仅在 localhost/内网可信网络中予以采信；公网绝不能直调本网关。
// 公网 query/body 中的 cookie 参数仍由 sanitizeNeteaseParams 一律剥离，此处不动。
// 优先级：合法的 per-request header > 共享 NETEASE_COOKIE > ''（匿名）。
// 校验：非空、长度上限 8KB、MUSIC_U 形态（须含 MUSIC_U=，否则视为脏值丢弃）。
// 日志只打长度、绝不打值；响应绝不回显（见 routes.js scrubSecrets）。
const USER_COOKIE_HEADER = 'x-vibe-user-cookie';
const USER_COOKIE_MAX_LEN = 8192;

function resolveNeteaseCookie(req) {
  const raw = req && req.headers ? req.headers[USER_COOKIE_HEADER] : undefined;
  const header = Array.isArray(raw) ? raw[0] : raw;
  if (typeof header === 'string' && header.trim()) {
    const value = header.trim();
    if (value.length > USER_COOKIE_MAX_LEN || !/MUSIC_U=/.test(value)) {
      writeLog('cookie', 'WARN', `per-request 用户 Cookie 非法已丢弃 (长度: ${header.length})，回落共享 Cookie`);
    } else {
      writeLog('cookie', 'INFO', `per-request 用户 Cookie 生效 (长度: ${value.length})`);
      return value;
    }
  }
  return NETEASE_COOKIE || '';
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
      qqMusic.setCookie(cookieJson);
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
  return Object.entries(config.qq || {})
    .filter(([, v]) => typeof v === 'string' && v && !String(v).includes(','))
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

module.exports = {
  cookieStatus,
  hasQQCookie,
  withNeteaseCookie,
  resolveNeteaseCookie,
  checkCookies,
  checkQQCookie,
  failQQCookie,
  reloadQQCookie,
  getQQCookieString,
};