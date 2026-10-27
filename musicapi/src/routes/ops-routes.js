// ==================== 运维路由（Cookie 恢复 / 状态 / 源健康 / 网易云快切）====================
//
// 2026-10-27 网关重构 pass 3：从 routes.js 原样搬出，**行为零变更**。
// 这些端点**不是**给 App 用的：全部走 canRefreshCookie 门禁（管理令牌或本机调用），
// 响应里只允许出现计数/状态/槽位，绝不含 Cookie 原文（见 helpers.scrubCookieValues）。
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const config = require('../config-loader');
const cookie = require('../cookie');
const sourceHealth = require('../source-health');
const { writeLog } = require('../logger');
const { canRefreshCookie, clientIp } = require('../access');
const { cookieStatusGauge } = require('../metrics');
const { GENERIC_500, firstQuery, scrubCookieValues } = require('./helpers');

function registerOpsRoutes(app) {
  // ==================== Cookie 手动恢复端点 ====================

  // GET /refresh-qq-cookie — 手动触发浏览器提取 Cookie。
  // 访问控制：管理令牌（?token=/x-admin-token/Bearer）或未配令牌时的本机调用；其余 403。
  // 只返回成功/失败 + 退出码，脚本明细只进服务端日志（不再回传 stdout，防 cookie 前缀外泄）。
  // 镜像内无 scripts/ 时明确 501（Dockerfile 只 COPY server.js/src，宿主机挂载场景才可用）。
  app.get('/refresh-qq-cookie', async (req, res) => {
    if (!canRefreshCookie(req)) {
      writeLog('access', 'WARN', `[/refresh-qq-cookie] 拒绝非授权访问: ${clientIp(req)}`);
      return res.status(403).json({ code: 403, message: 'Forbidden', data: null });
    }
    const scriptPath = path.resolve(__dirname, '..', '..', '..', 'scripts', 'get_qq_cookie.mjs');
    if (!fs.existsSync(scriptPath)) {
      return res.status(501).json({ code: 501, message: 'Cookie 提取脚本在当前镜像中不可用，仅宿主机部署支持', data: null });
    }

    const child = spawn('node', [scriptPath], {
      cwd: path.resolve(__dirname, '..', '..', '..'),
      timeout: 120000,
      env: process.env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d.toString(); });
    child.stderr.on('data', (d) => { stderr += d.toString(); });

    child.on('close', async (code) => {
      writeLog('cookie', code === 0 ? 'INFO' : 'ERROR',
        `[/refresh-qq-cookie] 退出码=${code}, outLen=${stdout.length}, errLen=${stderr.length}` +
        (code === 0 ? '' : `, 尾部: ${scrubCookieValues(stderr).slice(-200)}`));
      if (code === 0 && cookie.reloadQQCookie()) {
        cookie.cookieStatus.qq = true;
        cookieStatusGauge.set({ platform: 'qq' }, 1);
        return res.json({ code: 200, message: 'Cookie 提取成功，已自动加载', data: { exitCode: code } });
      }
      return res.status(500).json({ code: 500, message: GENERIC_500, data: { exitCode: code } });
    });

    child.on('error', (err) => {
      writeLog('cookie', 'ERROR', `[/refresh-qq-cookie] 无法启动浏览器: ${err.message}`);
      res.status(500).json({ code: 500, message: GENERIC_500, data: null });
    });
  });

  // Cookie 状态查询端点（同门禁：响应含 qqCookieKeys 计数，匿名不可见）
  app.get('/cookie-status', (req, res) => {
    if (!canRefreshCookie(req)) {
      writeLog('access', 'WARN', `[/cookie-status] 拒绝非授权访问: ${clientIp(req)}`);
      return res.status(403).json({ code: 403, message: 'Forbidden', data: null });
    }
    const qqKeys = Object.keys(config.qq).length;
    res.json({
      code: 200,
      data: { ...cookie.cookieStatus, qqCookieKeys: qqKeys },
      tip: cookie.cookieStatus.qq ? null : 'Cookie 过期，请运行: node scripts/get_qq_cookie.mjs',
      timestamp: new Date().toISOString(),
    });
  });

  /**
   * 每源健康度 + 熔断状态（与 /cookie-status 同一鉴权口径：只给运维/内部调用）。
   * 为什么需要它：源挂了以前只能靠「用户说搜不到歌」或翻 degradation 日志发现；
   * 熔断打开后搜索会静默少一个源，没有这个端点就完全不可见。
   */
  app.get('/source-health', (req, res) => {
    if (!canRefreshCookie(req)) {
      writeLog('access', 'WARN', `[/source-health] 拒绝非授权访问: ${clientIp(req)}`);
      return res.status(403).json({ code: 403, message: 'Forbidden', data: null });
    }
    res.json({
      code: 200,
      data: sourceHealth.snapshot(),
      timestamp: new Date().toISOString(),
    });
  });

  // 网易共享 Cookie 按需快切（内网调用：后端共享链路 need-login 时触发）。
  // 同门禁（令牌或无令牌本机）；先复探当前槽位，健康不切换；响应只含 switched/active，绝不含凭证。
  // force=true（查询串或 JSON body）走"能力感知"强制快切：重探两槽位的登录 + 播放能力，
  // 供 VIP 掉档（登录仍有效、仅播放权限丢失）时换槽；不带 force 时行为与旧版完全一致。
  app.post('/internal/netease-rotate', async (req, res) => {
    if (!canRefreshCookie(req)) {
      writeLog('access', 'WARN', `[/internal/netease-rotate] 拒绝非授权访问: ${clientIp(req)}`);
      return res.status(403).json({ code: 403, message: 'Forbidden', data: null });
    }
    try {
      const force = /^(1|true|yes)$/i.test(String(
        firstQuery(req.query.force) ?? (req.body && req.body.force) ?? ''
      ));
      const out = await cookie.rotateNeteaseActive({ force });
      return res.json({ code: 200, message: 'ok', data: out });
    } catch (e) {
      writeLog('cookie', 'ERROR', `[/internal/netease-rotate] 失败: ${e.message}`);
      return res.status(500).json({ code: 500, message: GENERIC_500, data: null });
    }
  });
}

module.exports = registerOpsRoutes;
