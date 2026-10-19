/**
 * 网易云 Cookie「播放能力」探针 + 强制能力快切测试（零新依赖：node:test + node:assert/strict）
 *
 * 运行: node --test --test-force-exit test/netease-capability-probe.test.js
 *
 * 覆盖（③ 项改动）：
 *  - 能力探针判定：登录有效性 ∩ 播放能力（试听/空链一律不可播），kill switch 关闭后回退旧口径；
 *  - 探针曲目可配（默认 273114，可经 config/环境变量覆盖）；
 *  - rotateNeteaseActive({force:true})：重探两槽位（登录+播放能力）后选槽，平局保持当前槽位；
 *  - POST /internal/netease-rotate：不带 force = 旧懒探测行为；force=true = 能力感知强制快切；
 *  - 日志/响应绝不含 Cookie 字节。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');

// 日志目录隔离：必须在 require 任何 src 模块（logger 在加载时确定 LOG_DIR）之前设置
process.env.MUSICAPI_LOG_DIR = path.join(os.tmpdir(), 'vibemusic-musicapi-test-logs');

const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const config = require('../src/config-loader');
const cookie = require('../src/cookie');
const {
  DEFAULT_PROBE_TRACK_ID,
  isCapabilityProbeEnabled,
  probeTrackId,
  isPlayableUrlPayload,
  probeNeteaseLogin,
  probeNeteasePlayback,
} = require('../src/cookie-probe');

const P = 'MUSIC_U=primary-cookie-value; __csrf=p';
const B = 'MUSIC_U=backup-cookie-value; __csrf=b';

const restores = [];
function stub(obj, key, impl) {
  const orig = obj[key];
  obj[key] = impl;
  restores.push(() => { obj[key] = orig; });
}
afterEach(() => {
  while (restores.length) restores.pop()();
  cookie._resetSharedForTest();
  delete config.neteaseProbeTrackId;
  delete config.neteaseProbeEnabled;
});

// ---------- 用例数据 ----------

function loginOk() {
  return { body: { code: 200, result: { songs: [{ id: 386038, name: '晴天' }] } } };
}

/** 有播放权限：完整可播链接、无试听信息 */
function playableBody() {
  return { body: { code: 200, data: [{ id: DEFAULT_PROBE_TRACK_ID, url: 'https://ne-cdn.example/full.mp3', fee: 1, payed: 1, time: 240000 }] } };
}

/** 掉 VIP：只回 30s 试听（实测形：fee=1 && payed=0 && freeTrialInfo） */
function trialBody() {
  return {
    body: {
      code: 200,
      data: [{
        id: DEFAULT_PROBE_TRACK_ID, url: 'https://ne-cdn.example/preview.mp3',
        fee: 1, payed: 0, level: 'standard', time: 30000,
        freeTrialInfo: { start: 0, end: 30 },
      }],
    },
  };
}

// ---------- isPlayableUrlPayload：纯函数口径 ----------

test('isPlayableUrlPayload：完整链接可播，试听/空链不可播', () => {
  assert.equal(isPlayableUrlPayload(playableBody().body), true);
  assert.equal(isPlayableUrlPayload(trialBody().body), false);
  assert.equal(isPlayableUrlPayload({ code: 200, data: [{ url: '' }] }), false);
  assert.equal(isPlayableUrlPayload({ code: 200, data: [{ fee: 1 }] }), false);
  assert.equal(isPlayableUrlPayload({ code: 200, data: [] }), false);
  assert.equal(isPlayableUrlPayload({ code: 301 }), false);
  assert.equal(isPlayableUrlPayload(null), false);
  assert.equal(isPlayableUrlPayload(undefined), false);
});

// ---------- 探针开关 / 探针曲目 ----------

test('能力探针默认开启，探针曲目默认 273114（VIP-only）', () => {
  assert.equal(isCapabilityProbeEnabled(), true);
  assert.equal(probeTrackId(), DEFAULT_PROBE_TRACK_ID);
  assert.equal(DEFAULT_PROBE_TRACK_ID, '273114');
});

test('kill switch 关闭：能力探针不再调用 song_url_v1，健康只剩登录口径', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  let urlCalls = 0;
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => { urlCalls++; return trialBody(); });
  config.neteaseProbeEnabled = false;

  assert.equal(await cookie.probeNeteaseCookie(P), true);
  assert.equal(urlCalls, 0, '关闭后不应再打取链探针（行为与旧版一致）');
});

test('探针曲目可配：neteaseProbeTrackId 覆盖默认值并透传到取链', async () => {
  const seen = [];
  stub(NeteaseCloudMusicApi, 'song_url_v1', async (params) => { seen.push(params.id); return playableBody(); });
  config.neteaseProbeTrackId = '186016';

  assert.equal(await probeNeteasePlayback(P), true);
  assert.deepEqual(seen, ['186016']);
  assert.equal(probeTrackId(), '186016');
});

// ---------- 能力探针本身 ----------

test('probeNeteasePlayback：可播 true / 试听 false / 上游抛错 false / 空 Cookie 不打上游', async () => {
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => playableBody());
  assert.equal(await probeNeteasePlayback(P), true);

  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => trialBody());
  assert.equal(await probeNeteasePlayback(P), false);

  let called = 0;
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => { called++; throw new Error('upstream down'); });
  assert.equal(await probeNeteasePlayback(P), false);

  assert.equal(await probeNeteasePlayback(''), false);
  assert.equal(called, 1, '空 Cookie 不应触达上游');
});

test('组合探针：登录通过但播放能力不通过 → 槽位不健康（VIP 掉档可被探到）', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => trialBody());

  assert.equal(await cookie.probeNeteaseCookie(P), false);
});

test('组合探针：登录失效短路，不再打取链探针', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => ({ body: { code: 301 } }));
  let urlCalls = 0;
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => { urlCalls++; return playableBody(); });

  assert.equal(await cookie.probeNeteaseCookie(P), false);
  assert.equal(await probeNeteaseLogin(P), false);
  assert.equal(urlCalls, 0);
});

// ---------- 强制能力快切选槽 ----------

test('pickCapableSlot：双可用平局保持当前槽位，单可用取可用槽，双不可用保持现状', () => {
  assert.equal(cookie.pickCapableSlot({ primaryOk: true, backupOk: true, current: 'backup' }), 'backup');
  assert.equal(cookie.pickCapableSlot({ primaryOk: true, backupOk: true, current: 'primary' }), 'primary');
  assert.equal(cookie.pickCapableSlot({ primaryOk: true, backupOk: false, current: 'backup' }), 'primary');
  assert.equal(cookie.pickCapableSlot({ primaryOk: false, backupOk: true, current: 'primary' }), 'backup');
  assert.equal(cookie.pickCapableSlot({ primaryOk: false, backupOk: false, current: 'backup' }), 'backup');
});

test('rotateNeteaseActive({force:true})：主槽掉 VIP、备槽可播 → 切到备槽', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async (params) => (params.cookie === B ? playableBody() : trialBody()));
  cookie._setSharedForTest({ primary: P, backup: B, active: 'primary' });

  const out = await cookie.rotateNeteaseActive({ force: true });

  assert.equal(out.active, 'backup');
  assert.equal(out.switched, true);
  assert.equal(out.forced, true);
  assert.equal(out.primaryOk, false);
  assert.equal(out.backupOk, true);
  assert.equal(cookie.getNeteaseActive(), 'backup');
  assert.deepEqual(cookie.resolveSharedCookie().cookie, B);
});

test('rotateNeteaseActive({force:true})：双槽都可播（平局）保持当前槽位不动', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => playableBody());
  cookie._setSharedForTest({ primary: P, backup: B, active: 'backup' });

  const out = await cookie.rotateNeteaseActive({ force: true });

  assert.equal(out.active, 'backup');
  assert.equal(out.switched, false);
  assert.equal(cookie.getNeteaseActive(), 'backup');
});

test('rotateNeteaseActive({force:true})：备槽掉 VIP、主槽可播 → 回切主槽', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async (params) => (params.cookie === P ? playableBody() : trialBody()));
  cookie._setSharedForTest({ primary: P, backup: B, active: 'backup' });

  const out = await cookie.rotateNeteaseActive({ force: true });

  assert.equal(out.active, 'primary');
  assert.equal(out.switched, true);
});

test('rotateNeteaseActive({force:true})：双槽都掉 VIP → 保持现状（降级链不变）', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => trialBody());
  cookie._setSharedForTest({ primary: P, backup: B, active: 'primary' });

  const out = await cookie.rotateNeteaseActive({ force: true });

  assert.equal(out.active, 'primary');
  assert.equal(out.switched, false);
  assert.equal(out.primaryOk, false);
  assert.equal(out.backupOk, false);
});

test('快切响应绝不含 Cookie 字节', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => playableBody());
  cookie._setSharedForTest({ primary: P, backup: B, active: 'primary' });

  const out = await cookie.rotateNeteaseActive({ force: true });

  assert.equal(JSON.stringify(out).includes('MUSIC_U'), false);
});

// ---------- GET/POST 端点：force 标志 ----------

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

beforeEach(() => {
  // 门禁：无令牌时仅本机可调（测试走 127.0.0.1，符合内网调用方口径）
  delete process.env.MUSICAPI_ADMIN_TOKEN;
});

async function postRotate(query = '') {
  const r = await fetch(`${base}/internal/netease-rotate${query}`, { method: 'POST' });
  return { status: r.status, body: await r.json() };
}

test('端点不带 force：响应形状与语义保持旧版（懒探测，无能力感知标记）', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => playableBody());
  cookie._setSharedForTest({ primary: P, backup: B, active: 'primary' });

  const r = await postRotate();

  assert.equal(r.status, 200);
  assert.equal(r.body.code, 200);
  // 旧形状精确一致：只含 switched/active，不新增字段
  assert.deepEqual(r.body.data, { switched: false, active: 'primary' });
  assert.equal(JSON.stringify(r.body).includes('MUSIC_U'), false);
});

test('端点不带 force：当前槽位掉 VIP 时，懒探测仍按 need-login 口径切到可播槽', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async (params) => (params.cookie === B ? playableBody() : trialBody()));
  cookie._setSharedForTest({ primary: P, backup: B, active: 'primary' });

  const r = await postRotate();

  // 旧响应形状不变（无 forced/primaryOk 等新字段），仅槽位切到可播的备槽
  assert.deepEqual(r.body.data, { switched: true, active: 'backup' });
});

test('端点 force=true：能力感知强制快切，重探两槽位并切到可播槽', async () => {
  let searchCalls = 0;
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => { searchCalls++; return loginOk(); });
  stub(NeteaseCloudMusicApi, 'song_url_v1', async (params) => (params.cookie === B ? playableBody() : trialBody()));
  cookie._setSharedForTest({ primary: P, backup: B, active: 'primary' });

  const r = await postRotate('?force=true');

  assert.equal(r.status, 200);
  assert.deepEqual(r.body.data, {
    switched: true, active: 'backup', forced: true, primaryOk: false, backupOk: true,
  });
  assert.equal(searchCalls, 2, 'force 需重探两个槽位');
  assert.equal(JSON.stringify(r.body).includes('MUSIC_U'), false);
});

test('端点 force 也可走 JSON body（等价于查询串）', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => playableBody());
  cookie._setSharedForTest({ primary: P, backup: B, active: 'backup' });

  const res = await fetch(`${base}/internal/netease-rotate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ force: true }),
  });
  const body = await res.json();

  assert.equal(body.data.forced, true);
  assert.equal(body.data.active, 'backup');
});
