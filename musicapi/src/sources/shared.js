// 源适配器共用基元（2026-10-27 网关重构 pass 1）
//
// 为什么单独一个文件：sources/* 与 search.js 都要用 withTimeout 和两个预算常量。
// 如果留在 search.js，sources/netease.js 就变成 search.js → sources/netease → search.js
// 的循环 require（Node 会拿到半成品 exports，报 undefined 函数）。
// 因此**基元下沉到这里**，search.js 只负责 re-export 保持旧契约（routes.js 仍从 './search' 取）。
//
// 这里只放无状态的纯工具；缓存（searchCache/urlCache）留在 search.js —— 它们有生命周期语义，
// 且只有 routes 与 search 自己用，搬走反而制造第二条 import 路径。

const UPSTREAM_TIMEOUT = 10000; // 10s（单次上游调用上限）
// 搜索策略链总预算：网易云 4 策略串行（各 10s）最坏约 40s，
// 此处设总预算 12s 早退——降级顺序不变，只是超时后不再继续试剩余策略。
const SEARCH_TOTAL_BUDGET = 12000; // 12s

function withTimeout(promise, ms, label) {
  let timer;
  const timeoutPromise = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`Upstream timeout: ${label} (${ms}ms)`)), ms);
  });
  return Promise.race([promise, timeoutPromise]).finally(() => clearTimeout(timer));
}

/**
 * 日志单行化：剥离 CR/LF，防止上游/用户输入伪造日志行（CWE-117）。
 *
 * 2026-10-27 pass 2 下沉到这里：原先 search.js 与 routes.js 各有一份**完全相同的实现**，
 * 拆分后 4 个源文件都要用，留在 search.js 会逼 sources/* 反向 require（正是循环依赖的来源）。
 */
function logSafe(value) {
  return String(value == null ? '' : value).replace(/[\r\n]+/g, ' ');
}

module.exports = {
  UPSTREAM_TIMEOUT,
  SEARCH_TOTAL_BUDGET,
  withTimeout,
  logSafe,
};
