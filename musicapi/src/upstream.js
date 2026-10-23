// ==================== 统一上游 HTTP 客户端 ====================
// 把散落各处的 axios.get 收口到一处，统一：超时 / 响应体积上限 / 重试 / 错误归一 / 指标 / 日志脱敏。
//
// 安全红线（务必遵守）：
//   日志只允许出现 host + pathname。URL query、headers、Cookie 一律不落日志——
//   网易云部分 URL 里带 cookie/token，明文打日志等于泄漏凭据。
const axios = require('axios');
const net = require('net');
const logger = require('./logger');
const { upstreamRequestTotal, upstreamDuration } = require('./metrics');

const DEFAULT_TIMEOUT_MS = 10000; // 与 search.js 的 UPSTREAM_TIMEOUT 同值
// 8MB：上游都是 JSON / 极小的 CDN Range 探活，正常响应远小于此；
// 超限即视为异常（防上游巨大响应把网关内存打满，云上仅 1.6G）。
const DEFAULT_MAX_BYTES = 8 * 1024 * 1024;
const RETRY_BASE_MS = 200; // 指数退避基数：200ms × 2^n
const LABEL_MAX_LEN = 64;

// 失败类型（也是指标 outcome 的取值，与 ok 一起构成闭集）
const KIND = {
  TIMEOUT: 'timeout',
  NETWORK: 'network',
  HTTP: 'http',
  TOO_LARGE: 'too_large',
  ABORTED: 'aborted',
  BLOCKED: 'blocked', // 目标被安全策略拒绝（私网/环回等，见 isPrivateHost）
  UNKNOWN: 'unknown',
};

// 只有网络/超时值得重试：4xx/5xx 是上游明确表态，重试只会放大压力；
// too_large/aborted 重试也不会变好。
const RETRYABLE_KINDS = new Set([KIND.NETWORK, KIND.TIMEOUT]);

// 可归为"网络"的 Node/undici 错误码（连接没建立起来，或连接被对端掐断）
const NETWORK_CODES = new Set([
  'ECONNREFUSED',
  'ENOTFOUND',
  'EAI_AGAIN',
  'ECONNRESET',
  'EHOSTUNREACH',
  'ENETUNREACH',
  'EPIPE',
  'EAI_FAIL',
]);

/**
 * 上游调用失败统一错误。
 * kind: 'timeout' | 'network' | 'http' | 'too_large' | 'aborted' | 'unknown'
 * 原始错误保留在 cause，message 里不含 URL query/headers/Cookie。
 */
class UpstreamError extends Error {
  constructor(kind, message, { label = 'unknown', status = null, host = 'unknown', elapsedMs = 0, cause } = {}) {
    super(message, cause ? { cause } : undefined);
    this.name = 'UpstreamError';
    this.kind = kind;
    this.label = label;
    this.status = status;
    this.host = host;
    this.elapsedMs = elapsedMs;
  }
}

/**
 * label 白名单化：只接受代码里写死的常量（字母数字 . _ / -），最长 64。
 * 含 ? & = 空白等字符一律判为"疑似拼了 URL/query"整段丢弃为 unknown，
 * 防止把 URL 或 sourceId 拼进指标标签造成基数爆炸/凭据泄漏。
 */
function normalizeLabel(label) {
  if (typeof label !== 'string') return 'unknown';
  const trimmed = label.trim();
  if (!trimmed || /[?&=\s]/.test(trimmed)) return 'unknown';
  const cleaned = trimmed.replace(/[^A-Za-z0-9._\/-]/g, '_').slice(0, LABEL_MAX_LEN);
  return cleaned || 'unknown';
}

/** 只取 host + pathname（绝不带 search/query），用于脱敏日志；hostname 供私网判断。 */
function describeUrl(rawUrl) {
  try {
    const u = new URL(String(rawUrl));
    return {
      host: u.host || u.hostname || 'invalid',
      hostname: u.hostname || '',
      path: u.pathname || '/',
    };
  } catch (e) {
    return { host: 'invalid', hostname: '', path: '/' };
  }
}

// 云厂商元数据域名（会解析到 169.254.x）：字面量判断拦不住，单独列黑名单。
// 不做 DNS 解析校验——那会引入每次请求的额外延迟，且 DNS rebinding 仍可绕过；
// 本层定位是"上游响应把地址直接指到内网"的主路径。
const METADATA_HOSTS = new Set([
  'metadata.tencentyun.com',
  'metadata.google.internal',
  'metadata.azure.internal',
]);

/** 把 IPv6 字面量展开成 8 个 16 位整数；非法返回 null。支持内嵌点分 IPv4 与 zone id。 */
function ipv6Groups(host) {
  let h = String(host).replace(/%.*$/, '');
  const dotted = h.match(/(\d+\.\d+\.\d+\.\d+)$/);
  if (dotted) {
    const oct = dotted[1].split('.').map(Number);
    if (oct.length !== 4 || oct.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return null;
    h = h.slice(0, -dotted[1].length) + `${((oct[0] << 8) | oct[1]).toString(16)}:${((oct[2] << 8) | oct[3]).toString(16)}`;
  }
  const halves = h.split('::');
  if (halves.length > 2) return null;
  const left = halves[0] ? halves[0].split(':') : [];
  const right = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  const groups = halves.length === 2
    ? [...left, ...Array(8 - left.length - right.length).fill('0'), ...right]
    : left;
  if (groups.length !== 8) return null;
  const nums = groups.map((g) => parseInt(g, 16));
  return nums.every((n) => Number.isInteger(n) && n >= 0 && n <= 0xffff) ? nums : null;
}

/**
 * 私网/环回/链路本地等"不应被上游 URL 指向"的目标（二阶 SSRF 防护）。
 * 背景：CDN 候选、取链地址这类 URL 来自上游响应体，若上游被污染/中间人，
 * 可把请求引向内网服务（云元数据 169.254.169.254、Redis、本机端口）。
 * 覆盖：IPv4 各私网段、IPv6 ULA/链路本地、IPv4-mapped/compatible 写法
 * （`::ffff:7f00:1`、`::7f00:1` 这类 WHATWG URL 归一化后的形式）、
 * 末尾点 FQDN（`localhost.`）与元数据域名。域名解析到私网（DNS rebinding）
 * 不在本层能力内。
 */
function isPrivateHost(host) {
  if (!host) return true; // 解析失败一律视为不安全
  let h = String(host).toLowerCase().replace(/^\[|\]$/g, '');
  h = h.replace(/\.+$/, ''); // 末尾点 FQDN 与不带点等价（localhost. / foo.local.）
  if (!h) return true;
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')) {
    return true;
  }
  if (METADATA_HOSTS.has(h)) return true;
  if (net.isIPv4(h)) {
    const parts = h.split('.').map(Number);
    const [a, b] = parts;
    if (a === 0 || a === 10 || a === 127) return true;              // 0/8、10/8、127/8
    if (a === 169 && b === 254) return true;                        // link-local（含云元数据）
    if (a === 172 && b >= 16 && b <= 31) return true;               // 172.16/12
    if (a === 192 && b === 168) return true;                        // 192.168/16
    if (a === 100 && b >= 64 && b <= 127) return true;              // CGNAT 100.64/10
    return false;
  }
  if (net.isIPv6(h)) {
    const g = ipv6Groups(h);
    if (!g) return true;
    if (g.every((n) => n === 0)) return true;                       // ::
    if (g.slice(0, 7).every((n) => n === 0) && g[7] === 1) return true; // ::1
    if ((g[0] & 0xfe00) === 0xfc00) return true;                    // ULA fc00::/7
    if ((g[0] & 0xffc0) === 0xfe80) return true;                    // link-local fe80::/10
    // IPv4-mapped（::ffff:a.b.c.d）与已废弃的 IPv4-compatible（::a.b.c.d）：
    // URL 归一化会写成十六进制（如 ::ffff:7f00:1），必须取末 32 位按 IPv4 再判。
    const topZero = g.slice(0, 5).every((n) => n === 0);
    if (topZero && (g[5] === 0xffff || g[5] === 0)) {
      return isPrivateHost([g[6] >> 8, g[6] & 0xff, g[7] >> 8, g[7] & 0xff].join('.'));
    }
    return false;
  }
  return false;
}

/** 重定向命中私网时抛出的标记错误，由 classifyError 归为 blocked。 */
function redirectBlockedError(target) {
  const err = new Error(`redirect to private host blocked: ${describeUrl(target).host}`);
  err.code = 'ERR_UPSTREAM_BLOCKED';
  return err;
}

function normalizeTimeout(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : DEFAULT_TIMEOUT_MS;
}

function normalizeMaxBytes(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : DEFAULT_MAX_BYTES;
}

/**
 * 合并超时信号与调用方信号。
 * AbortSignal.timeout 会在连接/DNS 阶段也生效（axios 的 timeout 覆盖不到全部场景），
 * axios 的 timeout 继续保留作双保险。
 */
function mergeSignals(timeoutMs, callerSignal) {
  const hasTimeoutFactory = typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function';
  const timeoutSignal = hasTimeoutFactory ? AbortSignal.timeout(timeoutMs) : null;
  const hasCaller = Boolean(callerSignal && typeof callerSignal.aborted === 'boolean');
  if (!hasCaller) return { timeoutSignal, signal: timeoutSignal || undefined };
  // Node 20.3+/24 有 AbortSignal.any；没有时按约定退化为只用 timeout（宁可不响应外部取消，也不丢超时）
  if (timeoutSignal && typeof AbortSignal.any === 'function') {
    return { timeoutSignal, signal: AbortSignal.any([timeoutSignal, callerSignal]) };
  }
  return { timeoutSignal, signal: timeoutSignal || callerSignal };
}

/** 失败归因：顺序即优先级，先判调用方取消，再判超体积，再判超时，最后按错误码/响应归类。 */
function classifyError(error, { callerSignal, timeoutSignal } = {}) {
  if (!error) return KIND.UNKNOWN;
  if (error.code === 'ERR_UPSTREAM_BLOCKED') return KIND.BLOCKED;
  if (callerSignal && callerSignal.aborted) return KIND.ABORTED;
  const message = String(error.message || '');
  // axios http 适配器超体积抛 "maxContentLength size of N exceeded"（code=ERR_BAD_RESPONSE）
  if (/maxContentLength|maxBodyLength/i.test(message)) return KIND.TOO_LARGE;
  if (timeoutSignal && timeoutSignal.aborted) return KIND.TIMEOUT;
  if (error.name === 'TimeoutError') return KIND.TIMEOUT;
  const code = error.code;
  if (code === 'ECONNABORTED' || code === 'ESOCKETTIMEDOUT') {
    // axios 自身 timeout 的 message 是 "timeout of Nms exceeded"；纯 "aborted" 多为连接被掐断
    return /timeout/i.test(message) ? KIND.TIMEOUT : KIND.NETWORK;
  }
  if (code === 'ETIMEDOUT') return KIND.TIMEOUT;
  if (code === 'ERR_CANCELED') return KIND.ABORTED;
  if (error.response) return KIND.HTTP; // 上游有响应（4xx/5xx 且调用方未放行）
  if (NETWORK_CODES.has(code)) return KIND.NETWORK;
  if (/timeout/i.test(message)) return KIND.TIMEOUT;
  return KIND.UNKNOWN;
}

function statusOf(error) {
  const status = error && error.response && error.response.status;
  return Number.isInteger(status) ? status : null;
}

function sleep(ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    // 退避等待不该成为进程活着的理由（单测里尤其明显）
    if (typeof timer.unref === 'function') timer.unref();
  });
}

function recordOutcome(label, outcome, startedAt) {
  upstreamRequestTotal.inc({ label, outcome });
  upstreamDuration.observe({ label }, (Date.now() - startedAt) / 1000);
}

/** 每条失败只打一条 WARN：只含 label/kind/status/host/path/elapsed，绝不含 query/headers/cookie。 */
function logFailure({ label, kind, status, endpoint, elapsedMs }) {
  const statusTag = status == null ? '-' : String(status);
  logger.writeLog(
    'api',
    'WARN',
    `[UPSTREAM] label=${label} kind=${kind} status=${statusTag} host=${endpoint.host} path=${endpoint.path} elapsed=${Math.round(elapsedMs)}ms`,
  );
}

/**
 * 统一上游 GET。
 *
 * @param {string} url 上游地址（允许带 query，但日志里不会出现）
 * @param {object} [options]
 * @param {string} [options.label] 必填语义的常量标签（如 'netease/search'），缺省 'unknown'
 * @param {object} [options.params]
 * @param {object} [options.headers]
 * @param {number} [options.timeoutMs=10000]
 * @param {number} [options.maxBytes=8388608] 响应体上限（maxContentLength/maxBodyLength）
 * @param {Function} [options.validateStatus] 不传=axios 默认（仅 2xx 通过）
 * @param {number} [options.retries=0] 仅对 network/timeout 生效，200ms×2^n 退避
 * @param {string} [options.responseType]
 * @param {number} [options.maxRedirects] 原样透传（取链需要 0 手动跟 302）
 * @param {AbortSignal} [options.signal]
 * @returns {Promise<import('axios').AxiosResponse>}
 * @throws {UpstreamError}
 */
async function upstreamGet(url, options = {}) {
  const label = normalizeLabel(options.label);
  const timeoutMs = normalizeTimeout(options.timeoutMs);
  const maxBytes = normalizeMaxBytes(options.maxBytes);
  const maxRetries = Number.isInteger(options.retries) && options.retries > 0 ? options.retries : 0;
  const callerSignal = options.signal;
  const endpoint = describeUrl(url);
  const startedAt = Date.now();
  // 二阶 SSRF 防护：默认拒绝私网/环回目标；本地联调与单测显式 allowPrivate: true。
  if (options.allowPrivate !== true && isPrivateHost(endpoint.hostname)) {
    recordOutcome(label, KIND.BLOCKED, startedAt);
    logFailure({
      label,
      kind: KIND.BLOCKED,
      status: null,
      endpoint,
      elapsedMs: Date.now() - startedAt,
    });
    throw new UpstreamError(KIND.BLOCKED, `upstream ${label} blocked`, {
      label,
      host: endpoint.host,
      elapsedMs: Date.now() - startedAt,
    });
  }
  let attempt = 0;

  for (;;) {
    let blockedByRedirect = false;
    const { timeoutSignal, signal } = mergeSignals(timeoutMs, callerSignal);
    const config = {
      params: options.params,
      headers: options.headers,
      timeout: timeoutMs, // 双保险：axios 自身超时
      maxContentLength: maxBytes,
      maxBodyLength: maxBytes,
      responseType: options.responseType,
      signal,
    };
    if (typeof options.maxRedirects === 'number') config.maxRedirects = options.maxRedirects;
    if (typeof options.validateStatus === 'function') config.validateStatus = options.validateStatus;
    // 校验每一跳重定向：首跳公网、302 到 169.254.169.254 也必须被拦住。
    // 注意 allowPrivate 只放开首跳（本地联调），不放开重定向目标。
    config.beforeRedirect = (redirectOptions) => {
      const target = `${redirectOptions.protocol}//${redirectOptions.hostname}` +
        `${redirectOptions.port ? `:${redirectOptions.port}` : ''}${redirectOptions.path || '/'}`;
      if (isPrivateHost(describeUrl(target).hostname)) {
        // follow-redirects 会把抛出的错误重新包装，code 可能丢失；用闭包标志兜底归因。
        blockedByRedirect = true;
        throw redirectBlockedError(target);
      }
    };

    try {
      const response = await axios.get(url, config);
      recordOutcome(label, 'ok', startedAt);
      return response;
    } catch (error) {
      const kind = blockedByRedirect ? KIND.BLOCKED : classifyError(error, { callerSignal, timeoutSignal });
      // 重试预算按"已用过的额外尝试次数"计：attempt<maxRetries 才再试
      if (RETRYABLE_KINDS.has(kind) && attempt < maxRetries) {
        attempt += 1;
        // eslint-disable-next-line no-await-in-loop
        await sleep(RETRY_BASE_MS * (2 ** (attempt - 1)));
        continue;
      }
      const elapsedMs = Date.now() - startedAt;
      const status = statusOf(error);
      recordOutcome(label, kind === KIND.HTTP ? 'http' : kind, startedAt);
      logFailure({ label, kind, status, endpoint, elapsedMs });
      const statusPart = status == null ? '' : ` status=${status}`;
      throw new UpstreamError(kind, `upstream ${label} ${kind}${statusPart} after ${elapsedMs}ms`, {
        label,
        status,
        host: endpoint.host,
        elapsedMs,
        cause: error,
      });
    }
  }
}

module.exports = {
  UpstreamError,
  upstreamGet,
  classifyError,
  normalizeLabel,
  describeUrl,
  isPrivateHost,
  DEFAULT_TIMEOUT_MS,
  DEFAULT_MAX_BYTES,
  KIND,
};
