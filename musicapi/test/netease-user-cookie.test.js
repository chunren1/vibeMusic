/**
 * 内部 per-request 用户 Cookie 覆盖测试（BYOC Task 3）
 *
 * 运行: node --test --test-force-exit test/netease-user-cookie.test.js（无需启动 server，纯单测）
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { withNeteaseCookie, resolveNeteaseCookie } = require('../src/cookie');
const { sanitizeNeteaseParams, scrubSecrets } = require('../src/routes');

const USER_COOKIE = 'MUSIC_U=user-personal-vip-cookie-value; __csrf=abc123';
const reqWith = (v) => ({ headers: { 'x-vibe-user-cookie': v } });
// 本环境共享 Cookie 未知（可能为空）：回落期望一律以无 header 基线为准
const baselineParams = () => withNeteaseCookie({ keywords: 'x' });

describe('resolveNeteaseCookie 优先级：header > 共享 > 匿名', () => {
  it('合法 header 获胜（MUSIC_U 形态、8KB 内）', () => {
    assert.equal(resolveNeteaseCookie(reqWith(USER_COOKIE)), USER_COOKIE);
    assert.deepEqual(
      withNeteaseCookie({ keywords: 'x' }, reqWith(USER_COOKIE)),
      { keywords: 'x', cookie: USER_COOKIE },
    );
  });

  it('空白 header 回落共享（空串/纯空格/缺失/非字符串均不抛错）', () => {
    const expected = baselineParams();
    assert.deepEqual(withNeteaseCookie({ keywords: 'x' }, reqWith('')), expected);
    assert.deepEqual(withNeteaseCookie({ keywords: 'x' }, reqWith('   ')), expected);
    assert.deepEqual(withNeteaseCookie({ keywords: 'x' }, { headers: {} }), expected);
    assert.deepEqual(withNeteaseCookie({ keywords: 'x' }, undefined), expected);
    assert.deepEqual(withNeteaseCookie({ keywords: 'x' }, { headers: { 'x-vibe-user-cookie': 42 } }), expected);
    assert.equal(resolveNeteaseCookie(undefined), resolveNeteaseCookie({ headers: {} }));
  });

  it('超长 header 被拒绝（>8KB），回落共享', () => {
    const oversized = `MUSIC_U=${'x'.repeat(9000)}`;
    assert.ok(oversized.length > 8192);
    assert.deepEqual(withNeteaseCookie({ keywords: 'x' }, reqWith(oversized)), baselineParams());
  });

  it('无 MUSIC_U 形态的脏值被拒绝，回落共享', () => {
    assert.deepEqual(
      withNeteaseCookie({ keywords: 'x' }, reqWith('SESSIONID=attacker-value')),
      baselineParams(),
    );
  });

  it('数组 header 取首个（Express ?k=a&k=b 口径一致）', () => {
    assert.equal(resolveNeteaseCookie(reqWith([USER_COOKIE, 'MUSIC_U=second'])), USER_COOKIE);
  });

  it('withNeteaseCookie 不污染入参，只增 cookie 键', () => {
    const extra = { keywords: 'x' };
    withNeteaseCookie(extra, reqWith(USER_COOKIE));
    assert.deepEqual(extra, { keywords: 'x' });
  });
});

describe('公网面不变：query/body cookie 照旧剥离，响应无凭证', () => {
  it('公网 ?cookie= / body.cookie 仍被剥离（header 通道是唯一覆盖入口）', () => {
    assert.deepEqual(
      sanitizeNeteaseParams({ keywords: 'x', cookie: 'MUSIC_U=forged' }, { Cookie: 'MUSIC_U=forged2' }),
      { keywords: 'x' },
    );
  });

  it('响应体不回显任何 Cookie（含 per-request 用户值）', () => {
    const body = {
      code: 200,
      cookie: USER_COOKIE,
      data: { songs: [{ id: 1 }], MUSIC_U: USER_COOKIE },
    };
    const out = scrubSecrets(body);
    assert.equal(out.cookie, undefined);
    assert.equal(out.data.MUSIC_U, undefined);
    assert.deepEqual(out.data.songs, [{ id: 1 }]);
    assert.ok(!JSON.stringify(out).includes(USER_COOKIE));
  });
});
