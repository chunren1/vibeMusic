/**
 * 网易共享 Cookie 主备槽位测试（纯单测，无需启动 server）
 *
 * 运行: node --test --test-force-exit test/netease-cookie-failover.test.js
 * 零新依赖：仅 node:test + node:assert/strict 内置模块。
 */
const { describe, it, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const {
  decideActiveSlot,
  resolveSharedCookie,
  resolveNeteaseCookie,
  getNeteaseActive,
  _setSharedForTest,
  _resetSharedForTest,
} = require('../src/cookie');

const P = 'MUSIC_U=primary-cookie-value; __csrf=p';
const B = 'MUSIC_U=backup-cookie-value; __csrf=b';

afterEach(() => {
  _resetSharedForTest();
});

describe('decideActiveSlot 主优先/备接管/双失效保持', () => {
  it('主有效恒选主（含双有效）', () => {
    assert.equal(decideActiveSlot({ primaryOk: true, backupOk: false, current: 'backup' }), 'primary');
    assert.equal(decideActiveSlot({ primaryOk: true, backupOk: true, current: 'backup' }), 'primary');
  });

  it('主失效且备有效切备', () => {
    assert.equal(decideActiveSlot({ primaryOk: false, backupOk: true, current: 'primary' }), 'backup');
  });

  it('双失效保持现状（降级链不变）', () => {
    assert.equal(decideActiveSlot({ primaryOk: false, backupOk: false, current: 'primary' }), 'primary');
    assert.equal(decideActiveSlot({ primaryOk: false, backupOk: false, current: 'backup' }), 'backup');
  });
});

describe('resolveSharedCookie 跟随生效槽位', () => {
  it('默认主槽位', () => {
    _setSharedForTest({ primary: P, backup: B, active: 'primary' });
    const out = resolveSharedCookie();
    assert.equal(out.slot, 'primary');
    assert.equal(out.cookie, P);
    assert.equal(getNeteaseActive(), 'primary');
  });

  it('切备后返回备串', () => {
    _setSharedForTest({ primary: P, backup: B, active: 'backup' });
    const out = resolveSharedCookie();
    assert.equal(out.slot, 'backup');
    assert.equal(out.cookie, B);
  });

  it('备槽无备串回落主（不透空）', () => {
    _setSharedForTest({ primary: P, backup: '', active: 'backup' });
    const out = resolveSharedCookie();
    assert.equal(out.slot, 'primary');
    assert.equal(out.cookie, P);
  });

  it('双空返回空串（匿名语义不变）', () => {
    _setSharedForTest({ primary: '', backup: '', active: 'primary' });
    assert.equal(resolveSharedCookie().cookie, '');
  });
});

describe('resolveNeteaseCookie 优先级不变：header > 生效槽位 > 匿名', () => {
  it('合法 header 仍获胜，不受槽位影响', () => {
    _setSharedForTest({ primary: P, backup: B, active: 'backup' });
    const header = 'MUSIC_U=user-personal; __csrf=x';
    assert.equal(resolveNeteaseCookie({ headers: { 'x-vibe-user-cookie': header } }), header);
  });

  it('无 header 时走生效槽位（备）', () => {
    _setSharedForTest({ primary: P, backup: B, active: 'backup' });
    assert.equal(resolveNeteaseCookie({ headers: {} }), B);
    assert.equal(resolveNeteaseCookie(undefined), B);
  });
});
