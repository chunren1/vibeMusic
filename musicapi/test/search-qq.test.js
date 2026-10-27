const { test } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const { searchQQ, mapQQSong } = require('../src/search');

const NEW_SAMPLE = {
  code: 0,
  search: {
    code: 0,
    data: {
      body: {
        song: {
          list: [
            {
              id: 12345,
              mid: '004abcXYZ',
              name: '晴天',
              singer: [{ id: 1, mid: '002xxx', name: '周杰伦' }, { id: 2, mid: '003yyy', name: '方文山' }],
              album: { id: 99, mid: '002eFUFQ2XYZOP', name: '叶惠美' },
              interval: 245,
              pay: { pay_play: 0 },
            },
            {
              id: 67890,
              mid: '001vipMID',
              name: '付费歌',
              singer: [{ name: '测试歌手' }],
              album: { id: 100, mid: '004vipALB', name: '测试专辑' },
              interval: 180,
              pay: { pay_play: 1 },
            },
          ],
        },
      },
    },
  },
};

// ---- 熔断记账口径（2026-10-27）：空结果不得被当成上游故障 ----
// 背景：QQ 的成功响应 code 就是 0（见上面 NEW_SAMPLE），而「查无结果」在生产里
// 18 小时出现 11 次。若一律记失败，连续 3 个冷门词就会熔断 QQ 60s —— 典型误熔断。
const sourceHealth = require('../src/source-health');

function qqFailures() {
  return sourceHealth.snapshot().sources.find((s) => s.name === 'qq').consecutiveFailures;
}

test('QQ 成功码 + 空列表 → 空结果，不计失败', async () => {
  sourceHealth.reset();
  const restore = stubAxiosGet(async () => ({ data: { code: 0, search: { data: { body: { song: { list: [] } } } } } }));
  try {
    assert.deepEqual(await searchQQ('查无此歌xyz', 10), []);
    assert.equal(qqFailures(), 0, '空结果不应计入连续失败');
  } finally { restore(); }
});

test("QQ 旧成功码 '000000' + 空列表 → 同样不计失败", async () => {
  sourceHealth.reset();
  const restore = stubAxiosGet(async () => ({ data: { code: '000000', search: { data: { body: { song: { list: [] } } } } } }));
  try {
    assert.deepEqual(await searchQQ('查无此歌xyz', 10), []);
    assert.equal(qqFailures(), 0);
  } finally { restore(); }
});

test('QQ 非成功码 + 空列表 → 记为上游异常（真故障要能被熔断）', async () => {
  sourceHealth.reset();
  const restore = stubAxiosGet(async () => ({ data: { code: 500, search: { data: { body: { song: { list: [] } } } } } }));
  try {
    assert.deepEqual(await searchQQ('周杰伦', 10), []);
    assert.equal(qqFailures(), 1);
  } finally { restore(); }
});

test('QQ 网络异常 → 记为失败并返回空数组', async () => {
  sourceHealth.reset();
  const restore = stubAxiosGet(async () => { throw new Error('ECONNRESET'); });
  try {
    assert.deepEqual(await searchQQ('周杰伦', 10), []);
    assert.equal(qqFailures(), 1);
    // upstream.js 会把 axios 错误包成自己的文案（"upstream qq/search unknown after 0ms"），
    // 所以这里只断言「有错误摘要」而不是原始 ECONNRESET 文本。
    assert.match(sourceHealth.snapshot().sources.find((s) => s.name === 'qq').lastError, /qq\/search/);
  } finally { restore(); }
});

function stubAxiosGet(impl) {
  const orig = axios.get;
  axios.get = impl;
  return () => { axios.get = orig; };
}

test('new-format sample maps to existing normalized shape incl. cover URL', async () => {
  let seenUrl; let seenParams; let seenHeaders;
  const restore = stubAxiosGet(async (url, opts) => {
    seenUrl = url; seenParams = opts.params; seenHeaders = opts.headers;
    return { data: NEW_SAMPLE };
  });
  try {
    const songs = await searchQQ('周杰伦', 20);
    assert.equal(songs.length, 2);
    assert.equal(seenUrl, 'https://u.y.qq.com/cgi-bin/musicu.fcg');
    assert.equal(seenParams.format, 'json');
    const payload = JSON.parse(seenParams.data);
    assert.equal(payload.comm.g_tk, 5381);
    assert.equal(payload.search.module, 'music.search.SearchCgiService');
    assert.equal(payload.search.method, 'DoSearchForQQMusicDesktop');
    assert.equal(payload.search.param.query, '周杰伦');
    assert.equal(payload.search.param.page_size, 20);
    assert.equal(seenHeaders.Referer, 'https://y.qq.com');

    const [first, second] = songs;
    assert.equal(first.id, '004abcXYZ');
    assert.equal(first.name, '晴天');
    assert.equal(first.artists, '周杰伦 / 方文山');
    assert.equal(first.album, '叶惠美');
    assert.equal(first.cover, 'https://y.gtimg.cn/music/photo_new/T002R300x300M000002eFUFQ2XYZOP.jpg');
    assert.equal(first.duration, 245000);
    assert.equal(first.vip, false);
    assert.equal(second.vip, true);
    assert.equal(second.cover, 'https://y.gtimg.cn/music/photo_new/T002R300x300M000004vipALB.jpg');
    assert.deepEqual(Object.keys(first).sort(), ['_raw', 'album', 'artists', 'cover', 'duration', 'id', 'name', 'vip']);
  } finally {
    restore();
  }
});

test('empty list returns []', async () => {
  const restore = stubAxiosGet(async () => ({ data: { code: 0, search: { data: { body: { song: { list: [] } } } } } }));
  try {
    assert.deepEqual(await searchQQ('不存在的词xyz', 10), []);
  } finally {
    restore();
  }
});

test('missing body returns []', async () => {
  const restore = stubAxiosGet(async () => ({ data: { code: 0 } }));
  try {
    assert.deepEqual(await searchQQ('周杰伦', 10), []);
  } finally {
    restore();
  }
});

test('HTTP error returns [] without throwing', async () => {
  const restore = stubAxiosGet(async () => { throw new Error('Request failed with status code 500'); });
  try {
    assert.deepEqual(await searchQQ('周杰伦', 10), []);
  } finally {
    restore();
  }
});

test('mapQQSong falls back to legacy classic fields when album is flat', () => {
  const out = mapQQSong({
    songmid: 'legacyMID', songname: '老歌', singer: [{ name: '老歌手' }],
    albumname: '老专辑', albummid: 'legacyALB', interval: 200, pay: { pay_play: 0 },
  });
  assert.equal(out.id, 'legacyMID');
  assert.equal(out.album, '老专辑');
  assert.equal(out.cover, 'https://y.gtimg.cn/music/photo_new/T002R300x300M000legacyALB.jpg');
});
