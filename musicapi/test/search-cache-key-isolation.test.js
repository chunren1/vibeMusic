/**
 * G1 回归测试：/search 缓存键 Cookie 维度隔离 + searchCache 容量。
 *
 * 运行: node --test --test-force-exit test/search-cache-key-isolation.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const path = require('path');
const os = require('os');

// 日志目录隔离：必须在 require 任何 src 模块（logger 加载时确定 LOG_DIR）之前设置，
// 避免本地跑测试写进生产 logs/（与 root 身份运行的服务同名文件冲突 → EACCES）。
process.env.MUSICAPI_LOG_DIR = path.join(os.tmpdir(), 'vibemusic-musicapi-test-logs');

const axios = require('axios');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const crypto = require('crypto');
const cookie = require('../src/cookie');
const { searchCache, urlCache } = require('../src/search');
const { buildSearchCacheKey, searchCookieDim } = require('../src/routes');

const COOKIE_A = 'MUSIC_U=user-a-vip-cookie-value; __csrf=aaa';
const COOKIE_B = 'MUSIC_U=user-b-vip-cookie-value; __csrf=bbb';
const SHARED_COOKIE = 'MUSIC_U=shared-shared-cookie-value; __csrf=sss';
const reqWith = (v) => ({ headers: { 'x-vibe-user-cookie': v } });
// 独立的期望摘要（不复用被测实现，避免自证式断言）
const expectedDim = (ck) => crypto.createHash('sha256').update(ck).digest('hex').slice(0, 16);

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
      search: { data: { body: { song: { list: [] } } } },
    },
  };
}

const restores = [];
function stub(obj, key, impl) {
  const orig = obj[key];
  obj[key] = impl;
  restores.push(() => { obj[key] = orig; });
}

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
afterEach(() => {
  while (restores.length) restores.pop()();
  searchCache.clear();
  urlCache.clear();
});

async function getSearch(keyword, cookieHeader) {
  const headers = cookieHeader ? { 'x-vibe-user-cookie': cookieHeader } : {};
  const r = await fetch(`${base}/search?keyword=${encodeURIComponent(keyword)}`, { headers });
  return { status: r.status, body: await r.json() };
}

test('不同 Cookie → 不同键；同 Cookie → 同键（摘要按 sha256 前 16 位独立校验）', () => {
  const kA1 = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(COOKIE_A));
  const kA2 = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(COOKIE_A));
  const kB = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(COOKIE_B));
  assert.equal(kA1, kA2);
  assert.notEqual(kA1, kB);
  assert.ok(kA1.endsWith(':' + expectedDim(COOKIE_A)), `键应含 COOKIE_A 摘要: ${kA1}`);
  assert.ok(kB.endsWith(':' + expectedDim(COOKIE_B)), `键应含 COOKIE_B 摘要: ${kB}`);
});

test('匿名桶：缺失/空/undefined header 键相同且以 :anon 结尾（与共享 Cookie 环境无关）', () => {
  // 隔离环境变量：本仓库 .env 可能已配置 MUSIC_NETEASE_COOKIE，
  // 不 stub 时该用例会在带共享 Cookie 的机器上假红（共享 Cookie 会覆盖 anon 语义）。
  stub(cookie, 'resolveNeteaseCookie', () => '');
  const kMissing = buildSearchCacheKey('周杰伦', 30, undefined, { headers: {} });
  const kEmpty = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(''));
  const kUndef = buildSearchCacheKey('周杰伦', 30, undefined, undefined);
  assert.equal(kMissing, kEmpty);
  assert.equal(kMissing, kUndef);
  assert.ok(kMissing.endsWith(':anon'), `匿名键应落 anon 桶: ${kMissing}`);
});

test('共享 Cookie 桶：无 per-request header 时统一落共享摘要桶（非 anon）', () => {
  stub(cookie, 'resolveNeteaseCookie', () => SHARED_COOKIE);
  const d1 = searchCookieDim({ headers: {} });
  const d2 = searchCookieDim(undefined);
  assert.equal(d1, d2);
  assert.equal(d1, expectedDim(SHARED_COOKIE));
});

test('键不含 Cookie 原文；BYOC 维度为 16 位 hex', () => {
  const dimA = searchCookieDim(reqWith(COOKIE_A));
  assert.match(dimA, /^[0-9a-f]{16}$/);
  assert.equal(dimA, expectedDim(COOKIE_A));
  stub(cookie, 'resolveNeteaseCookie', () => '');
  assert.equal(searchCookieDim(undefined), 'anon');
  assert.equal(searchCookieDim({ headers: {} }), 'anon');
  const key = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(COOKIE_A));
  assert.equal(key.includes(COOKIE_A), false);
  assert.equal(key.includes('MUSIC_U'), false);
});

test('searchCache 容量 >= 500（BYOC 键基数增长不挤出匿名热门词）', () => {
  assert.ok(searchCache.max >= 500, `searchCache.max=${searchCache.max}，期望 >= 500`);
});

test('端到端：BYOC 与匿名同词隔离；匿名二次命中保留', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => neteasePayload());
  stub(NeteaseCloudMusicApi, 'search', async () => neteasePayload());
  stub(axios, 'get', async () => qqPayload());

  const kwAnon = 'g1-anon-hit-rate';
  const a1 = await getSearch(kwAnon);
  assert.equal(a1.body.message, 'success');
  const a2 = await getSearch(kwAnon);
  assert.equal(a2.body.message, 'success (cached)');

  const kw = 'g1-byoc-isolation';
  const byocA1 = await getSearch(kw, COOKIE_A);
  assert.equal(byocA1.status, 200);
  assert.equal(byocA1.body.message, 'success');

  const anon = await getSearch(kw);
  assert.equal(anon.status, 200);
  assert.equal(anon.body.message, 'success');

  const byocB = await getSearch(kw, COOKIE_B);
  assert.equal(byocB.status, 200);
  assert.equal(byocB.body.message, 'success');

  const byocA2 = await getSearch(kw, COOKIE_A);
  assert.equal(byocA2.body.message, 'success (cached)');
});
