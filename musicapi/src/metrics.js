// ==================== Prometheus 监控指标 ====================
const promClient = require('prom-client');

const register = new promClient.Registry();
promClient.collectDefaultMetrics({ register, prefix: 'musicapi_' });

// 自定义指标
const httpRequestTotal = new promClient.Counter({
  name: 'musicapi_http_requests_total',
  help: 'HTTP 请求总数',
  labelNames: ['method', 'path', 'status'],
  registers: [register],
});

const httpRequestDuration = new promClient.Histogram({
  name: 'musicapi_http_request_duration_seconds',
  help: 'HTTP 请求耗时（秒）',
  labelNames: ['method', 'path'],
  buckets: [0.01, 0.05, 0.1, 0.3, 0.5, 1, 3, 5, 10],
  registers: [register],
});

const cacheHitTotal = new promClient.Counter({
  name: 'musicapi_cache_hits_total',
  help: '缓存命中次数',
  labelNames: ['cache_type'],
  registers: [register],
});

const cookieStatusGauge = new promClient.Gauge({
  name: 'musicapi_cookie_status',
  help: 'Cookie 状态 (1=正常, 0=降级)',
  labelNames: ['platform'],
  registers: [register],
});

const upGauge = new promClient.Gauge({
  name: 'musicapi_up',
  help: '服务存活 (1=运行中)',
  registers: [register],
});
upGauge.set(1);

// Prometheus HTTP 指标中间件（记录请求耗时和数量）
function metricsMiddleware(req, res, next) {
  const pathLabel = req.route ? req.route.path : req.path;
  const end = httpRequestDuration.startTimer({ method: req.method, path: pathLabel });
  res.on('finish', () => {
    end();
    httpRequestTotal.inc({ method: req.method, path: pathLabel, status: res.statusCode });
  });
  next();
}

module.exports = {
  register,
  httpRequestTotal,
  httpRequestDuration,
  cacheHitTotal,
  cookieStatusGauge,
  upGauge,
  metricsMiddleware,
};
