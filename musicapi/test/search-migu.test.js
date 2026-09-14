const { test } = require('node:test');
const assert = require('node:assert/strict');

const axios = require('axios');
const { searchMigu, mapMiguSong, getMiguUrl, miguCopyrightCache } = require('../src/search');

const MIGU_SAMPLE = {
  code: '000000',
  songResultData: {
    totalCount: '2',
    result: [
      {
        id: '1',
        resourceType: '2',
        contentId: '60054701986',
        copyrightId: '60054701986',
        name: '青花瓷',
        singers: [{ id: '111', name: '周杰伦' }],
        tags: [],
        lyricUrl: 'https://example/xxx.lrc',
        imgItems: [
          { imgSizeType: '03', img: 'https://example/cover1.jpg' },
          { imgSizeType: '02', img: 'https://example/cover1_small.jpg' },
        ],
      },
      {
        id: '2',
        resourceType: '2',
        contentId: '60054701987',
        copyrightId: '60054701988',
        name: '付费歌',
        singers: [{ id: '222', name: '测试歌手' }, { id: '223', name: '伴唱' }],
        tags: [],
        imgItems: [],
      },
    ],
  },
};

function stubAxiosGet(impl) {
  const orig = axios.get;
  axios.get = impl;
  return () => { axios.get = orig; };
}

test('full sample maps to existing normalized shape', async () => {
  let seenUrl; let seenParams; let seenHeaders;
  const restore = stubAxiosGet(async (url, opts) => {
    seenUrl = url; seenParams = opts.params; seenHeaders = opts.headers;
    return { data: MIGU_SAMPLE };
  });
  try {
    const songs = await searchMigu('青花瓷', 20);
    assert.equal(songs.length, 2);
    assert.equal(seenUrl, 'https://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do');
    assert.equal(seenParams.ua, 'Android_migu');
    assert.equal(seenParams.version, '5.0.1');
    assert.equal(seenParams.text, '青花瓷');
    assert.equal(seenParams.pageNo, 1);
    assert.equal(seenParams.pageSize, 20);
    assert.deepEqual(JSON.parse(seenParams.searchSwitch), { song: 1 });
    assert.equal(seenHeaders.Referer, 'https://music.migu.cn');

    const [first, second] = songs;
    assert.equal(first.id, '60054701986');
    assert.equal(first.name, '青花瓷');
    assert.equal(first.artists, '周杰伦');
    assert.equal(first.album, '');
    assert.equal(first.cover, 'https://example/cover1.jpg');
    assert.equal(first.duration, 0);
    assert.equal(first.vip, false);
    assert.equal(first._raw.copyrightId, '60054701986');
    assert.equal(second.artists, '测试歌手 / 伴唱');
    assert.equal(second.cover, '');
    assert.deepEqual(Object.keys(first).sort(), ['_raw', 'album', 'artists', 'cover', 'duration', 'id', 'name', 'vip']);
  } finally {
    restore();
  }
});

test('mapMiguSong tolerates minimal item (no singers/imgItems)', () => {
  const out = mapMiguSong({ contentId: 12345, name: '裸歌' });
  assert.equal(out.id, '12345');
  assert.equal(out.name, '裸歌');
  assert.equal(out.artists, '');
  assert.equal(out.album, '');
  assert.equal(out.cover, '');
  assert.equal(out.duration, 0);
  assert.equal(out.vip, false);
});

test('non-000000 code returns []', async () => {
  const restore = stubAxiosGet(async () => ({ data: { code: '100001', info: 'fail' } }));
  try {
    assert.deepEqual(await searchMigu('青花瓷', 10), []);
  } finally {
    restore();
  }
});

test('empty result returns []', async () => {
  const restore = stubAxiosGet(async () => ({ data: { code: '000000', songResultData: { totalCount: '0', result: [] } } }));
  try {
    assert.deepEqual(await searchMigu('不存在的词xyz', 10), []);
  } finally {
    restore();
  }
});

test('missing body returns []', async () => {
  const restore = stubAxiosGet(async () => ({ data: null }));
  try {
    assert.deepEqual(await searchMigu('青花瓷', 10), []);
  } finally {
    restore();
  }
});

test('HTTP error returns [] without throwing', async () => {
  const restore = stubAxiosGet(async () => { throw new Error('Request failed with status code 500'); });
  try {
    assert.deepEqual(await searchMigu('青花瓷', 10), []);
  } finally {
    restore();
  }
});

test('getMiguUrl returns 302 Location without downloading', async () => {
  let seenUrl; let seenParams; let seenOpts;
  const restore = stubAxiosGet(async (url, opts) => {
    seenUrl = url; seenParams = opts.params; seenOpts = opts;
    return { status: 302, headers: { location: 'https://cdn.migu.cn/signed.mp3?k=1' }, data: '' };
  });
  try {
    const out = await getMiguUrl('60054701986', '60054701986');
    assert.equal(out, 'https://cdn.migu.cn/signed.mp3?k=1');
    assert.equal(seenUrl, 'https://app.pd.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do');
    assert.equal(seenParams.toneFlag, 'PQ');
    assert.equal(seenParams.resourceType, 2);
    assert.equal(seenParams.contentId, '60054701986');
    assert.equal(seenParams.copyrightId, '60054701986');
    assert.equal(seenOpts.maxRedirects, 0);
  } finally {
    restore();
  }
});

test('getMiguUrl falls back to search-filled copyright cache', async () => {
  miguCopyrightCache.set('60000000001', '60000000002');
  let seenParams;
  const restore = stubAxiosGet(async (url, opts) => {
    seenParams = opts.params;
    return { status: 302, headers: { location: 'https://cdn.migu.cn/cached.mp3' }, data: '' };
  });
  try {
    const out = await getMiguUrl('60000000001');
    assert.equal(out, 'https://cdn.migu.cn/cached.mp3');
    assert.equal(seenParams.copyrightId, '60000000002');
    assert.equal(seenParams.toneFlag, 'PQ');
  } finally {
    restore();
    miguCopyrightCache.delete('60000000001');
  }
});

test('getMiguUrl returns null on 200000 no-rights JSON', async () => {
  const restore = stubAxiosGet(async () => ({
    status: 200, headers: {}, data: { code: '200000', info: '暂不提供试听地址' },
  }));
  try {
    assert.equal(await getMiguUrl('60054701986', '60054701986'), null);
  } finally {
    restore();
  }
});

test('getMiguUrl returns null on malformed redirect (no Location)', async () => {
  const restore = stubAxiosGet(async () => ({ status: 302, headers: {}, data: '' }));
  try {
    assert.equal(await getMiguUrl('60054701986', '60054701986'), null);
  } finally {
    restore();
  }
});

test('getMiguUrl rejects non-http Location', async () => {
  const restore = stubAxiosGet(async () => ({ status: 302, headers: { location: 'ftp://evil/x.mp3' }, data: '' }));
  try {
    assert.equal(await getMiguUrl('60054701986', '60054701986'), null);
  } finally {
    restore();
  }
});

test('getMiguUrl returns null (never throws) on network error and empty id', async () => {
  const restore = stubAxiosGet(async () => { throw new Error('socket hang up'); });
  try {
    assert.equal(await getMiguUrl('60054701986', '60054701986'), null);
    assert.equal(await getMiguUrl('', ''), null);
    assert.equal(await getMiguUrl(null, null), null);
  } finally {
    restore();
  }
});
