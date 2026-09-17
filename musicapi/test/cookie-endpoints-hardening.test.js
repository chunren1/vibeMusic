/**
 * P2 网关加固回归测试（code-review-round3-2026-09-16：G2/G3/G4）
 *
 * 运行: node --test --test-force-exit test/cookie-endpoints-hardening.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 * 覆盖：G3 /cookie-status 鉴权门禁（403 匿名 / 200 带令牌 / 无令牌本机行为不变）、
 *       G4 scrubCookieValues 脱敏 + 日志无原文断言 + 提取脚本无明文打印、
 *       G2 metricsMiddleware 单次挂载。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const os = require('os');

// 日志目录隔离：必须在 require 任何 src 模块（logger 在加载时确定 LOG_DIR）之前设置，
// 否则本地跑测试会写进生产 logs/，且与 root 身份运行的服务产生 EACCES 冲突。
process.env.MUSICAPI_LOG_DIR = path.join(os.tmpdir(), 'vibemusic-musicapi-test-logs');
const LOG_DIR = process.env.MUSICAPI_LOG_DIR;

const cookie = require('../src/cookie');

// routes.js 在 require 时解构 child_process.spawn，测试需先包装再加载 routes，
// 以便 E2E 用例注入假子进程验证"失败路径日志脱敏"（不依赖 Playwright/真实浏览器）。
const cp = require('child_process');
const realSpawn = cp.spawn;
let spawnImpl = null;
cp.spawn = function patchedSpawn(...args) {
  return spawnImpl ? spawnImpl(...args) : realSpawn.apply(cp, args);
};

const { scrubCookieValues, SENSITIVE_COOKIE_KEYS } = require('../src/routes');

const restores = [];
function stub(obj, key, impl) {
  const orig = obj[key];
  obj[key] = impl;
  restores.push(() => { obj[key] = orig; });
}
afterEach(() => {
  while (restores.length) restores.pop()();
});

let base = '';
let server = null;
before(async () => {
  const express = require('express');
  const registerRoutes = require('../src/routes');
  const app = express();
  app.use(express.json());
  registerRoutes(app);
  server = await new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => new Promise((resolve) => server.close(resolve)));

const prevToken = { value: process.env.MUSICAPI_ADMIN_TOKEN, defined: 'MUSICAPI_ADMIN_TOKEN' in process.env };
beforeEach(() => { process.env.MUSICAPI_ADMIN_TOKEN = 't-secret'; });
afterEach(() => {
  if (prevToken.defined) process.env.MUSICAPI_ADMIN_TOKEN = prevToken.value;
  else delete process.env.MUSICAPI_ADMIN_TOKEN;
});

async function getStatus(query = '') {
  const r = await fetch(`${base}/cookie-status${query}`);
  return { status: r.status, body: await r.json() };
}

// ---- G3：未授权一律 403（与 /refresh-qq-cookie 同形） ----
test('G3 /cookie-status 匿名 403：{code,message,data} 同形', async () => {
  const r = await getStatus();
  assert.equal(r.status, 403);
  assert.deepEqual(r.body, { code: 403, message: 'Forbidden', data: null });
});

// ---- G3：带令牌行为不变（形状保持） ----
test('G3 /cookie-status 带令牌 200：原形状 {code,data,timestamp} 不变', async () => {
  const r = await getStatus('?token=t-secret');
  assert.equal(r.status, 200);
  assert.equal(r.body.code, 200);
  assert.equal(typeof r.body.data.qqCookieKeys, 'number');
  assert.equal(typeof r.body.timestamp, 'string');
});

// ---- G3：canRefreshCookie 语义保持（无令牌时本机仍可调） ----
// 注：/cookie/reload 端点已随死代码清理删除（1332182），此处只验证存活的 /cookie-status。
test('G3 无令牌本机语义不变：localhost 仍可调 cookie-status', async () => {
  delete process.env.MUSICAPI_ADMIN_TOKEN;
  const r = await getStatus();
  assert.equal(r.status, 200);
  assert.equal(r.body.code, 200);
});

// ---- G4：脱敏函数（键清单硬编码 + 与生产导出一致性；形态含 k=v 与 JSON） ----
const KNOWN_SENSITIVE_KEYS = [
  'uin', 'qqmusic_key', 'MUSIC_U', 'qm_keyst',
  'psrf_qqunionid', 'psrf_qqrefresh_token', 'psrf_qqaccess_token', 'psrf_qqopenid',
];

test('G4 生产键清单覆盖全部已知敏感键（防漏字段回归）', () => {
  for (const k of KNOWN_SENSITIVE_KEYS) {
    assert.ok(SENSITIVE_COOKIE_KEYS.has(k), `SENSITIVE_COOKIE_KEYS 缺少 ${k}`);
  }
});

test('G4 scrubCookieValues：掩码全部敏感键值，保留键名与结构', () => {
  const raw = 'uin=123456; qqmusic_key=SECRETVALUE; MUSIC_U=abcdef; qm_keyst=kk; psrf_qqunionid=uu; psrf_qqrefresh_token=rr; psrf_qqaccess_token=aa; psrf_qqopenid=oo; ptcz=plain';
  const out = scrubCookieValues(raw);
  for (const secret of ['SECRETVALUE', 'abcdef', '123456']) {
    assert.equal(out.includes(secret), false, `原文 ${secret} 不应出现`);
  }
  for (const k of KNOWN_SENSITIVE_KEYS) {
    assert.match(out, new RegExp(`${k}=\\*\\*\\*`));
  }
  assert.match(out, /ptcz=plain/); // 非敏感键原样保留
  assert.equal(scrubCookieValues('everything ok, no secrets').includes('***'), false);
});

test('G4 scrubCookieValues：JSON 形态（MUSIC_QQ_COOKIE= 后接 JSON）同样脱敏', () => {
  const raw = 'MUSIC_QQ_COOKIE={"uin":"123456","qqmusic_key":"SECRETVALUE","ptcz":"plain"}';
  const out = scrubCookieValues(raw);
  assert.equal(out.includes('123456'), false);
  assert.equal(out.includes('SECRETVALUE'), false);
  assert.match(out, /"uin":"\*\*\*"/);
  assert.match(out, /"qqmusic_key":"\*\*\*"/);
  assert.match(out, /"ptcz":"plain"/);
});

test('G4 scrubCookieValues：带 = 填充的 Cookie 值不残留尾部', () => {
  const out = scrubCookieValues('MUSIC_U=abc==def;');
  assert.equal(out.includes('abc'), false);
  assert.match(out, /MUSIC_U=\*\*\*/);
});

// ---- G4：行为断言（真实请求 + 假子进程 + 读日志文件，替代源码文本断言） ----
test('G4 行为：/refresh-qq-cookie 失败时日志已脱敏（无 Cookie 原文）', async () => {
  const { EventEmitter } = require('node:events');
  const { PassThrough } = require('node:stream');
  const child = new EventEmitter();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  spawnImpl = () => child;

  try {
    const p = fetch(`${base}/refresh-qq-cookie?token=t-secret`);
    await new Promise((r) => setTimeout(r, 30)); // 等路由注册 stderr 监听
    child.stderr.write('MUSIC_U=SUPERSECRETVALUE; qqmusic_key=ANOTHERSECRET');
    child.emit('close', 1);
    const r = await p;
    assert.equal(r.status, 500);

    const logFile = path.join(LOG_DIR, `cookie-monitor.${new Date().toISOString().slice(0, 10)}.log`);
    let content = '';
    for (let i = 0; i < 20; i++) { // 日志为异步 appendFile，轮询至多 ~1s
      content = fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8') : '';
      if (content.includes('refresh-qq-cookie')) break;
      await new Promise((r2) => setTimeout(r2, 50));
    }
    assert.ok(content.includes('refresh-qq-cookie'), '日志应包含本次刷新记录');
    assert.equal(content.includes('SUPERSECRETVALUE'), false);
    assert.equal(content.includes('ANOTHERSECRET'), false);
    assert.match(content, /MUSIC_U=\*\*\*/);
  } finally {
    spawnImpl = null;
  }
});

// ---- G4：源码断言保留（提取脚本依赖 Playwright，无法行为化，仅作静态护栏） ----
test('G4 提取脚本：不再打印 Cookie 明文前缀，只记长度', () => {
  const src = fs.readFileSync(path.join(__dirname, '..', '..', 'scripts', 'get_qq_cookie.mjs'), 'utf8');
  assert.equal(src.includes('v.substring(0, 30)'), false);
  assert.match(src, /\*{3} \(len=\$\{v\.length\}\)/);
});

// ---- G2：单次挂载 ----
test('G2 server.js：metricsMiddleware 仅挂载一次（保留 429-进-指标的前置位置），require 唯一', () => {
  const src = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert.equal((src.match(/app\.use\(metricsMiddleware\)/g) || []).length, 1);
  assert.equal((src.match(/require\(['"]\.\/src\/metrics['"]\)/g) || []).length, 1);
  const useIdx = src.indexOf('app.use(metricsMiddleware)');
  const limiterIdx = src.indexOf('app.use(globalLimiter)');
  assert.ok(useIdx !== -1 && limiterIdx !== -1 && useIdx < limiterIdx, 'metrics 必须在 globalLimiter 之前（429 进指标意图保留）');
});
