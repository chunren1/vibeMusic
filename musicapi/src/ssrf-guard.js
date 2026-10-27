// ==================== 上游 URL 安全闸（二阶 SSRF 防护）====================
//
// 2026-10-27 网关重构 pass 3：从 upstream.js 原样搬出，**行为零变更**。
// 为什么单独成文件：upstream.js 已 263 纯 LOC（超 250 硬约束），而这块逻辑与
// 「怎么发请求」无关——它是纯函数集合（URL 解析 + 私网判定），可以独立单测。
//
// 背景：CDN 候选、取链地址这类 URL 来自**上游响应体**，若上游被污染/中间人，
// 可把请求引向内网服务（云元数据 169.254.169.254、Redis、本机端口）。
// 本层定位是"上游响应把地址直接指到内网"的主路径；域名解析到私网
// （DNS rebinding）不在本层能力内（见 isPrivateHost 注释）。
const net = require('net');

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
// 不做 DNS 解析校验——那会引入每次请求的额外延迟，且 DNS rebinding 仍可绕过。
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
 * 覆盖：IPv4 各私网段、IPv6 ULA/链路本地、IPv4-mapped/compatible 写法
 * （`::ffff:7f00:1`、`::7f00:1` 这类 WHATWG URL 归一化后的形式）、
 * 末尾点 FQDN（`localhost.`）与元数据域名。
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

module.exports = {
  describeUrl,
  isPrivateHost,
  ipv6Groups,
  redirectBlockedError,
  METADATA_HOSTS,
};
