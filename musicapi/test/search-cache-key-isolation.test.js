/**
 * G1 回归测试：/search 缓存键 Cookie 维度隔离 + searchCache 容量。
 *
 * 运行: node --test --test-force-exit test/search-cache-key-isolation.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const { searchCache, urlCache } = require('../src/search');
const { buildSearchCacheKey, searchCookieDim } = require('../src/routes');

const COOKIE_A = 'MUSIC_U=user-a-vip-cookie-value; __csrf=aaa';
const COOKIE_B = 'MUSIC_U=user-b-vip-cookie-value; __csrf=bbb';
const reqWith = (v) => ({ headers: { 'x-vibe-user-cookie': v } });

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

test('不同 Cookie → 不同键；同 Cookie → 同键', () => {
  const kA1 = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(COOKIE_A));
  const kA2 = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(COOKIE_A));
  const kB = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(COOKIE_B));
  assert.equal(kA1, kA2);
  assert.notEqual(kA1, kB);
});

test('匿名共享桶：缺失/空/undefined header 键相同且以 :anon 结尾', () => {
  const kMissing = buildSearchCacheKey('周杰伦', 30, undefined, { headers: {} });
  const kEmpty = buildSearchCacheKey('周杰伦', 30, undefined, reqWith(''));
  const kUndef = buildSearchCacheKey('周杰伦', 30, undefined, undefined);
  assert.equal(kMissing, kEmpty);
  assert.equal(kMissing, kUndef);
  assert.ok(kMissing.endsWith(':anon'), `匿名键应落 anon 桶: ${kMissing}`);
});

test('键不含 Cookie 原文；BYOC 维度为 16 位 hex', () => {
  const dimA = searchCookieDim(reqWith(COOKIE_A));
  assert.match(dimA, /^[0-9a-f]{16}$/);
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
