/**
 * /netease/* 通配路由防护测试（API-C1：SSRF + MUSIC_U 凭证外发）
 *
 * 运行: node --test test/netease-ssrf.test.js（无需启动 server，纯单测）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { sanitizeNeteaseParams, scrubSecrets } = require('../src/routes');

describe('sanitizeNeteaseParams 参数白名单', () => {
  it('放行合法业务参数（后端 NeteaseApiService 实际调用：playlist_detail?id / search?keyword&limit）', () => {
    assert.deepEqual(
      sanitizeNeteaseParams({ id: '12345' }, {}),
      { id: '12345' },
    );
    assert.deepEqual(
      sanitizeNeteaseParams({ keyword: '周杰伦', limit: '20' }, {}),
      { keyword: '周杰伦', limit: '20' },
    );
    assert.deepEqual(
      sanitizeNeteaseParams({ keywords: 'a', limit: 10, offset: 0, type: 1, level: 'exhigh', ids: '1,2', uid: '99', idx: 0 }, {}),
      { keywords: 'a', limit: 10, offset: 0, type: 1, level: 'exhigh', ids: '1,2', uid: '99', idx: 0 },
    );
  });

  it('剥离全部保留键：domain/proxy/realIP/cookie/crypto/ua/e_r（含大小写变体）', () => {
    const out = sanitizeNeteaseParams({
      keywords: 'x',
      domain: 'http://attacker.example',
      Domain: 'http://attacker.example',
      proxy: 'http://127.0.0.1:9',
      realIP: '127.0.0.1',
      realIp: '127.0.0.1',
      cookie: 'MUSIC_U=forged',
      Cookie: 'MUSIC_U=forged',
      crypto: 'weapi',
      ua: 'evil',
      e_r: 'true',
      url: 'http://169.254.169.254',
      ip: '127.0.0.1',
    }, {});
    assert.deepEqual(out, { keywords: 'x' });
  });

  it('默认拒绝未知参数；body 与 query 合并后同样过滤', () => {
    const out = sanitizeNeteaseParams({ keywords: 'x', foo: 'bar' }, { keywords: 'y', proxy: 'http://evil', limit: '5' });
    assert.deepEqual(out, { keywords: 'y', limit: '5' });
  });

  it('丢弃数组/对象/超长字符串/空字符串（上游库不期望复合参数）', () => {
    const out = sanitizeNeteaseParams({
      keywords: ['a', 'b'],
      limit: { $gt: 1 },
      type: '1',
      id: '',
      ids: 'x'.repeat(2001),
    }, {});
    assert.deepEqual(out, { type: '1' });
  });

  it('容忍缺失参数（undefined/null 不抛异常）', () => {
    assert.deepEqual(sanitizeNeteaseParams(undefined, undefined), {});
    assert.deepEqual(sanitizeNeteaseParams(null, null), {});
  });
});

describe('scrubSecrets 响应凭证剥离', () => {
  it('剥离顶层与嵌套的 cookie/MUSIC_U/MUSIC_A/__csrf', () => {
    const body = {
      code: 200,
      cookie: 'MUSIC_U=real-secret',
      data: { MUSIC_U: 'real-secret', MUSIC_A: 'a-secret', __csrf: 'c', songs: [{ id: 1 }] },
    };
    const out = scrubSecrets(body);
    assert.equal(out.cookie, undefined);
    assert.equal(out.data.MUSIC_U, undefined);
    assert.equal(out.data.MUSIC_A, undefined);
    assert.equal(out.data.__csrf, undefined);
    assert.deepEqual(out.data.songs, [{ id: 1 }]);
    assert.equal(out.code, 200);
  });

  it('剥离大小写变体与数组内嵌对象；保留含敏感词的值文本（如歌词里的 token 一词）', () => {
    const body = {
      Cookie: 'x',
      list: [{ MUSIC_u: 's', lyric: 'this token is just a lyric word' }, { id: 2 }],
    };
    const out = scrubSecrets(body);
    assert.equal(out.Cookie, undefined);
    assert.equal(out.list[0].MUSIC_u, undefined);
    assert.equal(out.list[0].lyric, 'this token is just a lyric word');
    assert.deepEqual(out.list[1], { id: 2 });
  });

  it('标量输入原样返回，不抛异常', () => {
    assert.equal(scrubSecrets(null), null);
    assert.equal(scrubSecrets('MUSIC_U=x'), 'MUSIC_U=x');
    assert.equal(scrubSecrets(42), 42);
  });
});
