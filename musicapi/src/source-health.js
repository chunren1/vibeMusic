// 每源熔断 + 健康快照（2026-10-27 网关重构 pass 1）
//
// 解决什么问题：网易云挂掉时，一次搜索要在 4 条策略链上串行重试，最坏烧满
// SEARCH_TOTAL_BUDGET(12s) 才返回空；QQ/咪咕/酷狗/B站 同理各烧 10s 超时。
// 上游持续故障期间，每个用户请求都在重复这段等待，而这期间**换源也没用**。
// 熔断打开后直接返回空，让其余源立刻出结果，用户看到的是"少一个源"而不是"搜索转圈 12 秒"。
//
// 口径（刻意保守，宁可少熔断不要误熔断）：
//   - 只有**明确的上游失败**才计数：catch 到的异常、上游返回非成功 code。
//   - **空结果不算失败**：冷门关键词本来就可能零结果，算失败会把好源熔断。
//   - 连续 3 次失败才打开；打开 60s 后放一个探测请求（half_open）；
//     探测成功关闭，失败重新计时 —— 避免上游抖动导致反复开关。
//
// 不持久化：进程重启后归零。这是有意的 —— 熔断状态是"当下上游好没好"，
// 跨重启沿用旧判断比重新探一次更危险。

const FAIL_THRESHOLD = 3;
const OPEN_MS = 60 * 1000;
const SOURCES = ['netease', 'qq', 'migu', 'kugou', 'bili'];

const state = new Map();

function blank() {
  return {
    consecutiveFailures: 0,
    openedAt: 0,
    lastError: '',
    lastOkAt: 0,
    lastFailAt: 0,
    lastSongs: 0,
    totalOk: 0,
    totalFail: 0,
  };
}

function entry(name) {
  if (!state.has(name)) state.set(name, blank());
  return state.get(name);
}

/** 当前时刻是否处于熔断打开期（未到探测时间）。 */
function isOpen(name, now = Date.now()) {
  const s = state.get(name);
  if (!s || !s.openedAt) return false;
  return now - s.openedAt < OPEN_MS;
}

/**
 * 记录一次搜索结果。`ok=false` 必须带 reason —— 快照里要能回答"为什么熔断"，
 * 否则运维只能看到 open 状态却不知道上游报的什么错。
 */
function recordOutcome(name, ok, detail = {}) {
  const s = entry(name);
  const now = Date.now();
  if (ok) {
    s.consecutiveFailures = 0;
    s.openedAt = 0;
    s.lastOkAt = now;
    s.totalOk += 1;
    if (typeof detail.songs === 'number') s.lastSongs = detail.songs;
    return;
  }
  s.consecutiveFailures += 1;
  s.lastFailAt = now;
  s.totalFail += 1;
  s.lastError = String(detail.reason || 'unknown').slice(0, 200);
  if (s.consecutiveFailures >= FAIL_THRESHOLD && !s.openedAt) {
    s.openedAt = now;
  }
}

/**
 * 成功记账并把结果原样返回 —— 让调用点写成 `return okOutcome('qq', list.map(...))`，
 * 不必为了记个数把 return 表达式拆成临时变量（拆了容易漏改某一分支）。
 */
function okOutcome(name, songs) {
  const list = Array.isArray(songs) ? songs : [];
  // 空结果不计数：冷门关键词本来就可能零结果，算成功会掩盖"上游返回空壳"，
  // 算失败又会把好源熔断 —— 两边都不算，保持上次状态。
  if (list.length > 0) recordOutcome(name, true, { songs: list.length });
  return songs;
}

/** 失败记账并返回空数组（搜索语义：源挂了就是这一源没结果，不抛错）。 */
function failOutcome(name, reason) {
  recordOutcome(name, false, { reason });
  return [];
}

/**
 * 熔断闸门：打开期直接返回空数组（搜索语义），否则执行。
 *
 * **只做短路，不记录结果** —— 记账必须发生在源内部：QQ/咪咕/酷狗/B站/网易云
 * 都是「catch 住异常 → 返回 []」，在闸门这层看到的永远是成功返回空数组，
 * 记不出"上游挂了"。所以 recordOutcome 由各源在自己的失败分支调用（见 search.js
 * 与 sources/netease.js 的 `degradation ERROR` 处）。
 *
 * 返回空数组而不是抛错：调用方全是搜索，抛错会把整个聚合搜索打成 500。
 */
async function guard(name, fn) {
  if (isOpen(name)) return [];
  return fn();
}

/** 供 /source-health 与测试使用。`now` 可注入，便于测半开时间窗。 */
function snapshot(now = Date.now()) {
  const rows = SOURCES.map((name) => {
    const s = state.get(name) || blank();
    const open = !!(s.openedAt && now - s.openedAt < OPEN_MS);
    return {
      name,
      state: open ? 'open' : (s.consecutiveFailures > 0 ? 'degraded' : 'closed'),
      consecutiveFailures: s.consecutiveFailures,
      openRemainingMs: open ? Math.max(0, OPEN_MS - (now - s.openedAt)) : 0,
      lastError: s.lastError,
      lastOkAt: s.lastOkAt || null,
      lastFailAt: s.lastFailAt || null,
      lastSongs: s.lastSongs,
      totalOk: s.totalOk,
      totalFail: s.totalFail,
    };
  });
  const openCount = rows.filter((r) => r.state === 'open').length;
  return {
    summary: {
      total: rows.length,
      open: openCount,
      degraded: rows.filter((r) => r.state === 'degraded').length,
      healthy: rows.filter((r) => r.state === 'closed').length,
    },
    sources: rows,
  };
}

/** 仅测试用：清空全部状态，避免用例之间互相污染。 */
function reset() {
  state.clear();
}

module.exports = {
  FAIL_THRESHOLD,
  OPEN_MS,
  SOURCES,
  isOpen,
  recordOutcome,
  guard,
  okOutcome,
  failOutcome,
  snapshot,
  reset,
};
