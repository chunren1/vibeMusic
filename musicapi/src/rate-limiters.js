// ==================== 速率限制（防滥用/爬取） ====================
// 计数键（2026-10-22 加固）：容器拓扑下后端容器经 172.x 调网关，trust proxy=false
// 会让所有请求共享同一个 req.ip → 100/min 实际退化成"全局吞吐上限"（实测峰值 70/min）。
// 故键优先级：可信身份头 X-User-Id > X-Trace-Id 的稳定哈希 > 对端 IP。
//
// 身份头可被伪造，只有在"能确认对端是内网/可信反代"时才采信：
//   - trust proxy=false（默认）：req.ip 就是对端真实地址，私网/本机 = 内网后端或容器；
//     公网客户端伪造 X-User-Id 一律无视，回落 IP 计数；
//   - trust proxy 开启（MUSICAPI_TRUST_PROXY=1/true/N）：说明网关前有可信反代，
//     此时必须由反代**覆写/剥离** X-User-Id、X-Trace-Id（否则客户端可自行伪造绕过限流）。
//     Express 的 trust proxy 只影响 req.ip 解析，不会清洗这两个自定义头，责任在反代配置。
//
// 放行端点：/health、/metrics、/reload 不计入限流（探活/抓取/配置重载需稳定可达），
// 否则健康检查会把限流额度吃满、Prometheus 抓取被 429 掉。
const crypto = require('crypto');
const rateLimit = require('express-rate-limit');
const { ipKeyGenerator } = require('express-rate-limit');
const { isPrivate } = require('./access');

const WINDOW_MS = 60 * 1000;

/** 不限流的端点（按路径精确匹配，容忍尾斜杠，忽略查询串）。 */
const SKIP_PATHS = new Set(['/health', '/metrics', '/reload']);

const USER_ID_HEADER = 'x-user-id';
const TRACE_ID_HEADER = 'x-trace-id';
// 只接受有界、可安全入键的字符集：避免超长/含分隔符的身份值污染计数键
const USER_ID_RE = /^[A-Za-z0-9._:@-]{1,64}$/;
const TRACE_ID_RE = /^[A-Za-z0-9._:@-]{1,128}$/;

/**
 * 解析 MUSICAPI_TRUST_PROXY：
 *   未配置 / false / 0 / off → false（默认，无可信反代）
 *   true / on / yes        → true（信任全部跳数，仅当网关不可被公网直连时使用）
 *   正整数 N               → N（信任最近 N 跳反代，推荐）
 * 注意：开启前必须确认公网无法直连网关，否则 X-Forwarded-For 可伪造 → 限流可按 IP 绕过。
 */
function parseTrustProxyValue(raw) {
  if (raw === undefined || raw === null) return false;
  const s = String(raw).trim();
  if (!s || /^(false|0|off|no)$/i.test(s)) return false;
  if (/^(true|on|yes)$/i.test(s)) return true;
  const n = Number(s);
  if (Number.isInteger(n) && n >= 1) return n;
  return false;
}

function isTrustProxyEnabled() {
  const v = parseTrustProxyValue(process.env.MUSICAPI_TRUST_PROXY);
  return v === true || (typeof v === 'number' && v >= 1);
}

/** 取头值（多值取第一个，去空白；非字符串一律视为缺失）。 */
function headerValue(req, name) {
  const raw = req && req.headers ? req.headers[name] : undefined;
  const value = Array.isArray(raw) ? raw[0] : raw;
  return typeof value === 'string' ? value.trim() : '';
}

/** 对端地址（trust proxy=false 时即真实 peer；剥掉 IPv4-mapped 前缀便于复用 isPrivate）。 */
function peerIp(req) {
  const raw = (req && (req.ip || (req.socket && req.socket.remoteAddress))) || 'unknown';
  return String(raw).replace(/^::ffff:/, '');
}

/**
 * 身份头是否可信：trust proxy 开启 → 交给可信反代保证头不可伪造；
 * 否则仅在内网/本机对端（后端容器 172.x、本机 127.0.0.1）采信。
 * MUSICAPI_TRUST_IDENTITY_HEADERS=false 可整体关闭（纯按 IP 计数的保守模式）。
 */
function isIdentityTrusted(req) {
  if (/^(false|0|off|no)$/i.test(process.env.MUSICAPI_TRUST_IDENTITY_HEADERS || '')) return false;
  if (isTrustProxyEnabled()) return true;
  return isPrivate(peerIp(req));
}

/** X-Trace-Id 做稳定哈希：键长有界、不把调用方的原始 trace 值写进 store。 */
function hashTraceId(traceId) {
  return crypto.createHash('sha256').update(traceId).digest('hex').slice(0, 16);
}

/**
 * 限流计数键（express-rate-limit keyGenerator）。
 * 身份头非法/不可信时回落 `ip:<normalized>`；IPv6 经 ipKeyGenerator 归一化（/56 子网）。
 */
function rateLimitKey(req) {
  if (isIdentityTrusted(req)) {
    const userId = headerValue(req, USER_ID_HEADER);
    if (userId && USER_ID_RE.test(userId)) return `user:${userId}`;
    const traceId = headerValue(req, TRACE_ID_HEADER);
    if (traceId && TRACE_ID_RE.test(traceId)) return `trace:${hashTraceId(traceId)}`;
  }
  return `ip:${ipKeyGenerator(peerIp(req))}`;
}

/** 放行判定：按路径精确匹配（容忍尾斜杠），忽略查询串。 */
function shouldSkip(req) {
  const raw = (req && (req.originalUrl || req.url)) || '';
  const pathOnly = String(raw).split('?')[0].replace(/\/+$/, '') || '/';
  return SKIP_PATHS.has(pathOnly);
}

function buildLimiter(max, message) {
  return rateLimit({
    windowMs: WINDOW_MS,
    max,
    standardHeaders: true,
    legacyHeaders: false,
    // 探活/抓取/重载不计额度（见文件头说明）
    skip: shouldSkip,
    // 优先按调用方身份计数，避免容器拓扑下坍缩为单 IP 全局上限
    keyGenerator: rateLimitKey,
    message,
  });
}

// ---- 额度分层（2026-10-23 调整）----
// 后端（RateLimitFilter）已按**用户身份**限流：search 20/min、stream 60/min。
// 网关这里拿不到每用户身份（后端扇出在独立线程池里执行，请求上下文不跨线程），
// 所有后端调用会落进同一个 ip:172.x 桶 —— 所以网关的额度是**全局聚合上限**，
// 必须高于“用户数 × 单用户额度”，否则会替后端提前误伤。
//   单用户 20 次搜索 → 5 个并发用户即 100 次/分，故 search 取 120；
//   单用户 60 次取链（含拖拽重试）→ 4 个并发用户即 240 次/分，故 url 取 240；
//   全局 300/min = 观测峰值 70/min 的 4 倍冗余，仍保留对单点突发/爬虫的兜底。
// 真正的“每用户公平”由后端负责；这里的职责是保护上游平台不被我们自己打爆。
const globalLimiter = buildLimiter(300, { code: 429, message: '请求过于频繁，请稍后再试', data: null });

const searchLimiter = buildLimiter(120, { code: 429, message: '搜索请求过于频繁，请稍后再试', data: null });

const urlLimiter = buildLimiter(240, { code: 429, message: '请求过于频繁，请稍后再试', data: null });

module.exports = {
  globalLimiter,
  searchLimiter,
  urlLimiter,
  rateLimitKey,
  shouldSkip,
  isIdentityTrusted,
  parseTrustProxyValue,
  SKIP_PATHS,
};
