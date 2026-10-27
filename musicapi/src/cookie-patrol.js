// ==================== 网易云 Cookie 巡检与快切 ====================
//
// 2026-10-27 网关重构 pass 3：从 cookie.js 拆出，**行为零变更**。
// 状态（槽位 / cookieStatus / gauge 初值）住在 ./cookie-slots.js，本文件只消费它，
// 这样"谁改槽位"只有一条路径（applyActiveSlot），不会出现两份状态。
const { writeLog } = require('./logger');
const {
  cookieStatus, effectivePrimary, effectiveBackup, getNeteaseActive,
  decideActiveSlot, setSlotGauges, applyActiveSlot,
} = require('./cookie-slots');
const {
  probeNeteaseCookie,
  getProbeState,
  withHardTimeout,
  isTimeoutError,
} = require('./cookie-probe');
const { checkQQCookie } = require('./cookie-qq');

/** 单次巡检看门狗：默认 60s（env 可调）。超时后强制结束本轮，不再无限挂起 15 分钟调度。 */
const DEFAULT_PATROL_WATCHDOG_MS = 60000;

function resolvePatrolWatchdogMs(override) {
  const raw = override !== undefined && override !== null && override !== ''
    ? override
    : process.env.MUSICAPI_PATROL_WATCHDOG_MS;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_PATROL_WATCHDOG_MS;
  return Math.min(600000, Math.max(1000, Math.floor(n)));
}

/** 失败原因口径：超时与业务失败分开写日志，巡检日志一眼能看出是不是上游挂起。 */
function probeFailureReason(name) {
  const st = getProbeState();
  const cur = st[name];
  if (!cur || cur.status === 'idle') return `${name}: unknown`;
  if (cur.status === 'failed(timeout)') return `${name}: failed(timeout)`;
  if (cur.status === 'skipped') return `${name}: skipped(no-cookie)`;
  return `${name}: ${cur.detail || cur.status}`;
}

/** 登录通过后能力探针失败时的原因（供 caller 只打一条准确的 ERROR）。 */
function slotFailureReason() {
  const st = getProbeState();
  if (st.login.status === 'failed(timeout)') return '登录探针 failed(timeout)';
  if (st.login.status === 'failed') return `登录探针失败 (${st.login.detail || 'unexpected-response'})`;
  if (st.login.status !== 'ok') return `登录探针 ${st.login.status}`;
  if (st.playback.status === 'failed(timeout)') return '播放能力探针 failed(timeout)';
  if (st.playback.status === 'failed') return `播放能力探针未通过 (${st.playback.detail || 'not-playable'})`;
  return `播放能力探针 ${st.playback.status}`;
}

/**
 * Cookie 巡检（15 分钟调度入口）。
 *
 * 双重超时：
 *   1. 每个探针 8s 硬超时（cookie-probe.withHardTimeout）；
 *   2. 整轮巡检 60s 看门狗：上游挂起时本轮必须结束，否则 setInterval 会永久卡住、
 *      cookieStatus 不再更新、后续自动轮换全部失效。
 *
 * 看门狗触发时本轮的探测结果未收敛，故不改动槽位/gauge（避免把"未知"误判成"掉 Cookie"
 * 触发后端无谓降级），只记 ERROR + 返回 {timedOut:true}；下一轮巡检会重新探测。
 */
async function checkCookies(opts = {}) {
  const startedAt = Date.now();
  const watchdogMs = resolvePatrolWatchdogMs(opts.watchdogMs);
  // cancelled 用共享可变对象传递：看门狗先返回时，迟到的内层流程不再落盘槽位/gauge
  const ctx = { cancelled: false };
  const run = runCookiePatrol(ctx, opts);
  // 看门狗先返回时，内层迟到异常不得变成 unhandledRejection
  run.catch(() => {});
  try {
    const out = await withHardTimeout(() => run, watchdogMs, 'cookie-patrol');
    if (ctx.cancelled) return { cancelled: true, timedOut: true, durationMs: Date.now() - startedAt };
    return out;
  } catch (e) {
    if (!isTimeoutError(e)) throw e;
    ctx.cancelled = true;
    writeLog('cookie', 'ERROR',
      `[watchdog] Cookie 巡检超过 ${watchdogMs}ms 被强制结束（上游疑似挂起），本轮不切换槽位、不改 gauge`);
    return { timedOut: true, durationMs: Date.now() - startedAt };
  }
}

/** 巡检主体（生产路径只经 checkCookies 进入；每个 await 后检查 cancelled）。 */
async function runCookiePatrol(ctx, opts = {}) {
  const startedAt = Date.now();
  const probeOpts = opts.probeTimeoutMs ? { timeoutMs: opts.probeTimeoutMs } : {};
  writeLog('cookie', 'INFO', '🔄 Cookie 存活检查开始...');

  // 检查网易云主备：双探针 + 主优先自动切换/回切
  const primary = effectivePrimary();
  const backup = effectiveBackup();
  const hasBackup = !!backup;
  let primaryOk = false;
  let backupOk = false;
  try {
    primaryOk = await probeNeteaseCookie(primary, probeOpts);
    if (ctx.cancelled) return { cancelled: true, durationMs: Date.now() - startedAt };
    if (primaryOk) {
      writeLog('cookie', 'INFO', '✅ 网易云主 Cookie 正常（登录 + 播放能力）');
    } else {
      writeLog('cookie', 'ERROR', `❌ 网易云主 Cookie 异常（${slotFailureReason()}）`);
    }
  } catch (e) {
    writeLog('cookie', 'ERROR', `❌ 网易云主 Cookie 检查失败: ${e.message}`);
  }
  try {
    if (hasBackup) {
      backupOk = await probeNeteaseCookie(backup, probeOpts);
      if (ctx.cancelled) return { cancelled: true, durationMs: Date.now() - startedAt };
      writeLog('cookie', backupOk ? 'INFO' : 'ERROR',
        backupOk ? '✅ 网易云备 Cookie 正常（登录 + 播放能力）' : `❌ 网易云备 Cookie 异常（${slotFailureReason()}）`);
    }
  } catch (e) {
    writeLog('cookie', 'ERROR', `❌ 网易云备 Cookie 检查失败: ${e.message}`);
  }
  setSlotGauges(primaryOk, backupOk, hasBackup);
  const next = decideActiveSlot({ primaryOk, backupOk, current: getNeteaseActive() });
  applyActiveSlot(next, 'checkCookies');

  // 检查QQ（含自动恢复）
  await checkQQCookie();
  if (ctx.cancelled) return { cancelled: true, durationMs: Date.now() - startedAt };

  const durationMs = Date.now() - startedAt;
  writeLog('cookie', 'INFO',
    `Cookie 存活检查完成（${durationMs}ms）: 主=${probeFailureReason('login')}, 播放=${probeFailureReason('playback')}`);
  return { timedOut: false, durationMs, primaryOk, backupOk, active: getNeteaseActive() };
}

/**
 * 按需快切（供后端共享链路 need-login 时调用）：先复探当前槽位，
 * 健康则不切换（防误切）；否则备有效即切备。返回 {switched, active}，绝不含凭证。
 *
 * @param {{force?: boolean}} [opts] force=true 走"能力感知"强制快切（VIP 掉档用，见下）
 */
async function rotateNeteaseActive(opts = {}) {
  if (opts.force) return rotateNeteaseActiveForced();
  const active = getNeteaseActive();
  const current = active === 'backup' && effectiveBackup() ? 'backup' : 'primary';
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

/** 按槽位取共享 Cookie（主/备）；抽出来让 rotate 的判定与注入用同一口径。 */
function sharedCookieFor(slot) {
  return slot === 'backup' ? effectiveBackup() : effectivePrimary();
}

/**
 * 能力感知强制快切（VIP 掉档容灾）：重探<b>两个槽位</b>的"登录 + 播放能力"
 * （主 Cookie 掉 VIP 时登录/搜索仍正常，只看登录探针永不切换），
 * 选能通过双探针的槽位；双槽都可用（平局）保持当前槽位；双槽都不可用保持现状。
 * gauge/探针计数与 checkCookies 同口径；返回体只含 switched/active/探针结果，绝不含凭证。
 */
async function rotateNeteaseActiveForced() {
  const active = getNeteaseActive();
  const current = active === 'backup' && effectiveBackup() ? 'backup' : 'primary';
  const hasBackup = !!effectiveBackup();
  const primaryOk = await probeNeteaseCookie(effectivePrimary());
  const backupOk = hasBackup ? await probeNeteaseCookie(effectiveBackup()) : false;
  setSlotGauges(primaryOk, backupOk, hasBackup);
  const next = pickCapableSlot({ primaryOk, backupOk, current });
  const out = next === current
    ? Object.assign(applyActiveSlot(current, 'rotate-force-noop'), { forced: true })
    : Object.assign(applyActiveSlot(next, 'rotate-force-capability'), { forced: true });
  return Object.assign(out, { primaryOk, backupOk });
}

/**
 * 强制快切选槽（纯函数）：能通过双探针的槽位优先；双可用平局保持当前槽位；
 * 双不可用保持当前（降级链不变，绝不切到更差的槽）。
 */
function pickCapableSlot({ primaryOk, backupOk, current }) {
  if (primaryOk && backupOk) return current;
  if (primaryOk) return 'primary';
  if (backupOk) return 'backup';
  return current;
}

module.exports = {
  DEFAULT_PATROL_WATCHDOG_MS,
  resolvePatrolWatchdogMs,
  probeFailureReason,
  slotFailureReason,
  checkCookies,
  runCookiePatrol,
  rotateNeteaseActive,
  rotateNeteaseActiveForced,
  pickCapableSlot,
  cookieStatus,
};
