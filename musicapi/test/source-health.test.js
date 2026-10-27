/**
 * 每源熔断 + 健康快照（纯单测，无需启动 server、无网络）
 *
 * 运行: node --test --test-force-exit test/source-health.test.js
 * 覆盖：连续失败阈值 / 半开时间窗 / 成功后清零 / 空结果不计账 / 快照口径 / 闸门短路。
 *
 * 为什么这些用例值得写：熔断一旦误开，用户会看到「某源搜不到任何歌」且没有任何报错，
 * 排障成本极高。所以每条边界（差一次不打开、空结果不计数、窗口到期自动放行）都要钉死。
 */
const { describe, it, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const {
  FAIL_THRESHOLD,
  OPEN_MS,
  SOURCES,
  isOpen,
  recordOutcome,
  guard,
  okOutcome,
  failOutcome,
  snapshot,
  setDisabledSources,
  reset,
} = require('../src/source-health');

describe('熔断阈值', () => {
  beforeEach(() => reset());

  it('连续失败未达阈值不打开', () => {
    for (let i = 1; i < FAIL_THRESHOLD; i++) recordOutcome('qq', false, { reason: `e${i}` });
    assert.equal(isOpen('qq'), false);
    assert.equal(snapshot().sources.find((s) => s.name === 'qq').state, 'degraded');
  });

  it('连续失败达到阈值即打开', () => {
    for (let i = 0; i < FAIL_THRESHOLD; i++) recordOutcome('qq', false, { reason: 'boom' });
    assert.equal(isOpen('qq'), true);
    const row = snapshot().sources.find((s) => s.name === 'qq');
    assert.equal(row.state, 'open');
    assert.ok(row.openRemainingMs > 0 && row.openRemainingMs <= OPEN_MS);
    assert.equal(row.lastError, 'boom');
  });

  it('中间成功一次就清零，不会累计到阈值', () => {
    recordOutcome('kugou', false, { reason: 'a' });
    recordOutcome('kugou', false, { reason: 'b' });
    recordOutcome('kugou', true, { songs: 3 });
    recordOutcome('kugou', false, { reason: 'c' });
    assert.equal(isOpen('kugou'), false);
    const row = snapshot().sources.find((s) => s.name === 'kugou');
    assert.equal(row.consecutiveFailures, 1);
    assert.equal(row.lastSongs, 3);
  });

  it('打开后过了 OPEN_MS 自动放行（半开）', () => {
    for (let i = 0; i < FAIL_THRESHOLD; i++) recordOutcome('bili', false, { reason: 'x' });
    assert.equal(isOpen('bili'), true);
    assert.equal(isOpen('bili', Date.now() + OPEN_MS + 1), false);
  });

  it('未记录的源默认不打开（进程刚起时不会误熔断）', () => {
    assert.equal(isOpen('netease'), false);
    assert.equal(snapshot().sources.find((s) => s.name === 'netease').state, 'closed');
  });
});

describe('记账口径：空结果不算成功也不算失败', () => {
  beforeEach(() => reset());

  it('okOutcome([]) 不增加成功数，也不清零已有失败', () => {
    recordOutcome('migu', false, { reason: 'a' });
    const returned = okOutcome('migu', []);
    assert.deepEqual(returned, []);
    const row = snapshot().sources.find((s) => s.name === 'migu');
    assert.equal(row.consecutiveFailures, 1, '空结果不应清零失败计数');
    assert.equal(row.totalOk, 0, '空结果不应计入成功');
  });

  it('okOutcome(非空) 记录成功并把原数组原样返回', () => {
    const songs = [{ id: 1 }, { id: 2 }];
    assert.equal(okOutcome('migu', songs), songs);
    const row = snapshot().sources.find((s) => s.name === 'migu');
    assert.equal(row.consecutiveFailures, 0);
    assert.equal(row.lastSongs, 2);
    assert.equal(row.totalOk, 1);
  });

  it('failOutcome 记录失败并返回空数组（搜索语义，不抛错）', () => {
    assert.deepEqual(failOutcome('bili', 'upstream-code=-412'), []);
    const row = snapshot().sources.find((s) => s.name === 'bili');
    assert.equal(row.consecutiveFailures, 1);
    assert.equal(row.lastError, 'upstream-code=-412');
  });

  it('lastError 截断到 200 字符（上游可能抛超长报文）', () => {
    failOutcome('qq', 'x'.repeat(500));
    const row = snapshot().sources.find((s) => s.name === 'qq');
    assert.equal(row.lastError.length, 200);
  });
});

describe('闸门 guard', () => {
  beforeEach(() => reset());

  it('未打开时正常执行并透传返回值', async () => {
    const out = await guard('qq', async () => [1, 2, 3]);
    assert.deepEqual(out, [1, 2, 3]);
  });

  it('打开时短路返回空数组，且**不执行**回调', async () => {
    for (let i = 0; i < FAIL_THRESHOLD; i++) recordOutcome('kugou', false, { reason: 'down' });
    let called = 0;
    const out = await guard('kugou', async () => { called += 1; return [9]; });
    assert.deepEqual(out, []);
    assert.equal(called, 0, '熔断打开后不应再打上游');
  });
});

describe('快照口径', () => {
  beforeEach(() => reset());

  it('固定覆盖 5 个源，顺序稳定（便于运维按名 grep）', () => {
    const snap = snapshot();
    assert.deepEqual(snap.sources.map((s) => s.name), SOURCES);
    assert.deepEqual(SOURCES, ['netease', 'qq', 'migu', 'kugou', 'bili']);
  });

  it('summary 三类计数之和等于总数', () => {
    for (let i = 0; i < FAIL_THRESHOLD; i++) recordOutcome('qq', false, { reason: 'x' });
    recordOutcome('migu', false, { reason: 'y' });
    recordOutcome('kugou', true, { songs: 1 });
    const { summary, sources } = snapshot();
    assert.equal(summary.total, sources.length);
    assert.equal(summary.open, 1);
    assert.equal(summary.degraded, 1);
    assert.equal(summary.healthy, 3);
    assert.equal(summary.disabled, 0);
    assert.equal(summary.open + summary.degraded + summary.disabled + summary.healthy, summary.total);
  });

  it('停用源标为 disabled，不计入 healthy（运维不该把它看成健康）', () => {
    setDisabledSources(['qq']);
    try {
      const { summary, sources } = snapshot();
      const qq = sources.find((s) => s.name === 'qq');
      assert.equal(qq.state, 'disabled');
      assert.equal(summary.disabled, 1);
      assert.equal(summary.healthy, SOURCES.length - 1);
      assert.equal(summary.open + summary.degraded + summary.disabled + summary.healthy, summary.total);
    } finally {
      setDisabledSources([]);
    }
  });

  it('disabled 与 open 互斥：停用的源即使熔断打开也显示 disabled', () => {
    for (let i = 0; i < FAIL_THRESHOLD; i++) recordOutcome('qq', false, { reason: 'x' });
    assert.equal(isOpen('qq'), true);
    setDisabledSources(['qq']);
    try {
      assert.equal(snapshot().sources.find((s) => s.name === 'qq').state, 'disabled');
    } finally {
      setDisabledSources([]);
    }
  });

  it('reset 清空全部状态', () => {
    for (let i = 0; i < FAIL_THRESHOLD; i++) recordOutcome('qq', false, { reason: 'x' });
    reset();
    assert.equal(isOpen('qq'), false);
    assert.equal(snapshot().sources.find((s) => s.name === 'qq').totalFail, 0);
  });
});
