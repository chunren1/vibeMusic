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
  DEFAULT_PROBE_TIMEOUT_MS,
  DEFAULT_RETRY_DELAY_MS,
  resolveProbeTimeoutMs,
  resolveRetryDelayMs,
  isCapabilityProbeEnabled,
  probeTrackId,
  isPlayableUrlPayload,
  isInconclusiveUrlPayload,
  isInconclusiveUpstreamResponse,
  isInconclusiveUpstreamError,
  probeNeteaseLogin,
  probeNeteasePlayback,
  getProbeState,
  _resetProbeStateForTest,
  isTimeoutError,
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
  _resetProbeStateForTest();
  delete config.neteaseProbeTrackId;
  delete config.neteaseProbeEnabled;
  delete process.env.MUSICAPI_PROBE_TIMEOUT_MS;
  delete process.env.MUSICAPI_PROBE_RETRY_DELAY_MS;
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

// ---------- 上游风控 / 暂时性故障：inconclusive 三态（2026-11-06 刷屏修复）----------

/** 实测形：HTTP 400 + {code:-460, message:'检测到您的网络环境存在风险，请稍后再试'} */
function riskControlResponse() {
  return { status: 400, body: { code: -460, message: '检测到您的网络环境存在风险，请稍后再试' } };
}

test('isInconclusiveUrlPayload：-460 / 风控文案 / 服务繁忙 → true；试听与正常体 → false', () => {
  assert.equal(isInconclusiveUrlPayload(riskControlResponse().body), true);
  assert.equal(isInconclusiveUrlPayload({ code: 200, message: '服务繁忙，请稍后再试' }), true);
  assert.equal(isInconclusiveUrlPayload({ code: -460 }), true);
  // 明确不可播不是 inconclusive：它是真结论（Cookie 没播放权限）
  assert.equal(isInconclusiveUrlPayload(trialBody().body), false);
  assert.equal(isInconclusiveUrlPayload(playableBody().body), false);
  assert.equal(isInconclusiveUrlPayload(null), false);
});

test('isInconclusiveUpstreamResponse / Error：5xx 与超时归 unknown，普通业务错不归', () => {
  assert.equal(isInconclusiveUpstreamResponse({ status: 502, body: { code: 200 } }), true);
  assert.equal(isInconclusiveUpstreamResponse({ status: 400, body: { code: 404 } }), false);
  assert.equal(isInconclusiveUpstreamError(Object.assign(new Error('timeout'), { isTimeout: true })), true);
  const e5xx = Object.assign(new Error('bad gateway'), { status: 503 });
  assert.equal(isInconclusiveUpstreamError(e5xx), true);
  assert.equal(isInconclusiveUpstreamError(new Error('upstream down')), false);
});

test('重试间隔：默认 1.5s，注入入参/env 可调，0 合法', () => {
  delete process.env.MUSICAPI_PROBE_RETRY_DELAY_MS;
  assert.equal(DEFAULT_RETRY_DELAY_MS, 1500);
  assert.equal(resolveRetryDelayMs(), 1500);
  assert.equal(resolveRetryDelayMs(0), 0);
  assert.equal(resolveRetryDelayMs(300), 300);
  process.env.MUSICAPI_PROBE_RETRY_DELAY_MS = '20';
  assert.equal(resolveRetryDelayMs(), 20);
  process.env.MUSICAPI_PROBE_RETRY_DELAY_MS = '-1';
  assert.equal(resolveRetryDelayMs(), DEFAULT_RETRY_DELAY_MS, '非法值回落默认');
});

test('-460 风控：重试一次后仍不可用 → unknown，不是"Cookie 失效"', async () => {
  let calls = 0;
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => { calls++; return riskControlResponse(); });
  const delays = [];

  const out = await probeNeteasePlayback(P, {
    delay: async (ms) => { delays.push(ms); },
    retryDelayMs: DEFAULT_RETRY_DELAY_MS,
  });

  assert.equal(out, 'unknown');
  assert.equal(calls, 2, 'inconclusive 必须先重试一次再下结论');
  assert.deepEqual(delays, [DEFAULT_RETRY_DELAY_MS]);
  assert.equal(getProbeState().playback.status, 'unknown');
  assert.equal(getProbeState().playback.detail, 'code:-460');
});

test('-460 只在首次出现：重试成功 → true（不误判失效）', async () => {
  let calls = 0;
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => (++calls === 1 ? riskControlResponse() : playableBody()));

  assert.equal(await probeNeteasePlayback(P, { delay: async () => {}, retryDelayMs: 0 }), true);
  assert.equal(calls, 2);
  assert.equal(getProbeState().playback.status, 'ok');
});

test('checkCookies：-460 时沿用上一状态，不改 neteasePrimary / 不切槽位', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => riskControlResponse());
  cookie._setSharedForTest({ primary: P, active: 'primary' });
  cookie.setSlotGauges(true, true, false);          // 事故现场：/cookie-status 显示主备都正常

  const out = await cookie.checkCookies({ probeRetryDelayMs: 0 });

  assert.equal(out.inconclusive, true);
  assert.equal(out.primaryOk, true, 'unknown 沿用上一次已知状态');
  assert.equal(cookie.cookieStatus.neteasePrimary, true, '-460 绝不能把主槽改写成失效');
  assert.equal(cookie.cookieStatus.netease, true);
  assert.equal(cookie.getNeteaseActive(), 'primary', '不切槽位');
});

test('checkCookies：明确不可播（30s 试听）→ false，仍判不健康', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => trialBody());
  cookie._setSharedForTest({ primary: P, active: 'primary' });
  cookie.setSlotGauges(true, true, false);

  const out = await cookie.checkCookies({ probeRetryDelayMs: 0 });

  assert.equal(out.inconclusive, false);
  assert.equal(out.primaryOk, false);
  assert.equal(cookie.cookieStatus.neteasePrimary, false, '真掉 VIP 必须能报出来');
});

test('checkCookies：正常可播 → true，gauge 保持健康', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => playableBody());
  cookie._setSharedForTest({ primary: P, active: 'primary' });

  const out = await cookie.checkCookies({ probeRetryDelayMs: 0 });

  assert.equal(out.inconclusive, false);
  assert.equal(out.primaryOk, true);
  assert.equal(cookie.cookieStatus.neteasePrimary, true);
});

test('rotateNeteaseActive：当前槽 unknown 时保持槽位不切换', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', async () => riskControlResponse());
  cookie._setSharedForTest({ primary: P, backup: B, active: 'primary' });

  const out = await cookie.rotateNeteaseActive({ probeRetryDelayMs: 0 });

  assert.equal(out.switched, false);
  assert.equal(out.active, 'primary');
  assert.equal(out.reason, 'unknown');
  assert.equal(cookie.getNeteaseActive(), 'primary');
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

// ---------- 探针硬超时（2026-10-22 加固：上游挂起不得卡死巡检）----------

test('探针超时配置：默认 8s，环境变量可调并在 100ms~30s 内钳制', () => {
  delete process.env.MUSICAPI_PROBE_TIMEOUT_MS;
  assert.equal(DEFAULT_PROBE_TIMEOUT_MS, 8000);
  assert.equal(resolveProbeTimeoutMs(), 8000);
  process.env.MUSICAPI_PROBE_TIMEOUT_MS = '1500';
  assert.equal(resolveProbeTimeoutMs(), 1500);
  process.env.MUSICAPI_PROBE_TIMEOUT_MS = 'abc';
  assert.equal(resolveProbeTimeoutMs(), DEFAULT_PROBE_TIMEOUT_MS, '非法值回落默认，绝不返回 NaN');
  assert.equal(resolveProbeTimeoutMs(5), 100, '过小值钳到下限');
  assert.equal(resolveProbeTimeoutMs(999999), 30000, '过大值钳到上限');
});

test('登录探针：上游永不返回 → 硬超时返回 false 并标记 failed(timeout)', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', () => new Promise(() => {}));
  const started = Date.now();
  const ok = await probeNeteaseLogin(P, { timeoutMs: 60 });
  const elapsed = Date.now() - started;

  assert.equal(ok, false);
  assert.ok(elapsed < 1000, `应在硬超时后立即返回，实际 ${elapsed}ms`);
  const st = getProbeState();
  assert.equal(st.login.status, 'failed(timeout)');
  assert.equal(st.login.detail, 'timeout');
  assert.equal(isTimeoutError(new Error('x')), false);
});

test('播放能力探针：上游永不返回 → 硬超时归入 unknown（结论不可用，不判 Cookie 失效）', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', async () => loginOk());
  stub(NeteaseCloudMusicApi, 'song_url_v1', () => new Promise(() => {}));

  const started = Date.now();
  const ok = await cookie.probeNeteaseCookie(P, { timeoutMs: 60, retryDelayMs: 0 });
  const elapsed = Date.now() - started;

  // 2026-11-06 语义变更（原断言为 false）：超时是"上游没回答"，不是"Cookie 没有播放权限"，
  // 故返回 'unknown'，由巡检层沿用上一状态；否则上游一挂就会误报 VIP 掉档。
  assert.equal(ok, 'unknown');
  assert.ok(elapsed < 2000, `组合探针应在硬超时+一次重试后返回，实际 ${elapsed}ms`);
  assert.equal(getProbeState().login.status, 'ok');
  assert.equal(getProbeState().playback.status, 'unknown');
  assert.equal(getProbeState().playback.detail, 'timeout');
});

test('探针超时后上游迟到 reject 不产生 unhandledRejection', async () => {
  let unhandled = 0;
  const onUnhandled = () => { unhandled++; };
  process.on('unhandledRejection', onUnhandled);
  try {
    stub(NeteaseCloudMusicApi, 'cloudsearch', () => new Promise((_, reject) => {
      setTimeout(() => reject(new Error('late upstream failure')), 40);
    }));
    assert.equal(await probeNeteaseLogin(P, { timeoutMs: 10 }), false);
    await new Promise((resolve) => setTimeout(resolve, 120));
  } finally {
    process.off('unhandledRejection', onUnhandled);
  }
  assert.equal(unhandled, 0, '迟到 reject 必须被吞掉，不能泄漏到进程级 unhandledRejection');
});

test('巡检看门狗：单轮巡检超过上限即强制结束，不切换槽位、不卡死调用方', async () => {
  stub(NeteaseCloudMusicApi, 'cloudsearch', () => new Promise(() => {}));
  cookie._setSharedForTest({ primary: P, active: 'primary' });

  const started = Date.now();
  const out = await cookie.checkCookies({ watchdogMs: 1000, probeTimeoutMs: 1500 });
  const elapsed = Date.now() - started;

  assert.equal(out.timedOut, true);
  assert.ok(elapsed < 5000, `看门狗应在上限附近返回，实际 ${elapsed}ms`);
  assert.equal(cookie.getNeteaseActive(), 'primary', '结果未收敛时保持原槽位');
});
