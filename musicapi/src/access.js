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

// 刷新端点：有令牌必须对上；未配置令牌时放行本机与私网。
// 私网必须放行：后端容器经 compose 网络调用（源 IP 是 172.x，不是 127.0.0.1），
// 只判 loopback 会让 /internal/netease-rotate 永远 403、自动快切整条链失效
// （2026-10-19 线上实测复现：后端容器 curl 到网关 = 403）。
// 端口仅绑定 127.0.0.1、网关不被 nginx 反代，公网不可达，私网放行等价于"同栈内部调用"。
function canRefreshCookie(req) {
  if (hasAdminToken(req)) return true;
  if (adminTokenConfigured()) return false;
  return isPrivate(clientIp(req));
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
