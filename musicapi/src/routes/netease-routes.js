// ==================== 网易云路由（兼容旧 API + 通配代理）====================
//
// 2026-10-27 网关重构 pass 3：从 routes.js 原样搬出，**行为零变更**。
// 所有上游调用都走 src/sources/netease.js 隔离墙（全仓唯一 require 第三方库处），
// 且响应一律经 sendUpstreamBody 剥离回显登录态。
const netease = require('../sources/netease');
const config = require('../config-loader');
const cookie = require('../cookie');
const { writeLog } = require('../logger');
const { UPSTREAM_TIMEOUT, withTimeout, searchNetease } = require('../search');
const sourceHealth = require('../source-health');
const {
  GENERIC_500, describeError, firstQuery, queryStr, sanitizeNeteaseParams, sendUpstreamBody,
} = require('./helpers');

function registerNeteaseRoutes(app) {
  // ==================== 兼容旧 API 路由 (不变) ====================

  app.get('/lyric', async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      if (!id) return res.status(400).json({ code: 400, message: 'id is required' });
      if (!/^\d{4,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await withTimeout(
        netease.invoke('lyric', cookie.withNeteaseCookie({ id }, req)),
        UPSTREAM_TIMEOUT, 'netease/lyric'
      );
      sendUpstreamBody(res, result.body);
    } catch (error) {
      writeLog('api', 'ERROR', `[/lyric] ${describeError(error)}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });

  /**
   * 网易云风控（-460）短退避重试：线上实测同一台机器连续取链会被判
   * 「检测到您的网络环境存在风险」，单次请求等 800ms 再试通常就能过。
   * 只对 -460 生效，其他错误（404 无版权、Cookie 失效）不重试，避免无谓延迟。
   */
  async function invokeSongUrlWithRiskRetry(id, level, req) {
    const params = cookie.withNeteaseCookie({ id, level }, req);
    try {
      return await withTimeout(netease.invoke('song_url_v1', params), UPSTREAM_TIMEOUT, 'netease/song_url_v1');
    } catch (error) {
      const code = error && error.body && error.body.code;
      if (code !== -460) throw error;
      writeLog('api', 'WARN', `[/song/url/v1] 命中风控 -460，800ms 后重试一次 id=${id}`);
      await new Promise((resolve) => setTimeout(resolve, 800));
      return withTimeout(netease.invoke('song_url_v1', params), UPSTREAM_TIMEOUT, 'netease/song_url_v1');
    }
  }

  app.get('/song/url/v1', async (req, res) => {
    try {
      const id = queryStr(req.query.id);
      const level = queryStr(req.query.level) ?? 'exhigh';
      if (!id) return res.status(400).json({ code: 400, message: '缺少 id 参数' });
      if (!/^\d{4,20}$/.test(id)) return res.status(400).json({ code: 400, message: 'invalid id format' });
      const result = await invokeSongUrlWithRiskRetry(id, level, req);
      sendUpstreamBody(res, result.body);
    } catch (error) {
      writeLog('api', 'ERROR', `[/song/url/v1] ${describeError(error)}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });

  app.get('/personalized', async (req, res) => {
    try {
      const limit = firstQuery(req.query.limit) ?? 10;
      const result = await withTimeout(
        netease.invoke('personalized', cookie.withNeteaseCookie({ limit }, req)),
        UPSTREAM_TIMEOUT, 'netease/personalized'
      );
      sendUpstreamBody(res, result.body);
    } catch (error) {
      writeLog('api', 'ERROR', `[/personalized] ${describeError(error)}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });

  // ==================== 独立平台搜索 ====================

  app.get('/netease/search', async (req, res) => {
    try {
      const keyword = queryStr(req.query.keyword);
      const limit = firstQuery(req.query.limit) ?? 20;
      if (!keyword) return res.status(400).json({ code: 400, message: 'keyword required' });
      // searchNetease 内部已有 SEARCH_TOTAL_BUDGET 总预算（12s 降级为空），此处不再叠加外层超时
      const songs = await sourceHealth.guard('netease', () => searchNetease(keyword, parseInt(limit), req));
      res.json({ code: 200, data: songs });
    } catch (error) {
      writeLog('api', 'ERROR', `[/netease/search] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: [] });
    }
  });

  // ==================== 通用代理路由（限制已知方法） ====================

  // 允许的网易云 API 方法白名单（从 config 读取）
  const ALLOWED_NETEASE_APIS = new Set(config.neteaseApis);

  app.all('/netease/*', async (req, res) => {
    try {
      const apiPath = req.path.replace('/netease/', '');
      const apiName = apiPath.replace(/\//g, '_');
      if (!ALLOWED_NETEASE_APIS.has(apiName)) {
        return res.status(403).json({ code: 403, message: `API ${apiName} not allowed` });
      }
      const params = cookie.withNeteaseCookie(sanitizeNeteaseParams(req.query, req.body), req);
      if (netease.isKnown(apiName)) {
        const result = await withTimeout(
          netease.invoke(apiName, params),
          UPSTREAM_TIMEOUT, `netease/${apiName}`
        );
        sendUpstreamBody(res, result.body);
      } else {
        res.status(404).json({ code: 404, message: `API ${apiName} not found` });
      }
    } catch (error) {
      writeLog('api', 'ERROR', `[/netease/*] ${error.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500 });
    }
  });
}

module.exports = registerNeteaseRoutes;