// 管理端点访问控制（/refresh-qq-cookie、/metrics 共用）
// 令牌：MUSICAPI_ADMIN_TOKEN（query ?token= / 头 x-admin-token / Bearer）。
// 未配置令牌时默认收敛到最小可用范围：refresh 仅本机，metrics 仅本机+私网
// （Prometheus 经容器网段抓取，不配令牌也不中断；公网直接打 3000 会被 403）。
function clientIp(req) {
  const raw = req.ip || (req.socket && req.socket.remoteAddress) || '';
  return String(raw).replace(/^::ffff:/, '');
}

function isLoopback(ip) {
  return ip === '127.0.0.1' || ip === '::1' || ip === 'localhost' || /^127\./.test(ip);
}

function isPrivate(ip) {
  if (isLoopback(ip)) return true;
  if (/^10\./.test(ip)) return true;
  if (/^192\.168\./.test(ip)) return true;
  const m172 = ip.match(/^172\.(\d+)\./);
  if (m172 && Number(m172[1]) >= 16 && Number(m172[1]) <= 31) return true;
  return false;
}

function adminTokenConfigured() {
  return !!process.env.MUSICAPI_ADMIN_TOKEN;
}

function hasAdminToken(req) {
  const token = process.env.MUSICAPI_ADMIN_TOKEN;
  if (!token) return false;
  const q = req.query && req.query.token;
  const h = req.headers && (req.headers['x-admin-token'] || req.headers.authorization);
  const bearer = typeof h === 'string' && h.startsWith('Bearer ') ? h.slice(7) : h;
  return q === token || bearer === token;
}

// 刷新端点：有令牌必须对上；无令牌仅本机可调（无自动化调用方，可 fail-closed 到本机）
function canRefreshCookie(req) {
  if (hasAdminToken(req)) return true;
  if (!adminTokenConfigured() && isLoopback(clientIp(req))) return true;
  return false;
}

// 指标端点：令牌 > 本机/私网放行；公网无令牌拒绝
function canReadMetrics(req) {
  if (hasAdminToken(req)) return true;
  return isPrivate(clientIp(req));
}

module.exports = {
  clientIp,
  isLoopback,
  isPrivate,
  hasAdminToken,
  canRefreshCookie,
  canReadMetrics,
};
