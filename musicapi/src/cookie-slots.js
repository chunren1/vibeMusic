// ==================== 网易云共享 Cookie：槽位与注入 ====================
//
// 2026-10-27 网关重构 pass 3：从 cookie.js（337 纯 LOC）按职责拆出，**行为零变更**。
// 文件分工：
//   本文件          —— 槽位状态（主/备）、per-request 用户 Cookie 信任边界、gauge 口径
//   ./cookie-patrol.js —— 巡检（checkCookies）与快切（rotate*），只消费本文件的状态
//   ./cookie-qq.js     —— QQ Cookie（检查/重载/请求头字符串）
//   ./cookie.js        —— 组合 + re-export，保持 `require('./cookie')` 旧契约
//
// 不变量：cookieStatus / neteaseActive 是**进程内单例**，只能在本文件声明；
// patrol 与 routes 一律通过本文件读写，避免出现两份状态。
const config = require('./config-loader');
const { writeLog } = require('./logger');
const { cookieStatusGauge, vipHealthGauge, cookieProbeTotal } = require('./metrics');

// 网易云 Cookie（注入到每次 API 调用的请求参数中）
// 主备容灾：主失效且备有效时自动切备，主恢复后自动切回；未配备份保持单 Cookie 行为
const NETEASE_COOKIE = config.netease;
const NETEASE_COOKIE_BACKUP = config.neteaseBackup || '';
writeLog('cookie', 'INFO', `网易云 Cookie 已加载 (主长度: ${NETEASE_COOKIE ? NETEASE_COOKIE.length : 0}, 备长度: ${NETEASE_COOKIE_BACKUP ? NETEASE_COOKIE_BACKUP.length : 0})`);

// 当前生效的共享 Cookie 槽位：primary 优先，备仅在主失效时接管
let neteaseActive = 'primary';

// Cookie 存活状态（随 API 启动自动运行）
const cookieStatus = { netease: true, neteasePrimary: true, neteaseBackup: !!NETEASE_COOKIE_BACKUP, neteaseActive: 'primary', qq: true };
// Prometheus gauge 初始值：假设 Cookie 可用，checkCookies 会更新为实际值
cookieStatusGauge.set({ platform: 'netease' }, 1);
cookieStatusGauge.set({ platform: 'netease-primary' }, 1);
cookieStatusGauge.set({ platform: 'netease-backup' }, NETEASE_COOKIE_BACKUP ? 1 : 0);
cookieStatusGauge.set({ platform: 'qq' }, 1);

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

module.exports = {
  cookieStatus,
  withNeteaseCookie,
  resolveNeteaseCookie,
  resolveSharedCookie,
  getNeteaseActive,
  decideActiveSlot,
  vipHealthState,
  getVipHealth,
  setSlotGauges,
  applyActiveSlot,
  effectivePrimary,
  effectiveBackup,
  _setSharedForTest,
  _resetSharedForTest,
};
