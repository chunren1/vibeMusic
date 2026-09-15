/**
 * /search 回归测试：去重循环引用 + 缓存毒化 + vkey 硬过期 + 空结果不缓存
 *
 * 运行: node --test --test-force-exit test/search-cache-regression.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const { searchCache, urlCache } = require('../src/search');
const { stripSearchInternals, buildSearchCacheKey } = require('../src/routes');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- 上游桩数据：网易与 QQ 返回同一首歌（同名/同歌手/同专辑/等时长） ----
const DUP_NAME = '晴天';
const DUP_ARTIST = '周杰伦';
const DUP_ALBUM = '叶惠美';

function neteasePayload() {
  return {
    body: {
      code: 200,
      result: {
        songs: [{
          id: 386038,
          name: DUP_NAME,
          ar: [{ name: DUP_ARTIST }],
          al: { name: DUP_ALBUM, picUrl: 'https://p2.music.126.net/xxx.jpg' },
          dt: 240000,
          fee: 0,
          pop: 1000000,
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
                mid: '004abcXYZ1',
                name: DUP_NAME,
                singer: [{ name: DUP_ARTIST }],
                album: { mid: '002eFUFQ2XYZOP', name: DUP_ALBUM },
                interval: 240, // 240s → 240000ms，与网易等时长，触发去重等时长分支
                pay: { pay_play: 0 },
              }],
            },
          },
        },
      },
    },
  };
}

function stubUpstreamSuccess() {
  const origCloud = NeteaseCloudMusicApi.cloudsearch;
  const origSearch = NeteaseCloudMusicApi.search;
  const origGet = axios.get;
  NeteaseCloudMusicApi.cloudsearch = async () => neteasePayload();
  NeteaseCloudMusicApi.search = async () => neteasePayload();
  axios.get = async () => qqPayload();
  return () => {
    NeteaseCloudMusicApi.cloudsearch = origCloud;
    NeteaseCloudMusicApi.search = origSearch;
    axios.get = origGet;
  };
}

function stubUpstreamFailure() {
  const origCloud = NeteaseCloudMusicApi.cloudsearch;
  const origSearch = NeteaseCloudMusicApi.search;
  const origGet = axios.get;
  NeteaseCloudMusicApi.cloudsearch = async () => { throw new Error('upstream down'); };
  NeteaseCloudMusicApi.search = async () => { throw new Error('upstream down'); };
  axios.get = async () => { throw new Error('upstream down'); };
  return () => {
    NeteaseCloudMusicApi.cloudsearch = origCloud;
    NeteaseCloudMusicApi.search = origSearch;
    axios.get = origGet;
  };
}

// ---- 本文件内起一个真实 /search 路由（随机端口，不与 :3000 冲突） ----
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

let restoreUpstream = null;
afterEach(() => {
  if (restoreUpstream) { restoreUpstream(); restoreUpstream = null; }
  searchCache.clear();
  urlCache.clear();
});

async function getSearch(keyword) {
  const r = await fetch(`${base}/search?keyword=${encodeURIComponent(keyword)}`);
  return { status: r.status, body: await r.json() };
}

const PUBLIC_KEYS = new Set([
  'id', 'name', 'artists', 'album', 'cover',
  'duration', 'vip', 'platform', 'score', 'sourcePlatforms',
]);

function assertPublicShape(list) {
  for (const item of list) {
    for (const k of Object.keys(item)) {
      assert.ok(PUBLIC_KEYS.has(k), `响应泄漏内部字段: ${k}`);
    }
    // 递归确认无嵌套内部字段残留
    assert.equal(JSON.stringify(item).includes('higherQuality'), false);
    assert.equal(JSON.stringify(item).includes('_raw'), false);
  }
}

test('等时长重复歌曲可正常序列化：首查 200，去重合并双平台，缓存命中仍 200', async () => {
  restoreUpstream = stubUpstreamSuccess();
  const kw = 'regression-dedup-equal-duration';

  const first = await getSearch(kw);
  assert.equal(first.status, 200);
  assert.equal(first.body.code, 200);
  assert.equal(first.body.data.total, 1);
  assert.deepEqual([...first.body.data.list[0].sourcePlatforms].sort(), ['netease', 'qq']);
  assertPublicShape(first.body.data.list);

  const second = await getSearch(kw);
  assert.equal(second.status, 200);
  assert.equal(second.body.message, 'success (cached)');
  assert.equal(second.body.data.total, 1);
  assertPublicShape(second.body.data.list);
});

test('历史毒化缓存（循环引用）不再 500：命中即清洗后返回 200', async () => {
  const kw = 'regression-poisoned-cache';
  const bad = {
    id: 'x', name: '毒', artists: 'a', album: 'b', cover: '',
    duration: 1, vip: false, platform: 'qq', score: 1, sourcePlatforms: ['qq'],
  };
  bad.higherQuality = bad; // 模拟修复前的自引用毒化条目
  bad._raw = { listenCount: 1 };
  // G1 后键尾追加 Cookie 维度：匿名请求落 anon 桶
  searchCache.set(buildSearchCacheKey(kw, 30, undefined, undefined), [bad]);

  const r = await getSearch(kw);
  assert.equal(r.status, 200);
  assert.equal(r.body.code, 200);
  assertPublicShape(r.body.data.list);
});

test('stripSearchInternals 递归剥离嵌套内部字段且不炸循环引用', () => {
  const inner = { id: 1 };
  inner.self = inner;
  const list = [{ id: 'a', _raw: { x: 1 }, finalScore: 9, higherQuality: { duration: 5, _raw: {} }, nested: inner }];
  stripSearchInternals(list);
  assert.deepEqual(Object.keys(list[0]).sort(), ['id', 'nested']);
  assert.deepEqual(list[0].nested, { id: 1 });
});

test('urlCache 硬过期：get 不续命，TTL 到即失效（过期 vkey 不 immortal）', async () => {
  urlCache.set('__regression_probe__', [{ id: 'x', url: 'https://example/x.mp3' }], { ttl: 200 });
  await sleep(100);
  assert.notEqual(urlCache.get('__regression_probe__'), undefined);
  await sleep(120);
  assert.equal(urlCache.get('__regression_probe__'), undefined);
});

test('空结果不入库：上游双失败后恢复，上游恢复即返回新结果而非 stale 空', { timeout: 60000 }, async () => {
  const kw = 'regression-empty-recovery';

  restoreUpstream = stubUpstreamFailure();
  const failed = await getSearch(kw);
  assert.equal(failed.status, 200);
  assert.equal(failed.body.data.total, 0);
  // 空数组永不写入缓存：miss 与 empty 解耦
  assert.equal(searchCache.get(buildSearchCacheKey(kw, 30, undefined, undefined)), undefined);

  restoreUpstream();
  restoreUpstream = stubUpstreamSuccess();
  const recovered = await getSearch(kw);
  assert.equal(recovered.status, 200);
  assert.ok(recovered.body.data.total >= 1);
  assertPublicShape(recovered.body.data.list);
});
