/**
 * 酷狗网关路由测试（K2b：complexsearch v2 搜索 + v5/url Lite 取链）
 *
 * 运行: node --test --test-force-exit test/search-kugou.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 * 覆盖：searchKugou 归一化/Privilege 过滤/<em>剥离、签名三元组稳定性、
 *       getKugouUrl 128/320回落与 CDN 探活选优、/kugou/* 三路由
 *       400 校验、url:null 不缓存、缓存隔离(kugou_url:{hash}:{level})、high/super 直返 null。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const {
  searchKugou, mapKugouSong, getKugouUrl, getKugouPlayData,
  kugouDevice, kugouAndroidSignature, kugouUrlKey, pickKugouUrls,
  urlCache, searchCache,
} = require('../src/search');

const HASH = '1822697a2ff6ac13824c1b0b3ce44c34';
const HASH_HQ = 'b57d11e68f470a928f768f42e13ce6e2';

function searchItem(over = {}) {
  return Object.assign({
    FileHash: HASH,
    SongName: '<em>安静</em>',
    OriSongName: '安静',
    FileName: '<em>周杰伦</em> - <em>安静</em>',
    SingerName: '<em>周杰伦</em>',
    AlbumName: '范特西',
    AlbumID: '958706',
    MixSongID: '32027754',
    ID: '32027754',
    Duration: 334,
    Privilege: 8,
    HQFileHash: HASH_HQ,
    SQFileHash: '',
    Image: 'http://singerimg.kugou.com/uploadpic/softhead/{size}/xxx.jpg',
    AlbumImage: '',
  }, over);
}

function searchPayload(items) {
  return {
    status: 1,
    data: { total: items.length, lists: items },
  };
}

function v5Payload(over = {}) {
  return Object.assign({
    status: 1,
    extName: 'mp3',
    bitRate: 128,
    timeLength: 334000,
    url: ['https://cdn-a.kugou.com/low.mp3', 'https://cdn-b.kugou.com/low.mp3'],
    backupUrl: ['https://cdn-c.kugou.com/low.mp3'],
  }, over);
}

const restores = [];
function stubAxiosGet(impl) {
  const orig = axios.get;
  axios.get = impl;
  restores.push(() => { axios.get = orig; });
}
afterEach(() => {
  while (restores.length) restores.pop()();
  searchCache.clear();
  urlCache.clear();
});

/** 网关 stub：gateway.kugou.com 走 upstream 载荷，其余 URL 视为 CDN 探活，默认 206。 */
function stubGateway(upstream, probeStatus = 206) {
  stubAxiosGet(async (url) => {
    if (String(url).includes('gateway.kugou.com')) return { data: upstream };
    return { status: probeStatus, headers: {}, data: Buffer.from([0, 1]) };
  });
}

// ---- 设备与签名 ----
test('kugouDevice 进程内稳定三元组：GUID 32hex，MID 为其十进制大整数', () => {
  const a = kugouDevice();
  const b = kugouDevice();
  assert.equal(a, b);
  assert.match(a.guid, /^[a-f0-9]{32}$/);
  assert.equal(BigInt(`0x${a.guid}`).toString(10), a.mid);
  assert.equal(a.dfid, '-');
});

test('kugouAndroidSignature 按 key 排序与插入顺序无关，改参则变', () => {
  const p1 = { b: '2', a: '1', mid: 'x' };
  const p2 = { mid: 'x', a: '1', b: '2' };
  assert.equal(kugouAndroidSignature(p1), kugouAndroidSignature(p2));
  assert.match(kugouAndroidSignature(p1), /^[a-f0-9]{32}$/);
  assert.notEqual(kugouAndroidSignature(p1), kugouAndroidSignature({ ...p1, b: '3' }));
});

test('kugouUrlKey 为 32hex 且随 hash/mid 变化', () => {
  const k1 = kugouUrlKey(HASH.toLowerCase(), '123');
  assert.match(k1, /^[a-f0-9]{32}$/);
  assert.notEqual(k1, kugouUrlKey(HASH_HQ.toLowerCase(), '123'));
  assert.notEqual(k1, kugouUrlKey(HASH.toLowerCase(), '456'));
});

test('pickKugouUrls 按 url[]→backupUrl[] 有序去重，非 http 丢弃', () => {
  assert.deepEqual(
    pickKugouUrls({ url: ['https://a/1.mp3', 'https://a/1.mp3'], backupUrl: ['https://b/2.mp3', 'ftp://c/3'] }),
    ['https://a/1.mp3', 'https://b/2.mp3'],
  );
  assert.deepEqual(pickKugouUrls({ url: 'https://a/1.mp3' }), ['https://a/1.mp3']);
  assert.deepEqual(pickKugouUrls(null), []);
  assert.deepEqual(pickKugouUrls({ status: 2 }), []);
});

// ---- 搜索归一化 ----
test('searchKugou 走 complexsearch v2：签名参数齐全，归一化形状正确', async () => {
  let seenUrl; let seenParams; let seenHeaders;
  stubAxiosGet(async (url, opts) => {
    seenUrl = url; seenParams = opts.params; seenHeaders = opts.headers;
    return { data: searchPayload([searchItem()]) };
  });
  const songs = await searchKugou('安静', 20);
  assert.equal(songs.length, 1);
  assert.equal(seenUrl, 'https://gateway.kugou.com/v2/search/song');
  assert.equal(seenHeaders['x-router'], 'complexsearch.kugou.com');
  assert.ok(String(seenHeaders['User-Agent']).startsWith('Android'));
  assert.equal(seenParams.appid, '3116');
  assert.equal(seenParams.clientver, '11520');
  assert.equal(seenParams.dfid, '-');
  assert.equal(seenParams.uuid, '-');
  assert.equal(seenParams.token, '');
  assert.equal(seenParams.userid, '0');
  assert.equal(seenParams.keyword, '安静');
  assert.equal(seenParams.page, '1');
  assert.equal(seenParams.pagesize, '20');
  assert.equal(seenParams.platform, 'AndroidFilter');
  assert.match(seenParams.signature, /^[a-f0-9]{32}$/);
  assert.ok(typeof seenParams.mid === 'string' && seenParams.mid.length > 0);

  const [s] = songs;
  assert.equal(s.id, HASH);
  assert.equal(s.name, '安静');
  assert.equal(s.artists, '周杰伦');
  assert.equal(s.album, '范特西');
  assert.equal(s.cover, 'http://singerimg.kugou.com/uploadpic/softhead/400/xxx.jpg');
  assert.equal(s.duration, 334000);
  assert.equal(s.vip, false);
  assert.equal(s._raw.albumId, '958706');
  assert.equal(s._raw.albumAudioId, '32027754');
  assert.equal(s._raw.hashHQ, HASH_HQ);
  assert.deepEqual(Object.keys(s).sort(), ['_raw', 'album', 'artists', 'cover', 'duration', 'id', 'name', 'vip']);
});

test('searchKugou 过滤 Privilege=10 付费档与非法 hash', async () => {
  stubAxiosGet(async () => ({
    data: searchPayload([
      searchItem(),
      searchItem({ FileHash: 'ffffffffffffffffffffffffffffffff', Privilege: 10 }),
      searchItem({ FileHash: 'not-a-hash', Privilege: 0 }),
      searchItem({ FileHash: HASH_HQ, Privilege: 0, OriSongName: '', SongName: '纯歌名' }),
    ]),
  }));
  const songs = await searchKugou('安静', 20);
  assert.equal(songs.length, 2);
  assert.equal(songs[1].name, '纯歌名');
});

test('mapKugouSong 无纯歌名时从 FileName 剥歌手前缀，多歌手顿号归一', () => {
  const out = mapKugouSong({
    FileHash: HASH, FileName: '歌手A - 合唱', SingerName: '歌手A、歌手B',
    Duration: 200, AlbumID: '9',
  });
  assert.equal(out.artists, '歌手A / 歌手B');
  assert.equal(out.duration, 200000);
  const out2 = mapKugouSong({ FileHash: HASH, FileName: '歌手A - 合唱' });
  assert.equal(out2.name, '合唱');
});

test('searchKugou 非 1 状态/空结果/异常均返回 []', async () => {
  stubAxiosGet(async () => ({ data: { status: 0, err_code: 1 } }));
  assert.deepEqual(await searchKugou('安静', 10), []);
  restores.pop()();

  stubAxiosGet(async () => ({ data: { status: 1, data: { lists: [] } } }));
  assert.deepEqual(await searchKugou('安静', 10), []);
  restores.pop()();

  stubAxiosGet(async () => { throw new Error('socket hang up'); });
  assert.deepEqual(await searchKugou('安静', 10), []);
});

// ---- 取链 ----
test('getKugouUrl low 取 v5 首个探活 200/206 的 URL(跳过 502)', async () => {
  stubAxiosGet(async (url) => {
    if (String(url).includes('gateway.kugou.com')) return { data: v5Payload() };
    if (String(url).includes('cdn-a')) return { status: 502, headers: {}, data: Buffer.alloc(0) };
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  const u = await getKugouUrl(HASH, '958706', 'low');
  assert.equal(u, 'https://cdn-b.kugou.com/low.mp3');
});

test('getKugouUrl 请求携带 key/signature 与 Lite 身份', async () => {
  let seenUrl; let seenParams; let seenHeaders;
  stubAxiosGet(async (url, opts) => {
    if (String(url).includes('gateway.kugou.com')) {
      seenUrl = url; seenParams = opts.params; seenHeaders = opts.headers;
      return { data: v5Payload() };
    }
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  await getKugouUrl(HASH, '958706', 'low');
  assert.equal(seenUrl, 'https://gateway.kugou.com/v5/url');
  assert.equal(seenHeaders['x-router'], 'trackercdn.kugou.com');
  assert.equal(seenParams.hash, HASH.toLowerCase());
  assert.equal(seenParams.album_id, '958706');
  assert.equal(seenParams.quality, '128');
  assert.equal(seenParams.behavior, 'play');
  assert.equal(seenParams.pid, '411');
  assert.equal(seenParams.appid, '3116');
  assert.equal(seenParams.clientver, '11430');
  assert.match(seenParams.key, /^[a-f0-9]{32}$/);
  assert.match(seenParams.signature, /^[a-f0-9]{32}$/);
  assert.equal(seenParams.signature.length, 32);
});

test('getKugouUrl standard 320 无链时回落 128', async () => {
  const seenQuality = [];
  stubAxiosGet(async (url, opts) => {
    if (String(url).includes('gateway.kugou.com')) {
      seenQuality.push(opts.params.quality);
      if (opts.params.quality === '320') return { data: { status: 2 } };
      return { data: v5Payload() };
    }
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  const u = await getKugouUrl(HASH, '958706', 'standard');
  assert.equal(u, 'https://cdn-a.kugou.com/low.mp3');
  assert.deepEqual(seenQuality, ['320', '128']);
});

test('getKugouUrl standard 320 有链时优先 320', async () => {
  stubAxiosGet(async (url, opts) => {
    if (String(url).includes('gateway.kugou.com')) {
      if (opts.params.quality === '320') {
        return { data: v5Payload({ url: ['https://cdn-h.kugou.com/320.mp3'], backupUrl: [] }) };
      }
      return { data: v5Payload() };
    }
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  assert.equal(await getKugouUrl(HASH, '958706', 'standard'), 'https://cdn-h.kugou.com/320.mp3');
});

test('getKugouUrl 无链/异常返回 null 永不抛错', async () => {
  stubAxiosGet(async (url) => {
    if (String(url).includes('gateway.kugou.com')) return { data: { status: 2 } };
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  assert.equal(await getKugouUrl(HASH, '958706', 'low'), null);
  restores.pop()();

  stubAxiosGet(async (url) => {
    if (String(url).includes('gateway.kugou.com')) return { data: v5Payload({ url: [], backupUrl: [] }) };
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  assert.equal(await getKugouUrl(HASH, '958706', 'low'), null);
  restores.pop()();

  stubAxiosGet(async () => { throw new Error('timeout'); });
  assert.equal(await getKugouUrl(HASH, '958706', 'low'), null);
});

test('getKugouPlayData 返回 v5 适配节点(v5 无内联歌词→lyrics 为空)', async () => {
  stubGateway(v5Payload());
  const data = await getKugouPlayData(HASH, '958706');
  assert.equal(data.play_url, 'https://cdn-a.kugou.com/low.mp3');
  assert.equal(data.lyrics, '');
  restores.pop()();

  stubGateway({ status: 2 });
  assert.equal(await getKugouPlayData(HASH, '958706'), null);
});

// ---- 路由 ----
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

test('/kugou/search 校验 400：缺 keyword/超长/重参对象', async () => {
  const r1 = await get('/kugou/search');
  assert.equal(r1.status, 400);

  const r2 = await get(`/kugou/search?keyword=${'歌'.repeat(101)}`);
  assert.equal(r2.status, 400);

  const r3 = await get('/kugou/search?keyword[x]=1');
  assert.equal(r3.status, 400);
});

test('/kugou/search 成功形状 {code,message,data}', async () => {
  stubAxiosGet(async () => ({ data: searchPayload([searchItem()]) }));
  const r = await get('/kugou/search?keyword=安静&limit=20');
  assert.equal(r.status, 200);
  assert.equal(r.body.code, 200);
  assert.equal(r.body.message, 'success');
  assert.equal(r.body.data.length, 1);
  assert.equal(r.body.data[0].id, HASH);
});

test('/kugou/url 校验 400：缺 hash/非法 hash/非法 albumId/非法 level', async () => {
  const r1 = await get('/kugou/url');
  assert.equal(r1.status, 400);

  const r2 = await get('/kugou/url?hash=xyz');
  assert.equal(r2.status, 400);

  const r3 = await get(`/kugou/url?hash=${HASH}&albumId=abc`);
  assert.equal(r3.status, 400);

  const r4 = await get(`/kugou/url?hash=${HASH}&level=lossless`);
  assert.equal(r4.status, 400);
});

test('/kugou/url 成功形状与缓存隔离：仅非 null 入缓存', async () => {
  let calls = 0;
  stubAxiosGet(async (url) => {
    if (String(url).includes('gateway.kugou.com')) {
      calls++;
      return { data: v5Payload() };
    }
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  const r1 = await get(`/kugou/url?hash=${HASH}&albumId=958706&level=low`);
  assert.equal(r1.status, 200);
  assert.deepEqual(r1.body, { code: 200, message: 'success', data: [{ id: HASH, url: 'https://cdn-a.kugou.com/low.mp3' }] });
  assert.notEqual(urlCache.get(`kugou_url:${HASH}:low`), undefined);
  assert.equal(urlCache.get(`qq_url:${HASH}`), undefined);

  const r2 = await get(`/kugou/url?hash=${HASH}&albumId=958706&level=low`);
  assert.equal(r2.body.message, 'success (cached)');
  assert.equal(calls, 1);
});

test('/kugou/url null 不缓存：两次都回源', async () => {
  let calls = 0;
  stubAxiosGet(async (url) => {
    if (String(url).includes('gateway.kugou.com')) {
      calls++;
      return { data: { status: 2 } };
    }
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  const r1 = await get(`/kugou/url?hash=${HASH}&albumId=958706&level=low`);
  assert.equal(r1.status, 200);
  assert.deepEqual(r1.body, { code: 200, message: 'success', data: [{ id: HASH, url: null }] });
  const r2 = await get(`/kugou/url?hash=${HASH}&albumId=958706&level=low`);
  assert.equal(r2.body.data[0].url, null);
  assert.equal(urlCache.get(`kugou_url:${HASH}:low`), undefined);
  assert.equal(calls, 2);
});

test('/kugou/url high/super 直接 null 不请求上游', async () => {
  let calls = 0;
  stubAxiosGet(async () => {
    calls++;
    return { data: v5Payload() };
  });
  for (const level of ['high', 'super']) {
    const r = await get(`/kugou/url?hash=${HASH}&albumId=958706&level=${level}`);
    assert.equal(r.status, 200);
    assert.deepEqual(r.body, { code: 200, message: 'success', data: [{ id: HASH, url: null }] });
  }
  assert.equal(calls, 0);
  assert.equal(urlCache.get(`kugou_url:${HASH}:high`), undefined);
});

test('/kugou/url level 按档隔离缓存', async () => {
  stubAxiosGet(async (url, opts) => {
    if (String(url).includes('gateway.kugou.com')) {
      if (opts.params.quality === '320') return { data: { status: 2 } };
      return { data: v5Payload() };
    }
    return { status: 206, headers: {}, data: Buffer.from([0, 1]) };
  });
  await get(`/kugou/url?hash=${HASH}&albumId=958706&level=low`);
  await get(`/kugou/url?hash=${HASH}&albumId=958706&level=standard`);
  assert.notEqual(urlCache.get(`kugou_url:${HASH}:low`), undefined);
  assert.notEqual(urlCache.get(`kugou_url:${HASH}:standard`), undefined);
});

test('/kugou/lyric 校验 400 与 404 形状(v5 无内联歌词)', async () => {
  const r1 = await get('/kugou/lyric');
  assert.equal(r1.status, 400);

  const r2 = await get('/kugou/lyric?hash=xyz');
  assert.equal(r2.status, 400);

  stubGateway(v5Payload());
  const r3 = await get(`/kugou/lyric?hash=${HASH}&albumId=958706`);
  assert.equal(r3.status, 404);
  assert.equal(r3.body.message, '未找到歌词');
  restores.pop()();

  stubGateway({ status: 2 });
  const r4 = await get(`/kugou/lyric?hash=${HASH}&albumId=958706`);
  assert.equal(r4.status, 404);
  assert.equal(r4.body.message, '未找到歌词');
});
