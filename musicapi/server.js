// ==================== vibeMusic BFF 网关 — 应用装配入口 ====================
// 业务逻辑已拆分至 src/ 各模块，本文件只负责：
//   1. 中间件装配（CORS / body / 全局限流 / 访问日志 / Prometheus）
//   2. 基础端点（/ 、/health、/metrics、/favicon.ico）
//   3. Cookie 存活巡检调度
//   4. 路由注册 + 全局错误处理 + 启动

// 加载项目根目录 .env（Cookie/密钥等）
require('dotenv').config({ path: require('path').join(__dirname, '..', '.env') });

const express = require('express');
const cors = require('cors');

const { writeLog } = require('./src/logger');
const { globalLimiter } = require('./src/rate-limiters');
const { register, metricsMiddleware } = require('./src/metrics');
const { canReadMetrics, clientIp } = require('./src/access');
const cookie = require('./src/cookie');
const { searchCache } = require('./src/search');
const registerRoutes = require('./src/routes');

const app = express();
const PORT = 3000;

// 部署现状：musicapi 3000 直绑宿主，前面无 nginx 反代（nginx/nginx.conf 无 musicapi 上游），
// 故保持 trust proxy=false（Express 默认），限流按直连 IP 计数；若日后加反代再改为 'trust proxy', 1。
app.set('trust proxy', false);

// CORS 键名与仓库统一为复数 CORS_ORIGINS（docker-compose.yml:222、.env:53-55 均为复数，
// 逗号分隔多源）；只改代码一侧，compose/文档不动。
const corsOrigins = (process.env.CORS_ORIGINS || '')
  .split(',')
  .map(s => s.trim())
  .filter(Boolean);

// ==================== 中间件 ====================
app.use(cors({
  origin: corsOrigins.length > 0 ? corsOrigins : 'http://localhost:5173',
  credentials: true
}));
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: true, limit: '1mb' }));

// Prometheus 中间件必须在限流器之前：被限流的 429 也要进指标，否则限流次数监控不可见
app.use(metricsMiddleware);
app.use(globalLimiter);  // 全局限流

// 访问日志中间件
app.use((req, res, next) => {
  // 跳过静态资源请求的日志
  if (!req.url.startsWith('/favicon')) {
    writeLog('access', 'INFO', `${req.method} ${req.originalUrl}`);
  }
  next();
});

// Prometheus HTTP 指标中间件（记录请求耗时和数量）
app.use(metricsMiddleware);

// 根路由（健康检查）
app.get('/', (req, res) => {
  res.json({ service: 'vibeMusic API', version: '3.0', status: 'running', endpoints: ['/netease/search', '/qq/search', '/migu/search', '/migu/url', '/lyric', '/personalized', '/cookie-status', '/health'] });
});

app.get('/health', (req, res) => {
  res.json({
    code: 200,
    message: 'Music API Service v3 (Unified Cookie + SLA)',
    data: {
      netease: cookie.cookieStatus.netease ? 'available' : 'degraded',
      qq: cookie.cookieStatus.qq ? 'available' : 'degraded',
      cacheSize: searchCache.size,
      uptime: process.uptime(),
      timestamp: new Date().toISOString(),
    },
  });
});

// Prometheus 指标暴露端点：本机/私网（容器抓取）或管理令牌可读，公网无令牌 403
app.get('/metrics', async (req, res) => {
  if (!canReadMetrics(req)) {
    writeLog('access', 'WARN', `[/metrics] 拒绝公网访问: ${clientIp(req)}`);
    return res.status(403).json({ code: 403, message: 'Forbidden', data: null });
  }
  try {
    res.set('Content-Type', register.contentType);
    res.end(await register.metrics());
  } catch (err) {
    writeLog('api', 'ERROR', `[/metrics] ${err.message}`);
    res.status(500).json({ code: 500, message: '服务繁忙，请稍后重试', data: null });
  }
});

// favicon 占位（避免 404 日志）
app.get('/favicon.ico', (req, res) => res.status(204).end());

// 防止 qq-music-api 内部未捕获异常导致进程崩溃
process.on('unhandledRejection', (reason) => {
  writeLog('api', 'ERROR', `[unhandledRejection] ${reason?.message || reason}`);
});

// 启动时检查一次（容错：即使崩溃也不影响启动），之后每 15 分钟检查
(async () => { try { await cookie.checkCookies(); } catch (e) { writeLog('cookie', 'ERROR', `Cookie check crashed: ${e.message}`); } })();
setInterval(() => { cookie.checkCookies().catch(e => writeLog('cookie', 'ERROR', `Cookie timer failed: ${e.message}`)); }, 15 * 60 * 1000);

// ==================== 业务路由注册 ====================
registerRoutes(app);

// ==================== 全局错误处理 ====================

app.use((err, req, res, next) => {
  writeLog('api', 'ERROR', `[${req.method} ${req.path}] ${err.message}`);
  res.status(500).json({ code: 500, message: 'Internal Server Error' });
});

// ==================== 启动 ====================

const server = app.listen(PORT, () => {
  console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
  console.log(`  vibeMusic API v3`);
  console.log(`  http://localhost:${PORT}`);
  console.log(`  Cookie: 统一管理 (网易云 + QQ)`);
  console.log(`  监控: 每小时自动检查 (GET /cookie-status)`);
  console.log(`  日志: ./logs/*.YYYY-MM-DD.log (按天轮转，保留30天)`);
  console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
});
// HTTP keep-alive: 复用 TCP 连接，减少后端 → 网关握手开销
server.keepAliveTimeout = 65000; // 略大于 nginx 默认 60s
server.headersTimeout = 66000;
