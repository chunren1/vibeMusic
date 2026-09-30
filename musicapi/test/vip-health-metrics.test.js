/**
 * VIP 健康指标测试（纯单测，无需启动 server）
 *
 * 运行: node --test --test-force-exit test/vip-health-metrics.test.js
 * 覆盖：vipHealthState 纯函数全分支 + setSlotGauges 落盘 gauge +
 * 告警规则文件包含 4 条新规则。零新依赖。
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  vipHealthState,
  setSlotGauges,
  getVipHealth,
} = require('../src/cookie');
const { register } = require('../src/metrics');

describe('vipHealthState 主备健康口径', () => {
  it('双有效 → 1/1', () => {
    assert.deepEqual(vipHealthState(true, true, true), { primary: 1, backup: 1 });
  });

  it('主失效备有效 → 0/1', () => {
    assert.deepEqual(vipHealthState(false, true, true), { primary: 0, backup: 1 });
  });

  it('双失效 → 0/0', () => {
    assert.deepEqual(vipHealthState(false, false, true), { primary: 0, backup: 0 });
  });

  it('未配备份 → 备恒为 1（无失效对象，不误报）', () => {
    assert.deepEqual(vipHealthState(true, false, false), { primary: 1, backup: 1 });
    assert.deepEqual(vipHealthState(false, false, false), { primary: 0, backup: 1 });
  });
});

describe('setSlotGauges 落盘 musicapi_cookie_vip_health', () => {
  it('主失效备有效时 gauge=0/1 且快照一致', () => {
    setSlotGauges(false, true, true);
    const snap = getVipHealth();
    assert.equal(snap.primary, 0);
    assert.equal(snap.backup, 1);
  });

  it('恢复双有效后 gauge 回 1/1（健康路径不变）', () => {
    setSlotGauges(true, true, true);
    const snap = getVipHealth();
    assert.equal(snap.primary, 1);
    assert.equal(snap.backup, 1);
  });

  it('探针计数器按槽位累加（result 标签 ok/fail）', async () => {
    const metric = register.getSingleMetric('musicapi_cookie_probe_total');
    assert.ok(metric, '应注册 musicapi_cookie_probe_total');
    const before = await metric.get();
    const sum = (slot, result) => before.values
      .filter((v) => v.labels.slot === slot && v.labels.result === result)
      .reduce((a, v) => a + v.value, 0);
    const okBefore = sum('primary', 'ok');
    const failBefore = sum('primary', 'fail');
    setSlotGauges(true, true, true);
    setSlotGauges(false, true, true);
    const after = await metric.get();
    const sumAfter = (slot, result) => after.values
      .filter((v) => v.labels.slot === slot && v.labels.result === result)
      .reduce((a, v) => a + v.value, 0);
    assert.equal(sumAfter('primary', 'ok') - okBefore, 1);
    assert.equal(sumAfter('primary', 'fail') - failBefore, 1);
  });
});

describe('告警规则包含 VIP 健康 4 条规则', () => {
  it('alert-rules.yml 含主/双/接管/QQ 四条告警', () => {
    const p = path.resolve(__dirname, '..', '..', 'docker-data', 'prometheus', 'alert-rules.yml');
    const text = fs.readFileSync(p, 'utf8');
    for (const name of [
      'NeteaseVipPrimaryDown',
      'NeteaseVipBothDown',
      'NeteaseVipFailoverActive',
      'QqCookieDown',
    ]) {
      assert.ok(text.includes(name), `缺告警规则: ${name}`);
    }
    assert.ok(text.includes('musicapi_cookie_vip_health'), '规则应引用 vip 健康指标');
  });
});
