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
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const { searchCache, urlCache } = require('../src/search');
const registerRoutes = require('../src/routes');

const CONTRACT = JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', '..', 'contract', 'gateway.contract.json'), 'utf8')
);

const restores = [];
afterEach(() => {
  while (restores.length) restores.pop()();
  searchCache.clear();
  urlCache.clear();
});

function stubAxiosGet(impl) {
  const orig = axios.get;
  axios.get = impl;
  restores.push(() => { axios.get = orig; });
}

/** 按契约里的类型语法校验（string / string? / [item] / 对象逐 key）。 */
function assertShape(value, spec, at) {
  if (spec === 'any' || spec === 'any?') return;
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
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      // ? 同时表示"可缺省"：上游对非试听曲会省略 freeTrialInfo，后端 get() 得 null
      if (typeof child === 'string' && child.endsWith('?')) continue;
      assert.fail(`${at}.${key} 缺失`);
    }
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

function neteaseSearchPayload() {
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

function qqSearchPayload() {
  return {
    data: {
      search: {
        data: {
          body: {
            song: {
              list: [{
                mid: '004abcXYZ1', name: '晴天', singer: [{ name: '周杰伦' }],
                album: { mid: '002eFUFQ2XYZOP', name: '叶惠美' },
                interval: 240, pay: { pay_play: 0 },
              }],
            },
          },
        },
      },
    },
  };
}

test('/search 契约：data{total,page,size,list[]}，缺 keyword 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const origCloud = NeteaseCloudMusicApi.cloudsearch;
  const origSearch = NeteaseCloudMusicApi.search;
  NeteaseCloudMusicApi.cloudsearch = async () => neteaseSearchPayload();
  NeteaseCloudMusicApi.search = async () => neteaseSearchPayload();
  restores.push(() => {
    NeteaseCloudMusicApi.cloudsearch = origCloud;
    NeteaseCloudMusicApi.search = origSearch;
  });
  stubAxiosGet(async () => qqSearchPayload());

  const ep = '/search';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(
    `${ep} success`,
    await fetch(`${base}${ep}?keyword=${encodeURIComponent('契约搜索唯一词')}`),
    ok.success
  );
  assert.ok(body.data.list.length >= 1, '应至少合并出一条结果');
  assert.ok(['netease', 'qq'].includes(body.data.list[0].platform));

  const [err] = ok.errors;
  await expectContract(`${ep} missing keyword`, await fetch(`${base}${ep}`), err);
});

test('/song/url/v1 契约：code/data[{url,freeTrialInfo,time}]，缺参 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const orig = NeteaseCloudMusicApi.song_url_v1;
  NeteaseCloudMusicApi.song_url_v1 = async () => ({
    body: {
      code: 200,
      data: [{ id: 386038, url: 'https://m701.music.126.net/x.mp3', time: 240000, freeTrialInfo: null }],
    },
  });
  restores.push(() => { NeteaseCloudMusicApi.song_url_v1 = orig; });

  const ep = '/song/url/v1';
  const ok = CONTRACT.endpoints[ep];
  await expectContract(`${ep} success`, await fetch(`${base}${ep}?id=386038&level=exhigh`), ok.success);

  // 可选字段缺省也要通过：上游对非试听曲会省略 freeTrialInfo/time
  NeteaseCloudMusicApi.song_url_v1 = async () => ({ body: { code: 200, data: [{ url: null }] } });
  await expectContract(`${ep} optional fields absent`, await fetch(`${base}${ep}?id=386039`), ok.success);

  const [err] = ok.errors;
  await expectContract(`${ep} missing id`, await fetch(`${base}${ep}`), err);
});

test('/qq/search 契约：data[歌曲字段]，缺 keyword 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  stubAxiosGet(async () => qqSearchPayload());

  const ep = '/qq/search';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(`${ep} success`, await fetch(`${base}${ep}?keyword=晴天&limit=2`), ok.success);
  assert.ok(body.data.length >= 1);
  assert.equal(body.data[0].name, '晴天');

  const [err] = ok.errors;
  await expectContract(`${ep} missing keyword`, await fetch(`${base}${ep}`), err);
});

test('/netease/search 契约：data[歌曲字段]，缺 keyword 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const origCloud = NeteaseCloudMusicApi.cloudsearch;
  const origSearch = NeteaseCloudMusicApi.search;
  NeteaseCloudMusicApi.cloudsearch = async () => neteaseSearchPayload();
  NeteaseCloudMusicApi.search = async () => neteaseSearchPayload();
  restores.push(() => {
    NeteaseCloudMusicApi.cloudsearch = origCloud;
    NeteaseCloudMusicApi.search = origSearch;
  });

  const ep = '/netease/search';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(`${ep} success`, await fetch(`${base}${ep}?keyword=晴天&limit=2`), ok.success);
  assert.ok(body.data.length >= 1);

  const [err] = ok.errors;
  await expectContract(`${ep} missing keyword`, await fetch(`${base}${ep}`), err);
});

// ==================== 独立平台：咪咕 / 酷狗 / B站 / 网易云歌词 / 推荐歌单 ====================
// 上游依赖（签名/票据/WBI）全部走 stub；stub 未命中即抛错，确保测试绝不触网。

/** 按 URL 子串路由的多路上游 stub：未命中直接抛错（测试若偷偷连真网会红）。 */
function stubUpstream(routes) {
  stubAxiosGet(async (url, opts) => {
    const u = String(url);
    for (const [needle, resp] of routes) {
      if (u.includes(needle)) return typeof resp === 'function' ? resp(u, opts) : resp;
    }
    throw new Error(`unexpected upstream call: ${u}`);
  });
}

const MIGU_CONTENT_ID = '600907000001234567';

function miguSearchPayload() {
  return {
    data: {
      code: '000000',
      songResultData: {
        result: [{
          contentId: MIGU_CONTENT_ID,
          copyrightId: '600000000009999999',
          name: '晴天',
          singers: [{ name: '周杰伦' }],
          imgItems: [{ img: 'https://cdn.migu.cn/cover.jpg' }],
          resourceType: '2',
        }],
      },
    },
  };
}

test('/migu/search 契约：data[歌曲字段]，缺 keyword 400，上游失败降级空数组', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  let upstreamDown = false;
  stubUpstream([['migu', () => {
    if (upstreamDown) throw new Error('migu upstream down');
    return miguSearchPayload();
  }]]);

  const ep = '/migu/search';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(
    `${ep} success`,
    await fetch(`${base}${ep}?keyword=${encodeURIComponent('晴天')}&limit=2`),
    ok.success
  );
  assert.equal(body.data[0].id, MIGU_CONTENT_ID);
  assert.equal(body.data[0].name, '晴天');
  assert.equal(body.data[0].duration, 0, '咪咕搜索无单曲时长，实现固定 0');

  // 上游异常：路由仍回 200 + data:[]（后端靠空数组降级），形状与成功契约一致
  upstreamDown = true;
  const degraded = await expectContract(
    `${ep} upstream failure`,
    await fetch(`${base}${ep}?keyword=${encodeURIComponent('晴天')}`),
    ok.success
  );
  assert.deepEqual(degraded.data, []);

  const [err] = ok.errors;
  await expectContract(`${ep} missing keyword`, await fetch(`${base}${ep}`), err);
});

test('/migu/url 契约：data[{id,url}]，302→签名 URL、无版权→url null，缺参 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const signedUrl = 'https://cdn.migu.cn/audio/signed.mp3?token=FAKE';
  let paid = false;
  stubUpstream([['listenSong', () => (paid
    ? { status: 200, data: { code: '200000' } }
    : { status: 302, headers: { location: signedUrl } })]]);

  const ep = '/migu/url';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(
    `${ep} success`,
    await fetch(`${base}${ep}?id=${MIGU_CONTENT_ID}&copyrightId=600000000009999999`),
    ok.success
  );
  assert.equal(body.data[0].id, MIGU_CONTENT_ID);
  assert.equal(body.data[0].url, signedUrl);
  assert.equal(body.message, undefined, '该端点无 message 字段（后端不读）');

  // 付费/无版权：上游 200000 → url:null（后端据此走降级）
  paid = true;
  const noAuth = await expectContract(`${ep} paid`, await fetch(`${base}${ep}?id=${MIGU_CONTENT_ID}`), ok.success);
  assert.equal(noAuth.data[0].url, null);

  const [err] = ok.errors;
  await expectContract(`${ep} missing id`, await fetch(`${base}${ep}`), err);
});

const KUGOU_HASH = 'a1b2c3d4e5f60718293a4b5c6d7e8f90';

function kugouSearchPayload() {
  return {
    data: {
      status: 1,
      data: {
        lists: [{
          FileHash: KUGOU_HASH,
          OriSongName: '<em>晴天</em>',
          SingerName: '周杰伦',
          AlbumName: '叶惠美',
          AlbumImage: 'https://imge.kugou.com/stdmusic/{size}/cover.jpg',
          Duration: 240,
          Privilege: 0,
          AlbumID: '123456',
          MixSongID: '789',
          HQFileHash: '',
          SQFileHash: '',
        }],
      },
    },
  };
}

test('/kugou/search 契约：data[歌曲字段]，缺/超长 keyword 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  stubUpstream([['kugou', kugouSearchPayload()]]);

  const ep = '/kugou/search';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(
    `${ep} success`,
    await fetch(`${base}${ep}?keyword=${encodeURIComponent('晴天')}&limit=2`),
    ok.success
  );
  assert.equal(body.data[0].id, KUGOU_HASH);
  assert.equal(body.data[0].name, '晴天', 'em 标签应被剥离');
  assert.equal(body.data[0].duration, 240000, '秒 → 毫秒');
  assert.equal(body.data[0].cover, 'https://imge.kugou.com/stdmusic/400/cover.jpg');

  const [err] = ok.errors;
  await expectContract(`${ep} missing keyword`, await fetch(`${base}${ep}`), err);
  await expectContract(`${ep} keyword too long`, await fetch(`${base}${ep}?keyword=${'x'.repeat(101)}`), err);
});

test('/kugou/url 契约：data[{id,url}]，探活成功回链、VIP 档直接 null，缺参 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const cdnUrl = 'https://fs.youthandroid2.kugou.com/2026/song.mp3';
  stubUpstream([
    ['gateway.kugou.com', { data: { status: 1, url: [cdnUrl], backupUrl: [] } }],
    ['fs.youthandroid2.kugou.com', { status: 206 }],
  ]);

  const ep = '/kugou/url';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(`${ep} success`, await fetch(`${base}${ep}?hash=${KUGOU_HASH}`), ok.success);
  assert.equal(body.data[0].id, KUGOU_HASH);
  assert.equal(body.data[0].url, cdnUrl);

  // high/super 为 VIP 档：phase 1 直接 url:null，不请求上游（stub 未命中会抛错 → 若真发请求这里会红）
  const vip = await expectContract(
    `${ep} vip tier`,
    await fetch(`${base}${ep}?hash=${KUGOU_HASH}&level=high`),
    ok.success
  );
  assert.equal(vip.data[0].url, null);

  const [err] = ok.errors;
  await expectContract(`${ep} missing hash`, await fetch(`${base}${ep}`), err);
  await expectContract(`${ep} invalid hash`, await fetch(`${base}${ep}?hash=not-a-hash`), err);
});

const BILI_BVID = 'BV1De411p77r';

function biliSpiPayload() {
  return { data: { code: 0, data: { b_3: 'FAKE-B3', b_4: 'FAKE-B4' } } };
}

function biliNavPayload() {
  return {
    data: {
      code: 0,
      data: {
        wbi_img: {
          img_url: 'https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png',
          sub_url: 'https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png',
        },
      },
    },
  };
}

function biliSearchPayload() {
  return {
    data: {
      code: 0,
      data: {
        result: [{
          bvid: BILI_BVID,
          aid: 123456,
          title: '晴天 - 周杰伦',
          author: '周杰伦',
          pic: '//i0.hdslb.com/bfs/archive/cover.jpg',
          duration: '4:00',
          play: 123456,
          danmaku: 789,
          typename: '音乐',
        }],
      },
    },
  };
}

test('/bili/search 契约：data[歌曲字段 + _raw 热度]，缺/超长 keyword 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  stubUpstream([
    ['/x/frontend/finger/spi', biliSpiPayload()],
    ['search/type', biliSearchPayload()],
  ]);

  const ep = '/bili/search';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(
    `${ep} success`,
    await fetch(`${base}${ep}?keyword=${encodeURIComponent('晴天')}&limit=2`),
    ok.success
  );
  assert.equal(body.data[0].id, BILI_BVID);
  assert.equal(body.data[0].cover, 'https://i0.hdslb.com/bfs/archive/cover.jpg', '// → https:');
  assert.equal(body.data[0].duration, 240000, 'mm:ss → 毫秒');
  // 后端 SongDtoParser 读 _raw.play/_raw.danmaku 参与热度加权
  assert.equal(body.data[0]._raw.play, 123456);
  assert.equal(body.data[0]._raw.danmaku, 789);

  const [err] = ok.errors;
  await expectContract(`${ep} missing keyword`, await fetch(`${base}${ep}`), err);
  await expectContract(`${ep} keyword too long`, await fetch(`${base}${ep}?keyword=${'x'.repeat(101)}`), err);
});

test('/bili/url 契约：data[{id,url}]，DASH 取最高带宽、风控 -412 降级 null，缺参 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const low = 'https://upos-sz-mirrorcos.bilivideo.com/low.m4s';
  const high = 'https://upos-sz-mirrorcos.bilivideo.com/high.m4s';
  let riskControl = false;
  stubUpstream([
    ['/x/player/wbi/playurl', () => (riskControl
      ? { data: { code: -412, message: '请求被拦截' } }
      : {
        data: {
          code: 0,
          data: { dash: { audio: [{ baseUrl: low, bandwidth: 132000 }, { baseUrl: high, bandwidth: 192000 }] } },
        },
      })],
    ['/x/web-interface/nav', biliNavPayload()],
    ['/x/frontend/finger/spi', biliSpiPayload()],
  ]);

  const ep = '/bili/url';
  const ok = CONTRACT.endpoints[ep];
  const trackId = `${BILI_BVID}|12345`; // 复合 id：自带 cid，跳过 pagelist
  const body = await expectContract(
    `${ep} success`,
    await fetch(`${base}${ep}?id=${encodeURIComponent(trackId)}`),
    ok.success
  );
  assert.equal(body.data[0].id, trackId);
  assert.equal(body.data[0].url, high, '应取 bandwidth 最大伴音轨');

  // 风控 -412：刷新票据重试一次仍失败 → url:null（路由绝不 500）。
  // 换一个 cid：成功结果已进 urlCache（同 id 会直接命中缓存，绕开降级路径）。
  riskControl = true;
  const riskTrackId = `${BILI_BVID}|99999`;
  const degraded = await expectContract(
    `${ep} risk control`,
    await fetch(`${base}${ep}?id=${encodeURIComponent(riskTrackId)}`),
    ok.success
  );
  assert.equal(degraded.data[0].url, null);

  const [err] = ok.errors;
  await expectContract(`${ep} missing id`, await fetch(`${base}${ep}`), err);
  await expectContract(`${ep} invalid id`, await fetch(`${base}${ep}?id=not-a-bvid`), err);
});

test('/lyric 契约：code/lrc.lyric（网易云直通），缺参 400，上游异常 500', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const lrc = '[00:01.00]晴天\n';
  const orig = NeteaseCloudMusicApi.lyric;
  NeteaseCloudMusicApi.lyric = async () => ({
    body: { code: 200, lrc: { version: 1, lyric: lrc }, tlyric: { version: 0, lyric: '' } },
  });
  restores.push(() => { NeteaseCloudMusicApi.lyric = orig; });

  const ep = '/lyric';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(`${ep} success`, await fetch(`${base}${ep}?id=386038`), ok.success);
  assert.equal(body.lrc.lyric, lrc);

  const bad = ok.errors.find((e) => e.status === 400);
  await expectContract(`${ep} missing id`, await fetch(`${base}${ep}`), bad);
  await expectContract(`${ep} invalid id`, await fetch(`${base}${ep}?id=abc`), bad);

  // 上游抛错：稳定 500 信封（后端 extractLyric 得 null → 空歌词，不崩）
  NeteaseCloudMusicApi.lyric = async () => { throw new Error('netease lyric down'); };
  const err500 = ok.errors.find((e) => e.status === 500);
  await expectContract(`${ep} upstream error`, await fetch(`${base}${ep}?id=386038`), err500);
});

const KUGOU_LYRIC_LRC = '[id:$00000000]\n[ar:Missy]\n[ti:晴天 (DJ降调0.9x版)]\n[by:深]\n[00:01.00]故事的小黄花\n';

/** 酷狗歌词第一步（krcs/search）响应：UGC 排前面，官方版在后面（验证官方优先）。 */
function kugouLyricSearchPayload() {
  return {
    data: {
      status: 200,
      candidates: [
        {
          id: '900001', accesskey: 'UGCACCESSKEY0001', song: '晴天(DJ版)', singer: 'DJ小明',
          duration: 238, product_from: 'ugc',
        },
        {
          id: '741307774', accesskey: '8D735CF26880D11E9F70B8953F206FBE', song: '晴天 (DJ降调0.9x版)',
          singer: 'Missy', duration: 240, product_from: 'kugou',
        },
      ],
    },
  };
}

/** 酷狗歌词第二步（lyrics/download）响应：content 是 base64 编码的 LRC。 */
function kugouLyricDownloadPayload(lrc = KUGOU_LYRIC_LRC) {
  return { data: { status: 200, fmt: 'lrc', content: Buffer.from(lrc, 'utf8').toString('base64') } };
}

test('/kugou/lyric 契约：code/data.lyric（两步接口解码 base64），无候选/上游异常 404，缺参 400', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const seenIds = [];
  stubUpstream([
    ['krcs.kugou.com/search', kugouLyricSearchPayload()],
    ['lyrics.kugou.com/download', (url, opts) => {
      seenIds.push(String(opts.params.id));
      return kugouLyricDownloadPayload();
    }],
  ]);

  const ep = '/kugou/lyric';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(
    `${ep} success`,
    await fetch(`${base}${ep}?hash=${KUGOU_HASH}&albumId=958706&duration=240000`),
    ok.success
  );
  assert.equal(body.data.lyric, KUGOU_LYRIC_LRC, 'content 需 base64 解码');
  assert.equal(body.data.song, '晴天 (DJ降调0.9x版)', '应优先官方版而非 UGC');
  assert.equal(body.data.singer, 'Missy');
  assert.deepEqual(seenIds, ['741307774'], '下载步应带选中候选的 id');

  const notFound = ok.errors.find((e) => e.status === 404);
  const bad = ok.errors.find((e) => e.status === 400);

  // 上游无候选（candidates: []）→ 404，不是 500
  stubUpstream([['krcs.kugou.com/search', { data: { status: 200, candidates: [] } }]]);
  const empty = await expectContract(`${ep} no candidates`, await fetch(`${base}${ep}?hash=${KUGOU_HASH}`), notFound);
  assert.equal(empty.code, 404);

  // 上游抛错（stub 未命中）→ 404，不是 500
  stubUpstream([['krcs.kugou.com/search', () => { throw new Error('krcs down'); }]]);
  const down = await expectContract(`${ep} upstream error`, await fetch(`${base}${ep}?hash=${KUGOU_HASH}`), notFound);
  assert.equal(down.code, 404);

  await expectContract(`${ep} missing hash`, await fetch(`${base}${ep}`), bad);
  await expectContract(`${ep} invalid hash`, await fetch(`${base}${ep}?hash=not-a-hash`), bad);
  await expectContract(
    `${ep} invalid albumId`,
    await fetch(`${base}${ep}?hash=${KUGOU_HASH}&albumId=abc`),
    bad
  );
});

test('/personalized 契约：result[{id,name,picUrl,playCount}]，上游异常 500', async (t) => {
  const { server, base } = await startApp();
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const orig = NeteaseCloudMusicApi.personalized;
  NeteaseCloudMusicApi.personalized = async () => ({
    body: {
      code: 200,
      result: [{
        id: 123456,
        name: '华语流行精选',
        picUrl: 'https://p1.music.126.net/cover.jpg',
        copywriter: '精选歌单',
        playCount: 100000,
      }],
    },
  });
  restores.push(() => { NeteaseCloudMusicApi.personalized = orig; });

  const ep = '/personalized';
  const ok = CONTRACT.endpoints[ep];
  const body = await expectContract(`${ep} success`, await fetch(`${base}${ep}?limit=2`), ok.success);
  assert.equal(body.result[0].name, '华语流行精选');
  assert.equal(typeof body.result[0].playCount, 'number');

  NeteaseCloudMusicApi.personalized = async () => { throw new Error('netease personalized down'); };
  const [err] = ok.errors;
  await expectContract(`${ep} upstream error`, await fetch(`${base}${ep}`), err);
});
