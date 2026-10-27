// ==================== 网易云 Cookie 探针（登录有效性 + 播放能力）====================
// 背景：cloudsearch 探活只证明"登录有效"，证明不了"还能播放 VIP 曲目"。
// 实测 2026-10：主 Cookie 掉 VIP 后 cloudsearch 依旧 200 有结果，
// 但 VIP-only 曲目取链只回 30s 试听（fee=1 && payed=0 && freeTrialInfo），
// 于是 decideActiveSlot 永远停在主槽、备 Cookie 永不接管，App 侧取链拿到试听被拒。
//
// 本模块补一条"播放能力"探针：对可配置的 VIP 曲目调 song_url_v1，
// 要求 url 非空且非试听。健康 = 登录有效性 且 播放能力（能力探针可被 kill switch 关闭）。
// 日志只打长度/槽位/结果，绝不打 Cookie 字节。
//
// 超时（2026-10-22 加固）：NeteaseCloudMusicApi 的 util/request.js 全文件无 timeout，
// 上游挂起时 15 分钟巡检会永久卡住（cookieStatus 不再更新、自动轮换全部失效）。
// 故每个探针调用都过一层硬超时（默认 8s，MUSICAPI_PROBE_TIMEOUT_MS 可调）：
// 该库不支持 AbortSignal（实测 request.js 无 signal 透传），只能用 Promise.race；
// 超时后把状态标记为 failed(timeout)，并对上游 promise 挂 noop catch，
// 防止迟到 reject 变成 unhandledRejection 打断进程。
// 走隔离墙，不直接碰第三方库（见 src/sources/netease.js）
const netease = require('./sources/netease');
const config = require('./config-loader');
const { writeLog } = require('./logger');

/** 默认探针曲目：273114《失眠》（VIP-only；实测有 VIP 的 Cookie 返回 payed=1 完整链接）。 */
const DEFAULT_PROBE_TRACK_ID = '273114';

/** 单次探针硬超时：默认 8s（env 可调，钳制在 100ms ~ 30s）。 */
const DEFAULT_PROBE_TIMEOUT_MS = 8000;
const PROBE_TIMEOUT_MIN_MS = 100;
const PROBE_TIMEOUT_MAX_MS = 30000;

/** 能力探针开关（kill switch）：关闭时健康判定等价于旧 probeNeteaseCookie（仅登录有效性）。 */
function isCapabilityProbeEnabled() {
  return config.neteaseProbeEnabled !== false;
}

/** 当前探针曲目 id：配置优先，缺失回落内置默认值。 */
function probeTrackId() {
  return config.neteaseProbeTrackId || DEFAULT_PROBE_TRACK_ID;
}

/** 解析探针超时：显式入参 > 环境变量 > 默认 8s；非法/越界一律回落，绝不返回 NaN。 */
function resolveProbeTimeoutMs(override) {
  const raw = override !== undefined && override !== null && override !== ''
    ? override
    : process.env.MUSICAPI_PROBE_TIMEOUT_MS;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_PROBE_TIMEOUT_MS;
  return Math.min(PROBE_TIMEOUT_MAX_MS, Math.max(PROBE_TIMEOUT_MIN_MS, Math.floor(n)));
}

// ==================== 探针状态（可观测的 failed(timeout) 标记）====================

/** 探针最近一次结果：状态只记枚举与耗时，绝不含 Cookie/URL 字节。 */
const probeState = {
  login: { status: 'idle', detail: '', at: 0, durationMs: 0 },
  playback: { status: 'idle', detail: '', at: 0, durationMs: 0 },
};

function recordProbe(name, status, detail, durationMs) {
  if (!probeState[name]) return;
  probeState[name] = {
    status,
    detail: String(detail || ''),
    at: Date.now(),
    durationMs: Math.max(0, Math.floor(durationMs || 0)),
  };
}

/** 探针状态快照（深拷贝；供 /cookie-status 之外的诊断与单测读取）。 */
function getProbeState() {
  return {
    login: { ...probeState.login },
    playback: { ...probeState.playback },
  };
}

/** 测试缝：重置探针状态。 */
function _resetProbeStateForTest() {
  recordProbe('login', 'idle', '', 0);
  recordProbe('playback', 'idle', '', 0);
}

// ==================== 硬超时（该库无 AbortSignal，故 Promise.race + 吞迟到拒绝）====================

function makeTimeoutError(label, ms) {
  const err = new Error(`probe timeout: ${label} (${ms}ms)`);
  err.code = 'PROBE_TIMEOUT';
  err.isTimeout = true;
  return err;
}

function isTimeoutError(err) {
  return !!(err && (err.isTimeout === true || err.code === 'PROBE_TIMEOUT'));
}

/**
 * 给一个（可能永不返回的）上游调用套硬超时。
 *
 * - 上游本身不接受 AbortSignal：超时只能"停止等待"，不能取消在飞 socket；
 * - 上游 promise 额外挂 noop catch：超时后迟到的 reject 不会变成 unhandledRejection；
 * - 定时器在正常返回时必定 clearTimeout，避免拖住事件循环。
 */
function withHardTimeout(run, ms, label) {
  const upstream = Promise.resolve().then(run);
  // 迟到 reject 的兜底消费（race 分支之外仍有一条已处理链）
  upstream.catch(() => {});
  let timer;
  const timeoutPromise = new Promise((_, reject) => {
    timer = setTimeout(() => reject(makeTimeoutError(label, ms)), ms);
  });
  return Promise.race([upstream, timeoutPromise]).finally(() => clearTimeout(timer));
}

// ==================== 探针实现 ====================

/**
 * 取链响应可播判定（纯函数）：{@code data[0].url} 非空且无试听信息才是完整可播。
 * 试听兜底（fee=1 && payed=0 && freeTrialInfo）、空链、脏形一律判不可播。
 */
function isPlayableUrlPayload(body) {
  const item = body && Array.isArray(body.data) ? body.data[0] : null;
  if (!item || typeof item.url !== 'string' || !item.url) return false;
  return item.freeTrialInfo === null || item.freeTrialInfo === undefined;
}

/** 失败收口：区分超时与业务失败，超时打 WARN（caller 据此不再打成"Cookie 过期"）。 */
function failProbe(name, err, timeoutMs, label, startedAt) {
  const timeout = isTimeoutError(err);
  const detail = timeout ? 'timeout' : `error:${(err && err.message) || 'unknown'}`;
  recordProbe(name, timeout ? 'failed(timeout)' : 'failed', detail, Date.now() - startedAt);
  if (timeout) {
    writeLog('cookie', 'WARN',
      `${label} 探针超时（${timeoutMs}ms 未返回），标记 failed(timeout)，该槽位按不健康处理`);
  }
  return false;
}

/** 登录/搜索有效性探针：cloudsearch 200+有结果即有效；空串/超时/异常判无效，绝不抛错。 */
async function probeNeteaseLogin(cookieStr, opts = {}) {
  if (!cookieStr) {
    recordProbe('login', 'skipped', 'no-cookie', 0);
    return false;
  }
  const timeoutMs = resolveProbeTimeoutMs(opts.timeoutMs);
  const startedAt = Date.now();
  try {
    const res = await withHardTimeout(
      () => netease.invoke('cloudsearch', { keywords: '周杰伦', limit: 1, type: 1, cookie: cookieStr }),
      timeoutMs, 'cloudsearch'
    );
    const ok = !!(res && res.body && res.body.code === 200 && res.body.result);
    recordProbe('login', ok ? 'ok' : 'failed', ok ? 'ok' : 'unexpected-response', Date.now() - startedAt);
    return ok;
  } catch (e) {
    return failProbe('login', e, timeoutMs, 'cloudsearch', startedAt);
  }
}

/**
 * 播放能力探针：对 VIP 探针曲目取链，能拿到完整可播链接才算通过。
 * 超时/异常/上游报错一律判不可播（与登录探针口径一致，绝不抛错，避免打断巡检）。
 */
async function probeNeteasePlayback(cookieStr, opts = {}) {
  if (!cookieStr) {
    recordProbe('playback', 'skipped', 'no-cookie', 0);
    return false;
  }
  const id = opts.trackId || probeTrackId();
  const level = opts.level || 'exhigh';
  const timeoutMs = resolveProbeTimeoutMs(opts.timeoutMs);
  const startedAt = Date.now();
  try {
    const res = await withHardTimeout(
      () => netease.invoke('song_url_v1', { id, level, cookie: cookieStr }),
      timeoutMs, 'song_url_v1'
    );
    const ok = isPlayableUrlPayload(res && res.body);
    recordProbe('playback', ok ? 'ok' : 'failed', ok ? 'ok' : 'not-playable', Date.now() - startedAt);
    return ok;
  } catch (e) {
    return failProbe('playback', e, timeoutMs, 'song_url_v1', startedAt);
  }
}

/**
 * 组合探针（槽位健康唯一口径）：健康 = 登录有效性 且 播放能力。
 * 能力探针被 kill switch 关闭时，行为与旧 probeNeteaseCookie 完全一致（只探登录）。
 */
async function probeNeteaseCookie(cookieStr, opts = {}) {
  const loginOk = await probeNeteaseLogin(cookieStr, opts);
  if (!loginOk) return false;
  if (!isCapabilityProbeEnabled()) return true;
  const playable = await probeNeteasePlayback(cookieStr, opts);
  if (!playable) {
    // 超时已在 failProbe 打过 WARN，这里只补业务口径，避免同一次失败两条 WARN 误导排查
    const st = probeState.playback;
    if (st.status !== 'failed(timeout)') {
      writeLog('cookie', 'WARN', `播放能力探针未通过（探针曲目 ${probeTrackId()} 无完整播放权限），该槽位判为不健康`);
    }
  }
  return playable;
}

module.exports = {
  DEFAULT_PROBE_TRACK_ID,
  DEFAULT_PROBE_TIMEOUT_MS,
  resolveProbeTimeoutMs,
  isCapabilityProbeEnabled,
  probeTrackId,
  isPlayableUrlPayload,
  getProbeState,
  _resetProbeStateForTest,
  withHardTimeout,
  isTimeoutError,
  probeNeteaseLogin,
  probeNeteasePlayback,
  probeNeteaseCookie,
};
