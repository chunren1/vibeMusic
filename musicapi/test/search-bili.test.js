/**
 * B站网关路由测试（guest 匿名）
 *
 * 运行: node --test --test-force-exit test/search-bili.test.js（无需启动 server，无需真实网络，纯 stub）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 * 覆盖：searchBili 归一化/BV 过滤、parseBiliDuration、getBiliUrl(pagelist→cid→WBI playurl、
 *       复合 id 跳过 view、-412 刷新票据重试、无链 null)、/bili/* 两路由
 *       400 校验、url:null 不缓存、缓存隔离(bili_url:{id})。
 */
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const searchMod = require('../src/search');
const { searchBili, mapBiliVideo, parseBiliTitle, parseBiliDuration, getBiliUrl, getBiliView, isBiliTrackId, isBiliSingle, BILI_SINGLE_MAX_DURATION_MS, BILI_MUSIC_TID, urlCache, searchCache } = searchMod;

const BVID = 'BV1De411p77r';
const AID = 243082173;
const CID = 171776208;

function searchPayload() {
  return {
    code: 0,
    message: 'ok',
    data: {
      result: [
        {
          type: 'video',
          aid: AID,
          bvid: BVID,
          title: '梦然-《<em class="keyword">少年</em>》官方版',
          author: '大橘爱吃猫',
          mid: 178932626,
          typename: 'MV',
          pic: '//i0.hdslb.com/bfs/archive/e25120857a6298d1d4b9e64a805c023b5143c8ff.jpg',
          play: 1037655,
          danmaku: 521,
          duration: '4:18',
        },
        {
          type: 'video',
          aid: 2,
          bvid: 'not-a-bvid!!',
          title: '坏条目',
          author: 'x',
          pic: '',
          play: 0,
          duration: '1:00',
        },
      ],
    },
  };
}

function viewPayload() {
  return { code: 0, message: 'OK', data: [{ cid: CID, page: 1, part: 'P1', duration: 246 }] };
}

function playurlPayload(over = {}) {
  return Object.assign({
    code: 0,
    message: 'ok',
    data: {
      quality: 32,
      format: 'mp4',
      timelength: 258000,
      dash: {
        audio: [
          { id: 30216, baseUrl: 'https://xy.bilivideo.com/64k.m4s', bandwidth: 64000 },
          { id: 30232, baseUrl: 'https://xy.bilivideo.com/132k.m4s', bandwidth: 132000 },
          { id: 30280, baseUrl: 'https://xy.bilivideo.com/192k.m4s', bandwidth: 192000 },
        ],
      },
    },
  }, over);
}

const SPI_PAYLOAD = { code: 0, message: 'ok', data: { b_3: 'B3XXXX', b_4: 'B4YYYY' } };
const NAV_PAYLOAD = {
  code: 0,
  data: {
    wbi_img: {
      img_url: 'https://i0.hdslb.com/bfs/wbi/7cd084941338484aae6a3e4e17199b7f.png',
      sub_url: 'https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png',
    },
  },
};

/** 按 URL 分发的多路 stub：spi/nav/search/view/playurl 各回各的包。 */
function stubBili(over = {}) {
  const orig = axios.get;
  const calls = [];
  axios.get = async (url, opts) => {
    calls.push({ url, params: opts && opts.params, headers: opts && opts.headers });
    if (url.includes('/finger/spi')) return { data: SPI_PAYLOAD };
    if (url.includes('/web-interface/nav')) return { data: NAV_PAYLOAD };
    if (url.includes('/search/type')) {
      if (over.search) return over.search(url, opts);
      return { data: searchPayload() };
    }
    if (url.includes('/player/pagelist')) {
      if (over.view) return over.view(url, opts);
      return { data: viewPayload() };
    }
    if (url.includes('/player/wbi/playurl')) {
      if (over.playurl) return over.playurl(url, opts);
      return { data: playurlPayload() };
    }
    throw new Error(`unexpected url ${url}`);
  };
  return { restore: () => { axios.get = orig; }, calls };
}

afterEach(() => {
  searchCache.clear();
  urlCache.clear();
});

// ---- ID 方案 ----
test('isBiliTrackId 只接受纯 bvid 或 bvid|cid，拒绝裸 aid/非法形', () => {
  assert.equal(isBiliTrackId(BVID), true);
  assert.equal(isBiliTrackId(`${BVID}|${CID}`), true);
  assert.equal(isBiliTrackId(String(AID)), false);
  assert.equal(isBiliTrackId('004abcXYZ'), false);
  assert.equal(isBiliTrackId(''), false);
  assert.equal(isBiliTrackId(null), false);
  assert.equal(isBiliTrackId(`${BVID}|abc`), false);
  assert.equal(isBiliTrackId(`${BVID}|1|2`), false);
});

test('parseBiliDuration 解析 M:SS/H:MM:SS/秒数，非法回 0', () => {
  assert.equal(parseBiliDuration('4:18'), 258000);
  assert.equal(parseBiliDuration('1:02:03'), 3723000);
  assert.equal(parseBiliDuration(258), 258000);
  assert.equal(parseBiliDuration('258'), 258000);
  assert.equal(parseBiliDuration(null), 0);
  assert.equal(parseBiliDuration('bad'), 0);
});

// ---- 搜索 ----
test('searchBili 归一化形状：BV 过滤/em 去标签/封面 https/时长毫秒', async () => {
  const { restore, calls } = stubBili();
  try {
    const songs = await searchBili('少年', 20);
    assert.equal(songs.length, 1);
    const searchCall = calls.find((c) => c.url.includes('/search/type'));
    assert.equal(searchCall.params.search_type, 'video');
    assert.equal(searchCall.params.keyword, '少年');
    assert.equal(searchCall.params.page_size, 20);
    assert.ok(String(searchCall.headers.Referer).includes('bilibili.com'));
    assert.ok(String(searchCall.headers['User-Agent']).length > 0);
    assert.ok(String(searchCall.headers.Cookie).includes('buvid3=B3XXXX'));
    assert.ok(String(searchCall.headers.Cookie).includes('buvid4=B4YYYY'));

    const [s] = songs;
    assert.equal(s.id, BVID);
    // 标题解析：'梦然-《少年》官方版' → 歌名=少年、歌手=梦然（不再是 UP 主）
    assert.equal(s.name, '少年');
    assert.equal(s.artists, '梦然');
    assert.equal(s.album, '');
    assert.equal(s.cover, 'https://i0.hdslb.com/bfs/archive/e25120857a6298d1d4b9e64a805c023b5143c8ff.jpg');
    assert.equal(s.duration, 258000);
    assert.equal(s.vip, false);
    assert.equal(s._raw.aid, String(AID));
    assert.equal(s._raw.bvid, BVID);
    assert.equal(s._raw.play, 1037655);
    assert.equal(s._raw.danmaku, 521);
    assert.deepEqual(Object.keys(s).sort(), ['_raw', 'album', 'artists', 'cover', 'duration', 'id', 'name', 'vip']);
  } finally {
    restore();
  }
});

test('searchBili 非 0 状态/空结果/异常均返回 []', async () => {
  let h = stubBili({ search: async () => ({ data: { code: -400, message: 'req err' } }) });
  assert.deepEqual(await searchBili('少年', 10), []);
  h.restore();

  h = stubBili({ search: async () => ({ data: { code: 0, data: { result: [] } } }) });
  assert.deepEqual(await searchBili('少年', 10), []);
  h.restore();

  const orig = axios.get;
  axios.get = async () => { throw new Error('socket hang up'); };
  try {
    assert.deepEqual(await searchBili('少年', 10), []);
  } finally {
    axios.get = orig;
  }
});

test('mapBiliVideo 容忍最小条目', () => {
  const out = mapBiliVideo({ bvid: BVID });
  assert.equal(out.id, BVID);
  assert.equal(out.name, '');
  assert.equal(out.artists, '');
  assert.equal(out.duration, 0);
  assert.equal(out.vip, false);
});

test('mapBiliVideo 封面归一：// 补 https、http 升级、缺 pic 置空(不断言上游恒有)', () => {
  assert.equal(
    mapBiliVideo({ bvid: BVID, title: '少年', author: 'up', pic: '//i0.hdslb.com/x.jpg' }).cover,
    'https://i0.hdslb.com/x.jpg');
  assert.equal(
    mapBiliVideo({ bvid: BVID, title: '少年', author: 'up', pic: 'http://i0.hdslb.com/x.jpg' }).cover,
    'https://i0.hdslb.com/x.jpg');
  assert.equal(mapBiliVideo({ bvid: BVID, title: '少年', author: 'up' }).cover, '');
});

test('mapBiliVideo _raw 透传播放量/弹幕数(供后端播放量门控)，缺失置 0', () => {
  const full = mapBiliVideo({ bvid: BVID, title: '少年', author: 'up', play: 2955739, danmaku: 17317 });
  assert.equal(full._raw.play, 2955739);
  assert.equal(full._raw.danmaku, 17317);
  const empty = mapBiliVideo({ bvid: BVID, title: '少年', author: 'up' });
  assert.equal(empty._raw.play, 0);
  assert.equal(empty._raw.danmaku, 0);
});

// ---- 标题解析 ----
test('parseBiliTitle《》优先：歌手-《歌名》/歌手《歌名》/全角破折号', () => {
  assert.deepEqual(parseBiliTitle('梦然-《少年》官方版', '大橘爱吃猫'), { name: '少年', artists: '梦然' });
  assert.deepEqual(parseBiliTitle('周杰伦——《晴天》', '寻意zzzz'), { name: '晴天', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('【杜比音效】周杰伦《晴天》4K', 'up'), { name: '晴天', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('《晴天》- 周杰伦', 'up'), { name: '晴天', artists: '周杰伦' });
  // live 实录：【Hi-Res】｜《晴天》- 周杰伦 -'…' → 邻位取歌手
  assert.deepEqual(
    parseBiliTitle('【Hi-Res无损音质】｜《晴天》- 周杰伦 -‘故事的小黄花’', 'VV音乐局'),
    { name: '晴天', artists: '周杰伦' });
});

test('parseBiliTitle 排序：歌名-歌手翻唱/歌手-歌名/版本词倒装', () => {
  assert.deepEqual(parseBiliTitle('晴天 - 周杰伦翻唱', '某UP'), { name: '晴天', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('【翻唱】阿梨 - 少年 (cover)', '阿梨本人'), { name: '少年', artists: '阿梨' });
  assert.deepEqual(parseBiliTitle('晴天完整版 - 周杰伦', 'up'), { name: '晴天', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('周杰伦翻唱 - 晴天', 'up'), { name: '晴天', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('周杰伦 - 稻香 (Live版)', '搬运'), { name: '稻香', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('【Live】周杰伦 - 夜曲', 'up'), { name: '夜曲', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('周杰伦 - 夜曲 live', 'up'), { name: '夜曲', artists: '周杰伦' });
  assert.deepEqual(parseBiliTitle('【4K修复】周杰伦 - 晴天MV 2160P修复版', 'up'), { name: '晴天', artists: '周杰伦' });
});

test('parseBiliTitle OST：剧名书名号剥除，真歌名保留', () => {
  assert.deepEqual(
    parseBiliTitle('林俊杰 - 她说 (电视剧《北上》主题曲)', 'up'),
    { name: '她说', artists: '林俊杰' });
  assert.deepEqual(
    parseBiliTitle('《匆匆那年》- 电影《匆匆那年》主题曲', '影视音乐'),
    { name: '匆匆那年', artists: '影视音乐' });
});

test('parseBiliTitle 歧义回退：多《》/指示词残留时保歌名、歌手回退 UP 主，绝不空', () => {
  // 广告混剪双《》→整体回退(由后端播放量门控沉底)
  const ad = parseBiliTitle('【疑似《那天下雨了》MV】周杰伦vivo最新广告《手握彩虹，遇见晴天》', 'up');
  assert.equal(ad.artists, 'up');
  assert.ok(ad.name.length > 0);
  // '这才是'指示词拒识→歌手回退 UP 主，歌名保留
  assert.deepEqual(parseBiliTitle('这才是《晴天》原版MV！', '杨某人'), { name: '晴天', artists: '杨某人' });
  // 歌词引用尾巴过长→回退 UP 主
  assert.deepEqual(parseBiliTitle('循环歌曲《晴天》|"刮风这天我试过握着你手', 'Echo'), { name: '晴天', artists: 'Echo' });
  // 单段版本词照剥
  assert.deepEqual(parseBiliTitle('少年完整版', '大橘爱吃猫'), { name: '少年', artists: '大橘爱吃猫' });
  // 空输入绝不抛，name/artist 均为 ''
  assert.deepEqual(parseBiliTitle('', ''), { name: '', artists: '' });
  assert.deepEqual(parseBiliTitle(null, null), { name: '', artists: '' });
});

// ---- 单曲形态过滤 ----
test('单曲过滤常量：阈值 480s / 音乐区 tid=3', () => {
  assert.equal(BILI_SINGLE_MAX_DURATION_MS, 480000);
  assert.equal(BILI_MUSIC_TID, 3);
});

test('searchBili 发 tids=音乐区(3)源头收窄', async () => {
  const { restore, calls } = stubBili();
  try {
    await searchBili('少年', 20);
    const searchCall = calls.find((c) => c.url.includes('/search/type'));
    assert.equal(searchCall.params.tids, BILI_MUSIC_TID);
  } finally {
    restore();
  }
});

function singleRow(bvid, title, duration) {
  return { type: 'video', aid: 1, bvid, title, author: 'up', typename: '音乐', pic: '', play: 100, duration };
}

test('searchBili 过滤长视频(>8min)与合集标题，保留正常单曲', async () => {
  const h = stubBili({
    search: async () => ({
      data: {
        code: 0,
        data: {
          result: [
            singleRow('BV1aaaaaaaaaa', '正常单曲-少年', '4:18'),
            singleRow('BV1bbbbbbbbbb', '十五分钟串烧合集', '15:00'),
            singleRow('BV1cccccccccc', '周杰伦串烧三首', '3:30'),
            singleRow('BV1dddddddddd', '1小时Live全程回顾', '65:00'),
          ],
        },
      },
    }),
  });
  try {
    const songs = await searchBili('少年', 20);
    assert.equal(songs.length, 1);
    assert.equal(songs[0].id, 'BV1aaaaaaaaaa');
    assert.deepEqual(Object.keys(songs[0]).sort(), ['_raw', 'album', 'artists', 'cover', 'duration', 'id', 'name', 'vip']);
  } finally {
    h.restore();
  }
});

test('searchBili 边界时长：8:00保留、8:01过滤；duration缺失保留', async () => {
  const h = stubBili({
    search: async () => ({
      data: {
        code: 0,
        data: {
          result: [
            singleRow('BV1eeeeeeeeee', '边界八分钟整', '8:00'),
            singleRow('BV1ffffffffff', '超一秒', '8:01'),
            { type: 'video', aid: 3, bvid: 'BV1gggggggggg', title: '缺时长条目', author: 'up', typename: '音乐', pic: '', play: 10 },
          ],
        },
      },
    }),
  });
  try {
    const songs = await searchBili('少年', 20);
    assert.deepEqual(songs.map((s) => s.id), ['BV1eeeeeeeeee', 'BV1gggggggggg']);
  } finally {
    h.restore();
  }
});

test('isBiliSingle 不误伤翻唱/现场/Live/MV/完整版', () => {
  for (const name of ['少年 翻唱', '少年 现场版', '少年 Live', '少年 MV', '少年完整版', '少年 Official']) {
    assert.equal(isBiliSingle({ name, duration: 200000 }), true, name);
  }
  assert.equal(isBiliSingle({ name: '百大串烧合集', duration: 200000 }), false);
  assert.equal(isBiliSingle({ name: '年度盘点50首', duration: 200000 }), false);
  assert.equal(isBiliSingle({ name: '正常歌', duration: 9999999 }), false);
});

// ---- 取链 ----
test('getBiliUrl 纯 bvid 走 pagelist→cid→WBI playurl，取带宽最高伴音轨', async () => {
  const { restore, calls } = stubBili();
  try {
    const url = await getBiliUrl(BVID);
    assert.equal(url, 'https://xy.bilivideo.com/192k.m4s');
    const viewCall = calls.find((c) => c.url.includes('/player/pagelist'));
    assert.equal(viewCall.params.bvid, BVID);
    const puCall = calls.find((c) => c.url.includes('/player/wbi/playurl'));
    assert.equal(puCall.params.bvid, BVID);
    assert.equal(String(puCall.params.cid), String(CID));
    assert.ok(puCall.params.w_rid);
    assert.ok(puCall.params.wts);
    assert.ok(String(puCall.headers.Cookie).includes('buvid3='));
  } finally {
    restore();
  }
});

test('getBiliUrl 复合 bvid|cid 跳过 pagelist 直调 playurl', async () => {
  const { restore, calls } = stubBili();
  try {
    const url = await getBiliUrl(`${BVID}|${CID}`);
    assert.equal(url, 'https://xy.bilivideo.com/192k.m4s');
    assert.equal(calls.filter((c) => c.url.includes('/player/pagelist')).length, 0);
    assert.equal(calls.filter((c) => c.url.includes('/player/wbi/playurl')).length, 1);
  } finally {
    restore();
  }
});

test('getBiliUrl 非法 id/pagelist 无 cid/无伴音轨/异常均返回 null', async () => {
  const { restore } = stubBili();
  try {
    assert.equal(await getBiliUrl('12345'), null);
  } finally {
    restore();
  }

  let h = stubBili({ view: async () => ({ data: { code: 0, data: [] } }) });
  assert.equal(await getBiliUrl(BVID), null);
  h.restore();

  h = stubBili({ playurl: async () => ({ data: playurlPayload({ data: { dash: { audio: [] } } }) }) });
  assert.equal(await getBiliUrl(`${BVID}|${CID}`), null);
  h.restore();

  const orig = axios.get;
  axios.get = async (url, opts) => {
    if (String(url).includes('/finger/spi')) return { data: SPI_PAYLOAD };
    if (String(url).includes('/web-interface/nav')) return { data: NAV_PAYLOAD };
    throw new Error('timeout');
  };
  try {
    assert.equal(await getBiliUrl(`${BVID}|${CID}`), null);
  } finally {
    axios.get = orig;
  }
});

test('getBiliUrl -412 时刷新双票据重试一次', async () => {
  let playCalls = 0;
  const { restore, calls } = stubBili({
    playurl: async () => {
      playCalls++;
      if (playCalls === 1) return { data: { code: -412, message: 'req blocked' } };
      return { data: playurlPayload() };
    },
  });
  try {
    const url = await getBiliUrl(`${BVID}|${CID}`);
    assert.equal(url, 'https://xy.bilivideo.com/192k.m4s');
    assert.equal(playCalls, 2);
    // -412 后强制刷新票据：至少有一次 SPI 获取（模块级票据缓存跨用例常驻时为恰好 1 次）
    assert.ok(calls.filter((c) => c.url.includes('/finger/spi')).length >= 1);
  } finally {
    restore();
  }
});

test('getBiliView 成功解析 cid/duration，异常回 null', async () => {
  const { restore } = stubBili();
  try {
    assert.deepEqual(await getBiliView(BVID), { cid: CID, duration: 246 });
  } finally {
    restore();
  }
  const h = stubBili({ view: async () => ({ data: { code: -404, message: 'nope' } }) });
  assert.equal(await getBiliView(BVID), null);
  h.restore();
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

test('/bili/search 校验 400：缺 keyword/超长/重参对象', async () => {
  const r1 = await get('/bili/search');
  assert.equal(r1.status, 400);

  const r2 = await get(`/bili/search?keyword=${'歌'.repeat(101)}`);
  assert.equal(r2.status, 400);

  const r3 = await get('/bili/search?keyword[x]=1');
  assert.equal(r3.status, 400);
});

test('/bili/search 成功形状 {code,message,data}', async () => {
  const h = stubBili();
  try {
    const r = await get('/bili/search?keyword=少年&limit=20');
    assert.equal(r.status, 200);
    assert.equal(r.body.code, 200);
    assert.equal(r.body.message, 'success');
    assert.equal(r.body.data.length, 1);
    assert.equal(r.body.data[0].id, BVID);
  } finally {
    h.restore();
  }
});

test('/bili/url 校验 400：缺 id/裸 aid/QQ 形 id', async () => {
  const r1 = await get('/bili/url');
  assert.equal(r1.status, 400);

  const r2 = await get('/bili/url?id=243082173');
  assert.equal(r2.status, 400);

  const r3 = await get('/bili/url?id=004abcXYZ');
  assert.equal(r3.status, 400);
});

test('/bili/url 成功形状与缓存隔离：仅非 null 入缓存', async () => {
  const h = stubBili();
  try {
    const r1 = await get(`/bili/url?id=${BVID}`);
    assert.equal(r1.status, 200);
    assert.deepEqual(r1.body, { code: 200, message: 'success', data: [{ id: BVID, url: 'https://xy.bilivideo.com/192k.m4s' }] });
    assert.notEqual(urlCache.get(`bili_url:${BVID}`), undefined);
    assert.equal(urlCache.get(`qq_url:${BVID}`), undefined);

    const r2 = await get(`/bili/url?id=${BVID}`);
    assert.equal(r2.body.message, 'success (cached)');
  } finally {
    h.restore();
  }
});

test('/bili/url null 不缓存：两次都回源', async () => {
  let calls = 0;
  const h = stubBili({ playurl: async () => { calls++; return { data: { code: 0, data: { dash: { audio: [] } } } }; } });
  try {
    const r1 = await get(`/bili/url?id=${BVID}|${CID}`);
    assert.equal(r1.status, 200);
    assert.deepEqual(r1.body.data[0], { id: `${BVID}|${CID}`, url: null });
    const r2 = await get(`/bili/url?id=${BVID}|${CID}`);
    assert.equal(r2.body.data[0].url, null);
    assert.equal(urlCache.get(`bili_url:${BVID}|${CID}`), undefined);
    assert.equal(calls, 2);
  } finally {
    h.restore();
  }
});
