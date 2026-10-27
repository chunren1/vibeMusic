// ==================== 路由层公共件（查询解析 / 脱敏 / 缓存键）====================
//
// 2026-10-27 网关重构 pass 3：从 routes.js（648 纯 LOC）按职责拆出，**行为零变更**。
// 这里只放**无状态纯函数**：查询参数归一、上游响应脱敏、搜索缓存键派生。
// 任何需要 app / 中间件的东西都不要放进来（那是各 *-routes.js 的事）。
const crypto = require('crypto');
const cookie = require('../cookie');

// 500 统一话术：细节只进 writeLog，不回传调用方（与全局错误处理器口径一致）
const GENERIC_500 = '服务繁忙，请稍后重试';

// Express 4 默认 query parser 下 ?k=a&k=b 解析为数组；?k[x]=1 解析为对象。
// 统一取首个标量，缺失/非法由各路由判 400（此前数组直接 .trim() 抛 TypeError → 500 泄漏堆栈文本）。
function firstQuery(v) {
  if (Array.isArray(v)) return v.length > 0 ? v[0] : undefined;
  return v;
}
function queryStr(v) {
  const first = firstQuery(v);
  return typeof first === 'string' ? first : undefined;
}

// ==================== /netease/* 通配路由防护（API-C1） ====================
// 上游 NeteaseCloudMusicApi 的 createOption 会消费 domain/proxy/realIP/ua/crypto/e_r/cookie
// 等保留键：调用方传入 ?domain=http://attacker 即可把携带 MUSIC_U 真实凭证的请求发往
// 任意主机（SSRF + 凭证外发，审计已动态复现）。此处默认拒绝：只放行白名单内的标量参数，
// 服务端 Cookie 仍由 withNeteaseCookie 统一注入；响应体递归剥离凭证字段后再返回。
const NETEASE_PARAM_ALLOWLIST = new Set([
  'keywords', 'keyword', 'limit', 'offset', 'type', 'id', 'ids',
  'level', 'uid', 'idx',
]);
// 保留键按小写比对，拦截 Cookie/Domain/Proxy 等大小写变体
const NETEASE_RESERVED_KEYS = new Set([
  'cookie', 'domain', 'proxy', 'realip', 'real_ip', 'ip', 'ua',
  'crypto', 'e_r', 'url', 'host', 'headers', 'agent',
  'httpagent', 'httpsagent', 'socket', 'method',
]);
const NETEASE_MAX_STR_LEN = 2000;

function sanitizeNeteaseParams(query = {}, body = {}) {
  const merged = { ...(query || {}), ...(body || {}) };
  const out = {};
  for (const [k, v] of Object.entries(merged)) {
    if (NETEASE_RESERVED_KEYS.has(String(k).toLowerCase())) continue;
    if (!NETEASE_PARAM_ALLOWLIST.has(k)) continue;
    if (typeof v === 'string') {
      if (v.length === 0 || v.length > NETEASE_MAX_STR_LEN) continue;
      out[k] = v;
    } else if (typeof v === 'number' && Number.isFinite(v)) {
      out[k] = v;
    } else if (typeof v === 'boolean') {
      out[k] = v;
    }
    // 数组/对象一律丢弃：上游库不期望复合参数，且可绕过标量校验
  }
  return out;
}

// 上游响应可能回显登录态（cookie/MUSIC_U/MUSIC_A/__csrf），递归剥离后再返回调用方
const SECRET_KEY_RE = /^(cookie|music_u|music_a|__csrf|csrf|csrf_token|csrftoken)$/i;

function scrubSecrets(value, depth = 0) {
  if (depth > 10 || value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) value[i] = scrubSecrets(value[i], depth + 1);
    return value;
  }
  for (const k of Object.keys(value)) {
    if (SECRET_KEY_RE.test(k)) { delete value[k]; continue; }
    value[k] = scrubSecrets(value[k], depth + 1);
  }
  return value;
}

// 上游响应统一出口：一律经 scrubSecrets 剥离回显的登录态再返回调用方。
// 收敛前 /lyric、/song/url/v1、/personalized 直接 res.json(result.body)，与 680 行通配路由
// 两套口径——同一上游的响应不该有的脱敏有的不脱敏。
function sendUpstreamBody(res, body) {
  res.json(scrubSecrets(body));
}

// /refresh-qq-cookie 日志脱敏：只记长度 + 失败时 scrubbed stderr 尾部（≤200 字符）。
// 遵循 cookie.js「日志只打长度、绝不打值」规范；凭证原文绝不进日志文件。
// 敏感键清单与 scripts/get_qq_cookie.mjs 的 NEEDED 保持一致（psrf_qqaccess_token/psrf_qqopenid
// 此前漏网）；另按字段名形态兜底（psrf_ 前缀、token/key/secret/passwd/password），
// 同时覆盖 `k=v` 与 JSON `"k":"v"` 两种形态。
const SENSITIVE_COOKIE_KEYS = new Set([
  'MUSIC_U', 'qqmusic_key', 'qm_keyst', 'uin',
  'psrf_qqunionid', 'psrf_qqrefresh_token', 'psrf_qqaccess_token', 'psrf_qqopenid',
]);

function isSensitiveKey(k) {
  return SENSITIVE_COOKIE_KEYS.has(k) || /^psrf_|token|key|secret|passwd|password/i.test(k);
}

function scrubCookieValues(s) {
  return String(s)
    // JSON 形态："k":"v" 或 "k": v
    .replace(/"([A-Za-z_][A-Za-z0-9_]*)"\s*:\s*("[^"]*"|[^,}\s]+)/g,
      (m, k) => (isSensitiveKey(k) ? `"${k}":"***"` : m))
    // Cookie 头形态：k=v（值可含 = 填充，读到 ; 或空白为止）
    .replace(/\b([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^;\s]+)/g,
      (m, k) => (isSensitiveKey(k) ? `${k}=***` : m));
}

// /search 内部字段：绝不进缓存、不出响应。higherQuality 曾存对象引用，
// 等时长自赋值即成循环引用（JSON 序列化抛错 → 500，且毒化缓存）。
// 如今只存标量时长提示，此处做递归兜底剥离（含历史毒化条目与嵌套）。
const SEARCH_INTERNAL_FIELDS = new Set(['higherQuality', 'higherQualityDuration', '_raw', 'finalScore']);

function stripSearchInternals(value, seen = new Set()) {
  if (value === null || typeof value !== 'object') return value;
  if (seen.has(value)) return undefined;
  seen.add(value);
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      const cleaned = stripSearchInternals(value[i], seen);
      if (cleaned === undefined) { value.splice(i--, 1); }
      else value[i] = cleaned;
    }
    return value;
  }
  for (const k of Object.keys(value)) {
    if (SEARCH_INTERNAL_FIELDS.has(k)) { delete value[k]; continue; }
    const cleaned = stripSearchInternals(value[k], seen);
    if (cleaned === undefined) delete value[k];
    else value[k] = cleaned;
  }
  return value;
}

// G1：/search 缓存键的 Cookie 派生维度。userCk 经 resolveNeteaseCookie 归一
// （per-request 用户 Cookie > 共享 NETEASE_COOKIE > ''）；ckDim 只存 sha256
// 摘要前 16 字符，原文与完整摘要绝不进日志/指标。
// 三态语义：per-request 指纹桶 / 共享 Cookie 桶（未带 per-request 时统一落此，
// 与共享 Cookie 摘要相同）/ 'anon' 桶（仅当共享 Cookie 也为空时出现）。
function searchCookieDim(req) {
  const userCk = cookie.resolveNeteaseCookie(req);
  if (!userCk) return 'anon';
  return crypto.createHash('sha256').update(userCk).digest('hex').slice(0, 16);
}

function buildSearchCacheKey(kw, maxRank, safePrefer, req) {
  return `search:${kw}:${maxRank}:${safePrefer ?? 'none'}:${searchCookieDim(req)}`;
}

module.exports = {
  GENERIC_500,
  firstQuery,
  queryStr,
  sanitizeNeteaseParams,
  scrubSecrets,
  sendUpstreamBody,
  SENSITIVE_COOKIE_KEYS,
  isSensitiveKey,
  scrubCookieValues,
  stripSearchInternals,
  searchCookieDim,
  buildSearchCacheKey,
};
