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

const cookie = require('../src/cookie');
const { scrubCookieValues } = require('../src/routes');

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
test('G3 无令牌本机语义不变：localhost 仍可调 reload/status', async () => {
  delete process.env.MUSICAPI_ADMIN_TOKEN;
  stub(cookie, 'reloadQQCookie', () => true);
  const r1 = await postReload();
  assert.equal(r1.status, 200);
  const r2 = await getStatus();
  assert.equal(r2.status, 200);
});

// ---- G4：脱敏函数 ----
test('G4 scrubCookieValues：掩码全部敏感键值，保留键名与结构', () => {
  const raw = 'uin=123456; qqmusic_key=SECRETVALUE; MUSIC_U=abcdef; qm_keyst=kk; psrf_qqunionid=uu; psrf_qqrefresh_token=rr; ptcz=plain';
  const out = scrubCookieValues(raw);
  assert.equal(out.includes('SECRETVALUE'), false);
  assert.equal(out.includes('abcdef'), false);
  assert.equal(out.includes('123456'), false);
  for (const k of ['uin', 'qqmusic_key', 'MUSIC_U', 'qm_keyst', 'psrf_qqunionid', 'psrf_qqrefresh_token']) {
    assert.match(out, new RegExp(`${k}=\\*\\*\\*`));
  }
  assert.match(out, /ptcz=plain/); // 非敏感键原样保留
  assert.equal(scrubCookieValues('everything ok, no secrets').includes('***'), false);
});

// ---- G4：源码断言（日志不再含原文输出） ----
test('G4 routes.js：无 (stdout+stderr).slice(0,500) 原文落盘，改记 outLen/errLen + scrubbed 尾部', () => {
  const src = fs.readFileSync(path.join(__dirname, '..', 'src', 'routes.js'), 'utf8');
  assert.equal(src.includes('(stdout + stderr).slice(0, 500)'), false);
  assert.match(src, /outLen=\$\{stdout\.length\}, errLen=\$\{stderr\.length\}/);
  assert.match(src, /scrubCookieValues\(stderr\)\.slice\(-200\)/);
});

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
