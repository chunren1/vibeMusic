// ==================== Cookie 统一管理 ====================
const path = require('path');
const qqMusic = require('qq-music-api');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const config = require('./config-loader');
const { writeLog } = require('./logger');
const { cookieStatusGauge, vipHealthGauge, cookieProbeTotal } = require('./metrics');

// QQ音乐 Cookie（进程级全局设置；Cookie 为可选项，无 Cookie 即无登录模式，属正常情况）
qqMusic.setCookie(config.qq || {});
if (hasQQCookie()) {
  writeLog('cookie', 'INFO', `QQ音乐 Cookie 已加载 (${Object.keys(config.qq).length} 个字段)`);
} else {
  writeLog('cookie', 'INFO', 'QQ音乐 Cookie 未配置，以无 Cookie 模式运行（正常情况，无需恢复）');
}

// 网易云 Cookie（注入到每次 API 调用的请求参数中）
// 主备容灾：主失效且备有效时自动切备，主恢复后自动切回；未配备份保持单 Cookie 行为
const NETEASE_COOKIE = config.netease;
const NETEASE_COOKIE_BACKUP = config.neteaseBackup || '';
writeLog('cookie', 'INFO', `网易云 Cookie 已加载 (主长度: ${NETEASE_COOKIE ? NETEASE_COOKIE.length : 0}, 备长度: ${NETEASE_COOKIE_BACKUP ? NETEASE_COOKIE_BACKUP.length : 0})`);

// 当前生效的共享 Cookie 槽位：primary 优先，备仅在主失效时接管
let neteaseActive = 'primary';

// Cookie 存活状态（随 API 启动自动运行）
let cookieStatus = { netease: true, neteasePrimary: true, neteaseBackup: !!NETEASE_COOKIE_BACKUP, neteaseActive: 'primary', qq: true };
// Prometheus gauge 初始值：假设 Cookie 可用，checkCookies 会更新为实际值
cookieStatusGauge.set({ platform: 'netease' }, 1);
cookieStatusGauge.set({ platform: 'netease-primary' }, 1);
cookieStatusGauge.set({ platform: 'netease-backup' }, NETEASE_COOKIE_BACKUP ? 1 : 0);
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

// ==================== 共享 Cookie 主备槽位 ====================
// 优先级（网关侧执行）：per-request header > 当前生效槽位（主/备）> ''（匿名）。
// decideActiveSlot 为纯函数：主有效恒选主；主失效且备有效切备；双失效保持现状（降级链不变）。
function decideActiveSlot({ primaryOk, backupOk, current }) {
  if (primaryOk) return 'primary';
  if (backupOk) return 'backup';
  return current === 'backup' ? 'backup' : 'primary';
}

function sharedCookieFor(slot) {
  if (slot === 'backup') return effectiveBackup();
  return effectivePrimary();
}

function resolveSharedCookie() {
  const active = neteaseActive === 'backup' && effectiveBackup() ? 'backup' : 'primary';
  return { cookie: sharedCookieFor(active), slot: active };
}

function getNeteaseActive() {
  return neteaseActive;
}

/** 测试缝：覆盖共享槽位（仅单测使用，生产路径只经 checkCookies/rotate 变更）。 */
function _setSharedForTest({ primary, backup, active } = {}) {
  _testShared = {
    primary: typeof primary === 'string' ? primary : undefined,
    backup: typeof backup === 'string' ? backup : undefined,
    active,
  };
  if (active === 'primary' || active === 'backup') neteaseActive = active;
}

let _testShared = null;

function effectivePrimary() {
  return _testShared && typeof _testShared.primary === 'string' ? _testShared.primary : (NETEASE_COOKIE || '');
}

function effectiveBackup() {
  return _testShared && typeof _testShared.backup === 'string' ? _testShared.backup : (NETEASE_COOKIE_BACKUP || '');
}

function _resetSharedForTest() {
  _testShared = null;
  neteaseActive = 'primary';
}
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
  return resolveSharedCookie().cookie;
}

/** 探活单个共享 Cookie：cloudsearch 200+有结果即有效；空串直接判无效，异常判无效，绝不抛错。 */
async function probeNeteaseCookie(cookieStr) {
  if (!cookieStr) return false;
  try {
    const res = await NeteaseCloudMusicApi.cloudsearch({ keywords: '周杰伦', limit: 1, type: 1, cookie: cookieStr });
    return !!(res && res.body && res.body.code === 200 && res.body.result);
  } catch (e) {
    return false;
  }
}

function setSlotGauges(primaryOk, backupOk, hasBackup) {
  cookieStatus.neteasePrimary = primaryOk;
  cookieStatus.neteaseBackup = hasBackup ? backupOk : true;
  cookieStatusGauge.set({ platform: 'netease-primary' }, primaryOk ? 1 : 0);
  cookieStatusGauge.set({ platform: 'netease-backup' }, hasBackup ? (backupOk ? 1 : 0) : 0);
  const vip = vipHealthState(primaryOk, backupOk, hasBackup);
  vipHealthGauge.set({ slot: 'primary' }, vip.primary);
  vipHealthGauge.set({ slot: 'backup' }, vip.backup);
  cookieProbeTotal.inc({ slot: 'primary', result: primaryOk ? 'ok' : 'fail' });
  if (hasBackup) cookieProbeTotal.inc({ slot: 'backup', result: backupOk ? 'ok' : 'fail' });
}

/**
 * VIP 健康纯函数：主槽如实反映探活；备槽未配置时报 1（无失效对象，
 * 与 cookieStatus.neteaseBackup 口径一致），配了才反映探活。
 * 返回 gauge 值（1/0），便于单测与告警口径对齐。
 */
function vipHealthState(primaryOk, backupOk, hasBackup) {
  return {
    primary: primaryOk ? 1 : 0,
    backup: hasBackup ? (backupOk ? 1 : 0) : 1,
  };
}

/** 当前 VIP 健康快照（与落盘 gauge 同值，只含 1/0，绝不含凭证）。 */
function getVipHealth() {
  return {
    primary: cookieStatus.neteasePrimary ? 1 : 0,
    backup: cookieStatus.neteaseBackup ? 1 : 0,
    active: neteaseActive,
  };
}

function applyActiveSlot(next, reason) {
  const prev = neteaseActive;
  neteaseActive = next;
  cookieStatus.neteaseActive = next;
  const effectiveOk = next === 'primary' ? cookieStatus.neteasePrimary : cookieStatus.neteaseBackup;
  cookieStatus.netease = effectiveOk;
  cookieStatusGauge.set({ platform: 'netease' }, effectiveOk ? 1 : 0);
  if (prev !== next) {
    writeLog('cookie', next === 'primary' ? 'INFO' : 'WARN', `网易云共享 Cookie 切换: ${prev} -> ${next} (${reason})`);
  }
  return { switched: prev !== next, active: next };
}

async function checkCookies() {
  writeLog('cookie', 'INFO', '🔄 Cookie 存活检查开始...');

  // 检查网易云主备：双探针 + 主优先自动切换/回切
  const primary = effectivePrimary();
  const backup = effectiveBackup();
  const hasBackup = !!backup;
  let primaryOk = false;
  let backupOk = false;
  try {
    primaryOk = await probeNeteaseCookie(primary);
    if (primaryOk) {
      writeLog('cookie', 'INFO', '✅ 网易云主 Cookie 正常');
    } else {
      writeLog('cookie', 'ERROR', '❌ 网易云主 Cookie 异常');
    }
  } catch (e) {
    writeLog('cookie', 'ERROR', `❌ 网易云主 Cookie 检查失败: ${e.message}`);
  }
  try {
    if (hasBackup) {
      backupOk = await probeNeteaseCookie(backup);
      writeLog('cookie', backupOk ? 'INFO' : 'ERROR', backupOk ? '✅ 网易云备 Cookie 正常' : '❌ 网易云备 Cookie 异常');
    }
  } catch (e) {
    writeLog('cookie', 'ERROR', `❌ 网易云备 Cookie 检查失败: ${e.message}`);
  }
  setSlotGauges(primaryOk, backupOk, hasBackup);
  const next = decideActiveSlot({ primaryOk, backupOk, current: neteaseActive });
  applyActiveSlot(next, 'checkCookies');

  // 检查QQ（含自动恢复）
  await checkQQCookie();
}

/**
 * 按需快切（供后端共享链路 need-login 时调用）：先复探当前槽位，
 * 健康则不切换（防误切）；否则备有效即切备。返回 {switched, active}，绝不含凭证。
 */
async function rotateNeteaseActive() {
  const current = neteaseActive === 'backup' && effectiveBackup() ? 'backup' : 'primary';
  if (await probeNeteaseCookie(sharedCookieFor(current))) {
    setSlotGauges(current === 'primary', current === 'backup' ? true : await probeNeteaseCookie(effectiveBackup()), !!effectiveBackup());
    applyActiveSlot(current, 'rotate-noop');
    return { switched: false, active: current };
  }
  const other = current === 'primary' ? 'backup' : 'primary';
  if (await probeNeteaseCookie(sharedCookieFor(other))) {
    setSlotGauges(other === 'primary', other === 'backup', !!effectiveBackup());
    return applyActiveSlot(other, 'rotate-on-need-login');
  }
  setSlotGauges(await probeNeteaseCookie(effectivePrimary()), false, !!effectiveBackup());
  applyActiveSlot(current, 'rotate-both-unhealthy');
  return { switched: false, active: current, reason: 'both-unhealthy' };
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
  resolveSharedCookie,
  getNeteaseActive,
  decideActiveSlot,
  vipHealthState,
  getVipHealth,
  setSlotGauges,
  probeNeteaseCookie,
  rotateNeteaseActive,
  checkCookies,
  checkQQCookie,
  failQQCookie,
  reloadQQCookie,
  getQQCookieString,
  _setSharedForTest,
  _resetSharedForTest,
};