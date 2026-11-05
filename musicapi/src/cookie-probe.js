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

/**
 * 结论不可用（inconclusive）时的重试间隔：默认 1.5s（env 可调，0 表示立即重试/测试用）。
 * 2026-11-06：网易云风控 -460 是"此刻这个出口 IP 被拦"，隔一两秒往往就放行，
 * 故先重试一次再下结论；重试仍不可用则返回 'unknown'，绝不判成 Cookie 失效。
 */
const DEFAULT_RETRY_DELAY_MS = 1500;
const RETRY_DELAY_MAX_MS = 10000;

/** 上游风控码（实测：song_url_v1 返回 {code:-460, message:'检测到您的网络环境存在风险，请稍后再试'}）。 */
const RISK_CONTROL_CODE = -460;

/** 上游风控/暂时性故障的文案特征（中文上游只给 message，没有稳定的机器码）。 */
const RISK_CONTROL_MESSAGE_MARKERS = ['网络环境存在风险', '服务繁忙'];

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

/** 解析 inconclusive 重试间隔：显式入参 > 环境变量 > 默认 1.5s；0 合法（=立即重试）。 */
function resolveRetryDelayMs(override) {
  const raw = override !== undefined && override !== null && override !== ''
    ? override
    : process.env.MUSICAPI_PROBE_RETRY_DELAY_MS;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 0) return DEFAULT_RETRY_DELAY_MS;
  return Math.min(RETRY_DELAY_MAX_MS, Math.floor(n));
}

/** 默认真实等待（测试可注入 opts.delay 以免真的 sleep）。 */
function defaultDelay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
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

// ==================== 上游风控 / 暂时性故障（inconclusive）====================
// 2026-11-06 刷屏事故：网易云风控 -460（"检测到您的网络环境存在风险，请稍后再试"）
// 被取链探针当成"Cookie 掉 VIP"，主备双双判死 → 推 🔴；下一轮恢复 → 推 🟢；来回刷屏。
// 口径：**结论不可用 ≠ Cookie 失效**。真正的失效只有一种 —— 拿到 url 却是试听/空链
// （isPlayableUrlPayload=false）。风控码、风控文案、5xx、超时一律归 'unknown'。

function bodyMessage(body) {
  if (!body || typeof body !== 'object') return '';
  return String(body.message || body.msg || '');
}

function isRiskControlMessage(text) {
  const msg = String(text || '');
  return RISK_CONTROL_MESSAGE_MARKERS.some((marker) => msg.includes(marker));
}

/**
 * 取链响应体是否为「上游风控/暂时性故障」（纯函数）：code=-460 或含风控文案。
 * 注意与 isPlayableUrlPayload 的分工：本函数只认"上游没给结论"，不判可播性。
 */
function isInconclusiveUrlPayload(body) {
  if (!body || typeof body !== 'object') return false;
  if (Number(body.code) === RISK_CONTROL_CODE) return true;
  return isRiskControlMessage(bodyMessage(body));
}

/** 上游响应（含 HTTP 状态）是否 inconclusive：风控体或 HTTP 5xx（网关/源站暂时故障）。 */
function isInconclusiveUpstreamResponse(res) {
  if (!res || typeof res !== 'object') return false;
  if (isInconclusiveUrlPayload(res.body)) return true;
  const status = Number(res.status || 0);
  return status >= 500 && status <= 599;
}

/** 上游异常是否 inconclusive：超时 / 5xx / 风控文案（异常体里也可能带 code=-460）。 */
function isInconclusiveUpstreamError(err) {
  if (!err) return false;
  if (isTimeoutError(err)) return true;
  if (isInconclusiveUrlPayload(err.body)) return true;
  const status = Number(err.status || err.statusCode || (err.response && err.response.status) || 0);
  if (status >= 500 && status <= 599) return true;
  return isRiskControlMessage(err.message);
}

/** inconclusive 的简短可观测描述（只含码/文案，绝不含 Cookie 字节）。 */
function inconclusiveDetail(res, err) {
  if (err) {
    if (isTimeoutError(err)) return 'timeout';
    const status = Number(err.status || err.statusCode || 0);
    if (status) return `http:${status}`;
    const msg = String(err.message || '');
    if (isRiskControlMessage(msg)) return 'risk-control';
    return 'upstream-error';
  }
  const body = res && res.body;
  if (body && Number(body.code)) return `code:${Number(body.code)}`;
  const status = Number(res && res.status || 0);
  return status ? `http:${status}` : 'inconclusive';
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
 * 单次取链判定：true=可播 / false=明确不可播 / 'unknown'=上游没给结论（风控·5xx·超时）。
 * 纯内部原语，调用方（probeNeteasePlayback）负责重试与状态落盘。
 */
async function probePlaybackOnce(cookieStr, id, level, timeoutMs) {
  try {
    const res = await withHardTimeout(
      () => netease.invoke('song_url_v1', { id, level, cookie: cookieStr }),
      timeoutMs, 'song_url_v1'
    );
    if (isInconclusiveUpstreamResponse(res)) {
      return { verdict: 'unknown', detail: inconclusiveDetail(res, null) };
    }
    const ok = isPlayableUrlPayload(res && res.body);
    return { verdict: ok, detail: ok ? 'ok' : 'not-playable' };
  } catch (e) {
    if (isInconclusiveUpstreamError(e)) {
      return { verdict: 'unknown', detail: inconclusiveDetail(null, e) };
    }
    return { verdict: false, detail: `error:${(e && e.message) || 'unknown'}` };
  }
}

/**
 * 播放能力探针：对 VIP 探针曲目取链，能拿到完整可播链接才算通过。
 *
 * 三态（2026-11-06 刷屏修复）：
 *   true      —— 拿到完整链接（url 非空 + 无 freeTrialInfo）；
 *   false     —— **明确**没有播放权限（30s 试听 / 空链 / 非风控类业务错）；
 *   'unknown' —— 上游风控 -460、5xx、超时：本轮结论不可用，重试一次仍不可用即返回 'unknown'。
 * 绝不把「上游没回答」当成「Cookie 失效」——那正是每分钟 🔴/🟢 来回刷屏的根因。
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
  const wait = typeof opts.delay === 'function' ? opts.delay : defaultDelay;
  const retryDelayMs = resolveRetryDelayMs(opts.retryDelayMs);

  let attempt = await probePlaybackOnce(cookieStr, id, level, timeoutMs);
  if (attempt.verdict === 'unknown') {
    writeLog('cookie', 'WARN',
      `播放能力探针结论不可用（${attempt.detail}），${retryDelayMs}ms 后重试一次`);
    if (retryDelayMs > 0) await wait(retryDelayMs);
    attempt = await probePlaybackOnce(cookieStr, id, level, timeoutMs);
  }
  if (attempt.verdict === 'unknown') {
    recordProbe('playback', 'unknown', attempt.detail, Date.now() - startedAt);
    writeLog('cookie', 'WARN',
      `播放能力探针重试后仍结论不可用（${attempt.detail}）：上游风控/暂不可达，本轮不据此判定 Cookie 失效`);
    return 'unknown';
  }
  recordProbe('playback', attempt.verdict ? 'ok' : 'failed', attempt.detail, Date.now() - startedAt);
  return attempt.verdict;
}

/**
 * 组合探针（槽位健康唯一口径）：健康 = 登录有效性 且 播放能力。
 * 能力探针被 kill switch 关闭时，行为与旧 probeNeteaseCookie 完全一致（只探登录）。
 *
 * 返回值三态：true | false | 'unknown'。
 *   登录探针失败 = false（登录有效性是硬信号，登录不上就是真失效）；
 *   登录 ok + 播放能力 'unknown' = 'unknown'（上游风控/暂不可达，本轮没有结论）。
 */
async function probeNeteaseCookie(cookieStr, opts = {}) {
  const loginOk = await probeNeteaseLogin(cookieStr, opts);
  if (!loginOk) return false;
  if (!isCapabilityProbeEnabled()) return true;
  const playable = await probeNeteasePlayback(cookieStr, opts);
  if (playable === 'unknown') {
    // 只作观测提示，不写 ERROR：这不是 Cookie 故障，是上游没给结论
    return 'unknown';
  }
  if (!playable) {
    const detail = probeState.playback.detail || 'not-playable';
    writeLog('cookie', 'WARN', detail === 'not-playable'
      ? `播放能力探针未通过（探针曲目 ${probeTrackId()} 无完整播放权限），该槽位判为不健康`
      : `播放能力探针失败（${detail}），该槽位判为不健康`);
  }
  return playable;
}

module.exports = {
  DEFAULT_PROBE_TRACK_ID,
  DEFAULT_PROBE_TIMEOUT_MS,
  DEFAULT_RETRY_DELAY_MS,
  RISK_CONTROL_CODE,
  resolveProbeTimeoutMs,
  resolveRetryDelayMs,
  isCapabilityProbeEnabled,
  probeTrackId,
  isPlayableUrlPayload,
  isInconclusiveUrlPayload,
  isInconclusiveUpstreamResponse,
  isInconclusiveUpstreamError,
  getProbeState,
  _resetProbeStateForTest,
  withHardTimeout,
  isTimeoutError,
  probeNeteaseLogin,
  probeNeteasePlayback,
  probeNeteaseCookie,
};
