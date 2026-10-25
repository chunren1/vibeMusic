/**
 * 酷狗歌词两步接口单测（src/kugou-lyric.js）
 *
 * 运行: node --test test/kugou-lyric.test.js（纯 stub，无真实网络）
 * 覆盖：候选择优（官方 > UGC、时长最接近、并列取首条）、id/accesskey 过滤、
 *       duration 归一化（毫秒/秒）、base64 解码、超时/体积上限、
 *       上游各类失败一律 null（路由据此 404，绝不 500）。
 */
const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const {
  fetchKugouLyric,
  pickKugouLyricCandidate,
  parseKugouLyricDuration,
  isUgcCandidate,
  KUGOU_LYRIC_TIMEOUT,
} = require('../src/kugou-lyric');

const HASH = '6bc433a2c149ffc43f8309c73a537601';
const LRC = '[id:$00000000]\n[ar:Missy]\n[ti:晴天 (DJ降调0.9x版)]\n[00:01.00]故事的小黄花\n';

const restores = [];
function stubAxiosGet(impl) {
  const orig = axios.get;
  axios.get = impl;
  restores.push(() => { axios.get = orig; });
}
afterEach(() => {
  while (restores.length) restores.pop()();
});

function candidate(over = {}) {
  return Object.assign({
    id: '741307774',
    accesskey: '8D735CF26880D11E9F70B8953F206FBE',
    song: '晴天',
    singer: 'Missy',
    duration: 240,
    product_from: 'kugou',
  }, over);
}

function searchResp(candidates) {
  return { data: { status: 200, candidates } };
}

function downloadResp(lrc = LRC) {
  return { data: { status: 200, fmt: 'lrc', content: Buffer.from(lrc, 'utf8').toString('base64') } };
}

/** 两步 stub：记录每次调用的 (url, params, headers)。 */
function stubTwoStep({ search = searchResp([candidate()]), download = downloadResp(), onCall } = {}) {
  const calls = [];
  stubAxiosGet(async (url, opts) => {
    const u = String(url);
    calls.push({ url: u, params: opts.params, headers: opts.headers, timeout: opts.timeout, maxBytes: opts.maxContentLength });
    if (onCall) onCall(u, opts);
    if (u.includes('krcs.kugou.com/search')) return typeof search === 'function' ? search(u, opts) : search;
    if (u.includes('lyrics.kugou.com/download')) return typeof download === 'function' ? download(u, opts) : download;
    throw new Error(`unexpected upstream call: ${u}`);
  });
  return calls;
}

// ---- 候选择优 ----

test('isUgcCandidate：product_from=ugc 才判 UGC，缺失按非 UGC', () => {
  assert.equal(isUgcCandidate({ product_from: 'ugc' }), true);
  assert.equal(isUgcCandidate({ product_from: 'UGC' }), true);
  assert.equal(isUgcCandidate({ product_from: 'kugou' }), false);
  assert.equal(isUgcCandidate({}), false);
  assert.equal(isUgcCandidate(null), false);
});

test('pickKugouLyricCandidate：官方优先于 UGC（即使 UGC 时长更接近）', () => {
  const ugc = candidate({ id: '1', accesskey: 'AAAA', product_from: 'ugc', duration: 240 });
  const official = candidate({ id: '2', accesskey: 'BBBB', product_from: 'kugou', duration: 260 });
  assert.equal(pickKugouLyricCandidate([ugc, official], 240).id, '2');
});

test('pickKugouLyricCandidate：同为官方时选时长最接近的', () => {
  const far = candidate({ id: '1', accesskey: 'AAAA', duration: 300 });
  const near = candidate({ id: '2', accesskey: 'BBBB', duration: 239 });
  assert.equal(pickKugouLyricCandidate([far, near], 240).id, '2');
});

test('pickKugouLyricCandidate：无 duration 目标 → 保持原始顺序（取第一条官方）', () => {
  const first = candidate({ id: '1', accesskey: 'AAAA', duration: 300 });
  const second = candidate({ id: '2', accesskey: 'BBBB', duration: 240 });
  assert.equal(pickKugouLyricCandidate([first, second]).id, '1');
  assert.equal(pickKugouLyricCandidate([first, second], 0).id, '1');
});

test('pickKugouLyricCandidate：候选无 duration 时该维度不参与比较，回退原始顺序', () => {
  const a = candidate({ id: '1', accesskey: 'AAAA', duration: undefined });
  const b = candidate({ id: '2', accesskey: 'BBBB', duration: undefined });
  assert.equal(pickKugouLyricCandidate([a, b], 240).id, '1');
});

test('pickKugouLyricCandidate：全是 UGC 时按时长挑（无官方可选）', () => {
  const a = candidate({ id: '1', accesskey: 'AAAA', product_from: 'ugc', duration: 300 });
  const b = candidate({ id: '2', accesskey: 'BBBB', product_from: 'ugc', duration: 241 });
  assert.equal(pickKugouLyricCandidate([a, b], 240).id, '2');
});

test('pickKugouLyricCandidate：缺 id/accesskey 或含怪字符的候选被丢弃', () => {
  const dirty = [
    candidate({ id: '', accesskey: 'AAAA' }),
    candidate({ id: '3', accesskey: '' }),
    candidate({ id: '4&x=1', accesskey: 'BBBB' }),
    candidate({ id: '5', accesskey: 'CC CC' }),
  ];
  assert.equal(pickKugouLyricCandidate(dirty), null);
  assert.equal(pickKugouLyricCandidate([]), null);
  assert.equal(pickKugouLyricCandidate(null), null);
  assert.equal(pickKugouLyricCandidate([...dirty, candidate({ id: '6', accesskey: 'DDDD' })]).id, '6');
});

// ---- duration 归一化 ----

test('parseKugouLyricDuration：毫秒/秒都接受，非法归 0', () => {
  assert.equal(parseKugouLyricDuration('240000'), 240, '毫秒 → 秒');
  assert.equal(parseKugouLyricDuration(240000), 240);
  assert.equal(parseKugouLyricDuration('240'), 240, '秒原样');
  assert.equal(parseKugouLyricDuration(['240000']), 240, '重复 query 取第一个');
  assert.equal(parseKugouLyricDuration(undefined), 0);
  assert.equal(parseKugouLyricDuration(''), 0);
  assert.equal(parseKugouLyricDuration('abc'), 0);
  assert.equal(parseKugouLyricDuration(-5), 0);
  assert.equal(parseKugouLyricDuration('0'), 0);
});

// ---- 两步取词 ----

test('fetchKugouLyric：两步参数/头/超时正确，content base64 解码，回带 song/singer', async () => {
  const calls = stubTwoStep();
  const hit = await fetchKugouLyric(HASH, 240);

  assert.equal(calls.length, 2);
  const [search, download] = calls;

  assert.match(search.url, /krcs\.kugou\.com\/search$/);
  assert.equal(search.params.hash, HASH);
  assert.equal(search.params.ver, 1);
  assert.equal(search.params.man, 'yes');
  assert.equal(search.params.client, 'mobi');
  assert.equal(search.headers['User-Agent'], 'Mozilla/5.0');
  assert.equal(search.headers.Referer, 'https://www.kugou.com/');
  assert.equal(search.timeout, KUGOU_LYRIC_TIMEOUT, '搜索步必须有超时');

  assert.match(download.url, /lyrics\.kugou\.com\/download$/);
  assert.deepEqual(
    { ver: download.params.ver, client: download.params.client, id: download.params.id, fmt: download.params.fmt, charset: download.params.charset },
    { ver: 1, client: 'pc', id: '741307774', fmt: 'lrc', charset: 'utf8' },
  );
  assert.equal(download.params.accesskey, '8D735CF26880D11E9F70B8953F206FBE');
  assert.equal(download.timeout, KUGOU_LYRIC_TIMEOUT, '下载步必须有超时');

  assert.deepEqual(hit, { lyric: LRC, song: '晴天', singer: 'Missy', duration: 240 });
});

test('fetchKugouLyric：时长目标透传到候选选择（选 240s 而非 300s）', async () => {
  stubTwoStep({
    search: searchResp([
      candidate({ id: '300', accesskey: 'AAAA', duration: 300 }),
      candidate({ id: '240', accesskey: 'BBBB', duration: 240 }),
    ]),
  });
  const hit = await fetchKugouLyric(HASH, 240);
  assert.equal(hit.duration, 240);
});

test('fetchKugouLyric：base64 解出空串/空白 → null', async () => {
  stubTwoStep({ download: { data: { status: 200, fmt: 'lrc', content: Buffer.from('   ', 'utf8').toString('base64') } } });
  assert.equal(await fetchKugouLyric(HASH, 240), null);
});

test('fetchKugouLyric：无候选 → null', async () => {
  stubTwoStep({ search: searchResp([]) });
  assert.equal(await fetchKugouLyric(HASH, 240), null);
});

test('fetchKugouLyric：搜索步 status!=200 / candidates 非数组 → null', async () => {
  stubTwoStep({ search: { data: { status: 500, candidates: [candidate()] } } });
  assert.equal(await fetchKugouLyric(HASH, 240), null);

  restores.pop()();
  stubTwoStep({ search: { data: { status: 200 } } });
  assert.equal(await fetchKugouLyric(HASH, 240), null);
});

test('fetchKugouLyric：下载步 status!=200 / content 非字符串 → null', async () => {
  stubTwoStep({ download: { data: { status: 404 } } });
  assert.equal(await fetchKugouLyric(HASH, 240), null);

  restores.pop()();
  stubTwoStep({ download: { data: { status: 200, content: null } } });
  assert.equal(await fetchKugouLyric(HASH, 240), null);
});

test('fetchKugouLyric：上游抛错（超时/网络）→ null，永不抛错', async () => {
  stubAxiosGet(async () => { throw new Error('timeout of 6000ms exceeded'); });
  await assert.doesNotReject(async () => {
    assert.equal(await fetchKugouLyric(HASH, 240), null);
  });
});

test('fetchKugouLyric：hash 非法直接 null，不请求上游', async () => {
  let calls = 0;
  stubAxiosGet(async () => { calls++; return searchResp([candidate()]); });
  assert.equal(await fetchKugouLyric('not-a-hash', 240), null);
  assert.equal(await fetchKugouLyric('', 240), null);
  assert.equal(await fetchKugouLyric(null, 240), null);
  assert.equal(calls, 0);
});
