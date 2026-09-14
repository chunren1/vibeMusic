/**
 * QQ 无 Cookie 模式测试（用户无 QQ VIP、永不提供 QQ Cookie 为正常情况）
 *
 * 运行: node --test --test-force-exit test/qq-no-cookie.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 */
const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const qqMusic = require('qq-music-api');
const config = require('../src/config-loader');
const cookie = require('../src/cookie');
const { qqRequestHeaders, fetchQQPlayUrl, fetchQQLyric } = require('../src/routes');

const restores = [];
afterEach(() => { while (restores.length) restores.pop()(); });

// 本地 config.js 可能自带真实 Cookie：每个用例按需覆盖，用完恢复，保证确定性
function setQQConfig(value) {
  const prev = config.qq;
  config.qq = value;
  restores.push(() => { config.qq = prev; });
}

function stubAxiosGet(impl) {
  const orig = axios.get;
  axios.get = impl;
  restores.push(() => { axios.get = orig; });
}

const MID = '004abcXYZ1';
const VKEY_PAYLOAD = {
  req_0: {
    data: {
      midurlinfo: [{ filename: `M800${MID}.mp3`, purl: 'C400XXX.m4a?vkey=FAKE&guid=126548448' }],
      sip: ['https://aqqmusic.tc.qq.com'],
    },
  },
};
const VIP_PAYLOAD = {
  req_0: { data: { midurlinfo: [{ filename: `M800${MID}.mp3`, purl: '' }], sip: [] } },
};

test('无 Cookie 时上游请求不带 Cookie 头，有 Cookie 才带', () => {
  setQQConfig({});
  assert.ok(!('Cookie' in qqRequestHeaders()));

  setQQConfig({ uin: '12345', qqmusic_key: 'ABC' });
  assert.match(qqRequestHeaders().Cookie, /uin=12345/);
});

test('无 Cookie 取链不抛错，返回可用地址', async () => {
  setQQConfig({});
  let seenHeaders;
  stubAxiosGet(async (url, opts) => {
    if (String(url).includes('musicu.fcg')) {
      seenHeaders = opts.headers;
      return { data: VKEY_PAYLOAD };
    }
    return { status: 206 }; // CDN 探测命中
  });
  const url = await fetchQQPlayUrl(MID);
  assert.equal(url, 'https://aqqmusic.tc.qq.com/C400XXX.m4a?vkey=FAKE&guid=126548448');
  assert.ok(!('Cookie' in seenHeaders));
});

test('VIP/鉴权限制曲目返回 null（同形），不抛错', async () => {
  setQQConfig({});
  stubAxiosGet(async () => ({ data: VIP_PAYLOAD }));
  assert.equal(await fetchQQPlayUrl(MID), null);

  stubAxiosGet(async () => ({ data: {} }));
  assert.equal(await fetchQQPlayUrl(MID), null);

  stubAxiosGet(async () => { throw new Error('Request failed with status code 403'); });
  assert.equal(await fetchQQPlayUrl(MID), null);
});

test('无 Cookie 歌词可取；缺歌词/异常返回 null，不抛错', async () => {
  setQQConfig({});
  let seenHeaders;
  const lrcText = '[00:01.00]晴天\n';
  stubAxiosGet(async (url, opts) => {
    seenHeaders = opts.headers;
    return { data: { req_0: { data: { lyric: Buffer.from(lrcText, 'utf8').toString('base64') } } } };
  });
  assert.equal(await fetchQQLyric(MID), lrcText);
  assert.ok(!('Cookie' in seenHeaders));

  stubAxiosGet(async () => ({ data: { req_0: { data: {} } } }));
  assert.equal(await fetchQQLyric(MID), null);

  stubAxiosGet(async () => { throw new Error('timeout'); });
  assert.equal(await fetchQQLyric(MID), null);
});

test('无 Cookie 时监控保持安静：checkQQCookie 不调上游、不标失败', async () => {
  setQQConfig({});
  const origApi = qqMusic.api;
  qqMusic.api = async () => { throw new Error('must not be called without cookie'); };
  restores.push(() => { qqMusic.api = origApi; });

  await cookie.checkQQCookie();
  assert.equal(cookie.cookieStatus.qq, true);
  assert.equal(cookie.hasQQCookie(), false);
});

test('无 Cookie 时 failQQCookie 不标失败、不引导恢复', () => {
  setQQConfig({});
  cookie.cookieStatus.qq = true;
  cookie.failQQCookie('模拟异常');
  assert.equal(cookie.cookieStatus.qq, true);
});

test('reloadQQCookie 缺配置时 no-op 返回 false，绝不抛错', () => {
  const prevEnv = process.env.MUSIC_QQ_COOKIE;
  delete process.env.MUSIC_QQ_COOKIE;
  restores.push(() => {
    if (prevEnv === undefined) delete process.env.MUSIC_QQ_COOKIE;
    else process.env.MUSIC_QQ_COOKIE = prevEnv;
  });
  const fs = require('fs');
  const origRead = fs.readFileSync;
  fs.readFileSync = () => { const e = new Error('ENOENT'); e.code = 'ENOENT'; throw e; };
  restores.push(() => { fs.readFileSync = origRead; });

  assert.equal(cookie.reloadQQCookie(), false);
});

test('reloadQQCookie 有环境变量时可重载（无需重启）', () => {
  setQQConfig({});
  const prevEnv = process.env.MUSIC_QQ_COOKIE;
  process.env.MUSIC_QQ_COOKIE = JSON.stringify({ uin: '999', qqmusic_key: 'K' });
  restores.push(() => {
    if (prevEnv === undefined) delete process.env.MUSIC_QQ_COOKIE;
    else process.env.MUSIC_QQ_COOKIE = prevEnv;
  });
  assert.equal(cookie.reloadQQCookie(), true);
  assert.equal(config.qq.uin, '999');
});

test('端到端（无 Cookie、无真实网络）：/song/url/qq 200 同形，VIP 降级 url=null；/qq/lyric 缺词 404 非 500', async (t) => {
  setQQConfig({});
  stubAxiosGet(async (url) => {
    if (String(url).includes('musicu.fcg')) return { data: VKEY_PAYLOAD };
    return { status: 206 };
  });

  const express = require('express');
  const registerRoutes = require('../src/routes');
  const app = express();
  registerRoutes(app);
  const server = await new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;

  let r = await fetch(`${base}/song/url/qq?id=${MID}`);
  assert.equal(r.status, 200);
  let body = await r.json();
  assert.deepEqual(body, { code: 200, data: [{ id: MID, url: 'https://aqqmusic.tc.qq.com/C400XXX.m4a?vkey=FAKE&guid=126548448' }] });

  r = await fetch(`${base}/qq/lyric?songmid=${MID}`);
  assert.equal(r.status, 404); // stub 未返回 lyric 字段 → 干净空形，非 500
  body = await r.json();
  assert.equal(body.code, 404);
});
