/**
 * 网关 Top-10 修复回归测试（review-report-2026-09-14 第二章）
 *
 * 运行: node --test --test-force-exit test/gateway-top10.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 * 覆盖：#1 refresh 鉴权、#2 metrics unmatched 标签、#3 搜索总预算/直连路由超时、
 *       #4 prefer 缓存键隔离、#6 500 脱敏、#7 null-url 不缓存、#9 重参归一化/400。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const { searchCache, urlCache, searchNetease, SEARCH_TOTAL_BUDGET } = require('../src/search');
const { buildSearchCacheKey } = require('../src/routes');
const { register, metricsMiddleware } = require('../src/metrics');
const access = require('../src/access');

function neteasePayload() {
  return {
    body: {
      code: 200,
      result: {
        songs: [{
          id: 386038, name: '晴天', ar: [{ name: '周杰伦' }],
          al: { name: '叶惠美', picUrl: 'https://p2.music.126.net/xxx.jpg' },
          dt: 240000, fee: 0, pop: 1000000,
        }],
      },
    },
  };
}

function qqPayload() {
  return {
    data: {
      search: {
        data: {
          body: {
            song: {
              list: [{
                mid: '004abcXYZ1', name: '晴天', singer: [{ name: '周杰伦' }],
                album: { mid: '002eFUFQ2XYZOP', name: '叶惠美' },
                interval: 240, pay: { pay_play: 0 },
              }],
            },
          },
        },
      },
    },
  };
}

const restores = [];
function stub(obj, key, impl) {
  const orig = obj[key];
  obj[key] = impl;
  restores.push(() => { obj[key] = orig; });
}
afterEach(() => {
  while (restores.length) restores.pop()();
  searchCache.clear();
  urlCache.clear();
});

let base = '';
let server = null;
before(async () => {
  const express = require('express');
  const registerRoutes = require('../src/routes');
  const app = express();
  registerRoutes(app);
  server = await new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => new Promise((resolve) => server.close(resolve)));
beforeEach(() => {
  searchCache.clear();
  urlCache.clear();
});

async function get(path) {
  const r = await fetch(`${base}${path}`);
  return { status: r.status, body: await r.json() };
}

// ---- #4 prefer 进缓存键：不同偏好互不污染 ----
test('#4 prefer 缓存键隔离：netease/qq 各自独立，相同偏好二次命中', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => neteasePayload());
  stub(NeteaseCloudMusicApi, 'search', async () => neteasePayload());
  stub(axios, 'get', async () => qqPayload());
  const kw = 'top10-prefer-isolation';

  const r1 = await get(`/search?keyword=${kw}&prefer=netease`);
  assert.equal(r1.status, 200);
  assert.equal(r1.body.message, 'success');

  const r2 = await get(`/search?keyword=${kw}&prefer=qq`);
  assert.equal(r2.status, 200);
  assert.equal(r2.body.message, 'success'); // 未命中 чужого 缓存

  const r3 = await get(`/search?keyword=${kw}&prefer=qq`);
  assert.equal(r3.body.message, 'success (cached)');

  // G1：键尾追加 Cookie 维度（本环境无 header → anon 共享桶）
  assert.notEqual(searchCache.get(buildSearchCacheKey(kw, 30, 'netease', undefined)), undefined);
  assert.notEqual(searchCache.get(buildSearchCacheKey(kw, 30, 'qq', undefined)), undefined);
  assert.equal(searchCache.get(`search:${kw}:30`), undefined); // 旧无后缀键不再产生
});

// ---- #9 重参归一化：数组取首个，不再 500；非法归一化值 400 ----
test('#9 重参取首个不再 500：keyword=a&keyword=b 按首个正常搜索', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => neteasePayload());
  stub(NeteaseCloudMusicApi, 'search', async () => neteasePayload());
  stub(axios, 'get', async () => qqPayload());
  const r = await get('/search?keyword=a&keyword=b');
  assert.equal(r.status, 200);
  assert.equal(r.body.code, 200);
});

test('#9 非法归一化值 400：对象式参数与非法 id', async () => {
  const r1 = await get('/search?keyword[x]=1');
  assert.equal(r1.status, 400);

  const r2 = await get('/song/url/qq?id=ab&id=cd');
  assert.equal(r2.status, 400);

  const r3 = await get('/search');
  assert.equal(r3.status, 400);
});

// ---- #7 url:null 不缓存：失败可重试，第二次仍回源 ----
test('#7 null-url 不缓存：两次请求都回源，缓存无条目', async () => {
  const MID = '004abcXYZ1';
  let upstreamCalls = 0;
  stub(axios, 'get', async () => {
    upstreamCalls++;
    return { data: { req_0: { data: { midurlinfo: [{ filename: `M800${MID}.mp3`, purl: '' }], sip: [] } } } };
  });

  const r1 = await get(`/song/url/qq?id=${MID}`);
  assert.equal(r1.status, 200);
  assert.deepEqual(r1.body, { code: 200, data: [{ id: MID, url: null }] });

  const r2 = await get(`/song/url/qq?id=${MID}`);
  assert.equal(r2.status, 200);
  assert.equal(r2.body.data[0].url, null);

  assert.equal(urlCache.get(`qq_url:${MID}`), undefined);
  assert.equal(upstreamCalls, 2); // 未缓存 → 第二次仍回源
});

// ---- #6 500 脱敏：上游错误文本不回传 ----
test('#6 500 统一话术：lyric 上游抛错只回通用文案', async () => {
  stub(NeteaseCloudMusicApi, 'lyric', async () => { throw new Error('connect ECONNREFUSED 10.0.0.99:8080'); });
  const r = await get('/lyric?id=12345678');
  assert.equal(r.status, 500);
  assert.equal(r.body.message, '服务繁忙，请稍后重试');
  assert.equal(String(JSON.stringify(r.body)).includes('ECONNREFUSED'), false);
});

// ---- #2 metrics：未匹配路径归 unmatched，不膨胀基数 ----
test('#2 metrics 未匹配归一：随机 404 路径只记 unmatched', async () => {
  const express = require('express');
  const app = express();
  app.use(metricsMiddleware);
  app.get('/ping', (req, res) => res.json({ ok: 1 }));
  const s = await new Promise((resolve) => {
    const sv = app.listen(0, () => resolve(sv));
  });
  try {
    const port = s.address().port;
    const nonce = `nope-${Date.now()}-xyz`;
    await fetch(`http://127.0.0.1:${port}/${nonce}`); // 404
    await fetch(`http://127.0.0.1:${port}/ping`); // 正常路由
    const text = await register.metrics();
    assert.match(text, /path="unmatched"/);
    assert.equal(text.includes(nonce), false);
    assert.match(text, /path="\/ping"/);
  } finally {
    await new Promise((resolve) => s.close(resolve));
  }
});

// ---- #1 访问控制矩阵（纯函数，不 spawn 浏览器） ----
test('#1 refresh/metrics 鉴权矩阵：令牌 > 本机/私网 > 公网拒绝', () => {
  const prev = process.env.MUSICAPI_ADMIN_TOKEN;
  const req = (ip, query = {}, headers = {}) => ({ ip, query, headers, socket: { remoteAddress: ip } });
  try {
    delete process.env.MUSICAPI_ADMIN_TOKEN;
    assert.equal(access.canRefreshCookie(req('127.0.0.1')), true); // 未配令牌本机可调
    assert.equal(access.canRefreshCookie(req('172.19.0.2')), true); // 容器网段：后端经 compose 网络调 rotate
    assert.equal(access.canRefreshCookie(req('10.0.0.7')), true);
    assert.equal(access.canRefreshCookie(req('203.0.113.9')), false); // 公网拒绝
    assert.equal(access.canReadMetrics(req('127.0.0.1')), true);
    assert.equal(access.canReadMetrics(req('172.19.0.2')), true); // 容器网段（Prometheus 抓取）
    assert.equal(access.canReadMetrics(req('203.0.113.9')), false);

    process.env.MUSICAPI_ADMIN_TOKEN = 's3cret';
    assert.equal(access.canRefreshCookie(req('203.0.113.9', { token: 's3cret' })), true);
    assert.equal(access.canRefreshCookie(req('203.0.113.9', {}, { 'x-admin-token': 's3cret' })), true);
    assert.equal(access.canRefreshCookie(req('127.0.0.1')), false); // 配了令牌就必须对上
    assert.equal(access.canReadMetrics(req('203.0.113.9', {}, { authorization: 'Bearer s3cret' })), true);
  } finally {
    if (prev === undefined) delete process.env.MUSICAPI_ADMIN_TOKEN;
    else process.env.MUSICAPI_ADMIN_TOKEN = prev;
  }
});

test('#1 refresh 端点：公网无令牌直接 403（不启动子进程）', async () => {
  const prev = process.env.MUSICAPI_ADMIN_TOKEN;
  process.env.MUSICAPI_ADMIN_TOKEN = 's3cret';
  try {
    const r = await get('/refresh-qq-cookie');
    assert.equal(r.status, 403);
    assert.equal(r.body.code, 403);
  } finally {
    if (prev === undefined) delete process.env.MUSICAPI_ADMIN_TOKEN;
    else process.env.MUSICAPI_ADMIN_TOKEN = prev;
  }
});

// ---- #3 搜索总预算：上游全挂起时 ~12s 早退降级 ----
test('#3 搜索总预算：策略链 12s 早退（非 40s），降级为空', { timeout: 30000 }, async () => {
  assert.equal(SEARCH_TOTAL_BUDGET, 12000);
  stub(NeteaseCloudMusicApi, 'cloudsearch', () => new Promise(() => {})); // 永挂起
  stub(NeteaseCloudMusicApi, 'search', () => new Promise(() => {}));
  const t0 = Date.now();
  const out = await searchNetease('budget-probe', 5);
  const dur = Date.now() - t0;
  assert.deepEqual(out, []);
  assert.ok(dur >= 11000, `预算过早返回: ${dur}ms`);
  assert.ok(dur < 25000, `总预算未生效，耗时 ${dur}ms（接近 40s 说明仍在串行全等）`);
});

// ---- #3 直连路由超时：lyric 上游挂起时 10s 熔断为 500 通用文案 ----
test('#3 直连路由超时：lyric 挂起 10s 熔断，不长期占连接', { timeout: 30000 }, async () => {
  stub(NeteaseCloudMusicApi, 'lyric', () => new Promise(() => {})); // 永挂起
  const t0 = Date.now();
  const r = await get('/lyric?id=12345678');
  const dur = Date.now() - t0;
  assert.equal(r.status, 500);
  assert.equal(r.body.message, '服务繁忙，请稍后重试');
  assert.ok(dur >= 9000, `过早返回: ${dur}ms`);
  assert.ok(dur < 20000, `超时保护未生效: ${dur}ms`);
});

// ---------- 限流计数键（2026-10-22 加固：容器拓扑下单 IP 坍缩 → 全局吞吐上限）----------
// 背景：后端容器经 172.x 调网关、trust proxy=false → 所有请求共享 req.ip，
// 100/min 变成全站上限。键优先取可信身份头，其次 trace 哈希，最后才回退 IP。

const express = require('express');
const rateLimit = require('express-rate-limit');
const { rateLimitKey, shouldSkip } = require('../src/rate-limiters');

test('限流键：同一 IP + 不同 X-User-Id 分别计数，不再坍缩为单键', () => {
  const reqA = { ip: '172.18.0.5', headers: { 'x-user-id': '1001' } };
  const reqB = { ip: '172.18.0.5', headers: { 'x-user-id': '1002' } };

  assert.equal(rateLimitKey(reqA), 'user:1001');
  assert.equal(rateLimitKey(reqB), 'user:1002');
  assert.notEqual(rateLimitKey(reqA), rateLimitKey(reqB));
});

test('限流键：无身份头时同一 IP 合并为同一键', () => {
  const a = { ip: '172.18.0.5', headers: {} };
  const b = { ip: '172.18.0.5', headers: { 'x-user-id': '' } };
  const c = { ip: '172.18.0.6', headers: {} };

  assert.equal(rateLimitKey(a), rateLimitKey(b));
  assert.match(rateLimitKey(a), /^ip:/);
  assert.notEqual(rateLimitKey(a), rateLimitKey(c));
});

test('限流键：X-Trace-Id 走稳定哈希（同值同键 / 异值异键 / 键内不落原文）', () => {
  const base = { ip: '172.18.0.5', headers: {} };
  const t1 = rateLimitKey({ ...base, headers: { 'x-trace-id': 'trace-abc-123' } });
  const t2 = rateLimitKey({ ...base, headers: { 'x-trace-id': 'trace-abc-123' } });
  const t3 = rateLimitKey({ ...base, headers: { 'x-trace-id': 'trace-abc-456' } });

  assert.equal(t1, t2);
  assert.notEqual(t1, t3);
  assert.match(t1, /^trace:[0-9a-f]{16}$/);
  assert.equal(t1.includes('trace-abc-123'), false, '原始 trace 值不应进入 store 键');
});

test('限流键：公网伪造 X-User-Id 不采信，仍按 IP 计数（防伪造头绕过限流）', () => {
  const spoofA = { ip: '203.0.113.9', headers: { 'x-user-id': 'a' } };
  const spoofB = { ip: '203.0.113.9', headers: { 'x-user-id': 'b' } };

  assert.equal(rateLimitKey(spoofA), rateLimitKey(spoofB));
  assert.match(rateLimitKey(spoofA), /^ip:/);
});

test('限流放行端点：/health、/metrics、/reload 不计额度（含查询串/尾斜杠）', () => {
  for (const url of ['/health', '/health?probe=1', '/metrics/', '/reload?token=t']) {
    assert.equal(shouldSkip({ originalUrl: url }), true, url);
  }
  for (const url of ['/', '/search', '/healthz', '/health/deep']) {
    assert.equal(shouldSkip({ originalUrl: url }), false, url);
  }
});

test('集成：同 IP 两个身份独立配额，无身份头合并计数，/health 不限流', async () => {
  const app = express();
  app.use(rateLimit({
    windowMs: 60 * 1000,
    max: 2,
    standardHeaders: true,
    legacyHeaders: false,
    skip: shouldSkip,
    keyGenerator: rateLimitKey,
    message: { code: 429 },
  }));
  app.get('/ping', (req, res) => res.json({ code: 200 }));
  app.get('/health', (req, res) => res.json({ code: 200 }));

  const server = await new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  const base = `http://127.0.0.1:${server.address().port}`;

  const hit = async (url, headers = {}) => {
    const r = await fetch(base + url, { headers });
    return r.status;
  };

  try {
    // 用户 1001：2 次正常，第 3 次 429
    assert.equal(await hit('/ping', { 'x-user-id': '1001' }), 200);
    assert.equal(await hit('/ping', { 'x-user-id': '1001' }), 200);
    assert.equal(await hit('/ping', { 'x-user-id': '1001' }), 429);
    // 另一身份同 IP：独立配额，不被打满
    assert.equal(await hit('/ping', { 'x-user-id': '1002' }), 200);
    // 无身份头：同 IP 合并计数（独立于身份桶）
    assert.equal(await hit('/ping'), 200);
    assert.equal(await hit('/ping'), 200);
    assert.equal(await hit('/ping'), 429);
    // 放行端点：配额打满后仍可用
    for (let i = 0; i < 3; i++) assert.equal(await hit('/health'), 200);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
