/**
 * 网关内部契约测试：用 contract/gateway.contract.json 校验响应形状。
 *
 * 目的：后端（Java）按这些 key 解析网关响应，字段名/类型一旦漂移，
 * 在这里先红，而不是等线上播放链路静默降级。
 *
 * 运行: node --test --test-force-exit test/contract.test.js（纯 stub，无真实网络）
 */
const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const axios = require('axios');
const registerRoutes = require('../src/routes');

const CONTRACT = JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', '..', 'contract', 'gateway.contract.json'), 'utf8')
);

const restores = [];
afterEach(() => { while (restores.length) restores.pop()(); });

function stubAxiosGet(impl) {
  const orig = axios.get;
  axios.get = impl;
  restores.push(() => { axios.get = orig; });
}

/** 按契约里的类型语法校验（string / string? / [item] / 对象逐 key）。 */
function assertShape(value, spec, at) {
  if (typeof spec === 'string') {
    const nullable = spec.endsWith('?');
    const expected = nullable ? spec.slice(0, -1) : spec;
    if (nullable && value === null) return;
    assert.equal(typeof value, expected, `${at} 类型应为 ${spec}`);
    return;
  }
  if (Array.isArray(spec)) {
    assert.ok(Array.isArray(value), `${at} 应为数组`);
    if (spec.length) value.forEach((v, i) => assertShape(v, spec[0], `${at}[${i}]`));
    return;
  }
  assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value), `${at} 应为对象`);
  for (const [key, child] of Object.entries(spec)) {
    assert.ok(Object.prototype.hasOwnProperty.call(value, key), `${at}.${key} 缺失`);
    assertShape(value[key], child, `${at}.${key}`);
  }
}

async function startApp() {
  const express = require('express');
  const app = express();
  registerRoutes(app);
  const server = await new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  return { server, base: `http://127.0.0.1:${server.address().port}` };
}

async function expectContract(ep, res, { status, body }) {
  assert.equal(res.status, status, `${ep} 期望 HTTP ${status}，实际 ${res.status}`);
  const json = await res.json();
  assertShape(json, body, `${ep} body`);
  return json;
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

test('/song/url/qq 契约：code/data[{id,url}]，缺参 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  stubAxiosGet(async (url) => {
    if (String(url).includes('musicu.fcg')) return { data: VKEY_PAYLOAD };
    return { status: 206 };
  });

  const ep = '/song/url/qq';
  const ok = CONTRACT.endpoints[ep];
  await expectContract(`${ep} success`, await fetch(`${base}${ep}?id=${MID}`), ok.success);

  const [err] = ok.errors;
  await expectContract(`${ep} missing id`, await fetch(`${base}${ep}`), err);
});

test('/qq/lyric 契约：code/data.lyric，参数非法 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const lrc = '[00:01.00]晴天\n';
  stubAxiosGet(async () => ({
    data: { req_0: { data: { lyric: Buffer.from(lrc, 'utf8').toString('base64') } } },
  }));

  const ep = '/qq/lyric';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(`${ep} success`, await fetch(`${base}${ep}?songmid=${MID}`), ok.success);
  assert.equal(body.data.lyric, lrc);

  const bad = ok.errors.find((e) => e.status === 400);
  await expectContract(`${ep} invalid songmid`, await fetch(`${base}${ep}?songmid=x`), bad);
});

test('/qq/playlist 契约：code/data 完整歌单形状，缺参 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  stubAxiosGet(async () => ({
    data: {
      cdlist: [{
        dissid: '123456',
        dissname: '测试歌单',
        desc: '简介',
        logo: 'https://cover/x.jpg',
        nickname: '创建者',
        listennum: 42,
        songlist: [{ songmid: 'mid1', songname: '歌名', singer: [{ name: '歌手' }], albummid: 'am1', albumname: '专辑', interval: 200 }],
      }],
    },
  }));

  const ep = '/qq/playlist';
  const ok = CONTRACT.endpoints[ep];
  await expectContract(`${ep} success`, await fetch(`${base}${ep}?id=123456`), ok.success);

  const [err] = ok.errors;
  await expectContract(`${ep} missing id`, await fetch(`${base}${ep}`), err);
});
