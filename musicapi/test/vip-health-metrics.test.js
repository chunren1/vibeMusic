/**
 * VIP 健康指标测试（纯单测，无需启动 server）
 *
 * 运行: node --test --test-force-exit test/vip-health-metrics.test.js
 * 覆盖：vipHealthState 纯函数全分支 + setSlotGauges 落盘 gauge +
 * 告警口径迁移断言（死规则已清空 + 源健康改由 health-alert.sh 探活）。零新依赖。
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

// 2026-10-27 口径迁移：这 4 条源告警原本写在 alert-rules.yml 里，但 Prometheus 从未部署，
// 规则一条都不会触发（详见 docs/2026-10-26-project-audit.md §15）。现在改成两条断言：
//   1) 规则文件保持清空 —— 防止有人把「跑不了但看着安心」的死规则又抄回来；
//   2) 真正在跑的那条通道（scripts/health-alert.sh 第 5 节）必须覆盖同样的 4 个口径。
// 只断言「文件里出现某个名字」是弱测试（注释里写个名字就能过），所以第 1 条反着断言：
// 不允许出现任何 - alert: 行，也不允许引用已删导出器的指标名。
describe('告警口径：死规则已清空，源健康迁移到 health-alert.sh', () => {
  const root = path.resolve(__dirname, '..', '..');
  const rules = fs.readFileSync(
    path.join(root, 'docker-data', 'prometheus', 'alert-rules.yml'), 'utf8');
  const healthAlert = fs.readFileSync(path.join(root, 'scripts', 'health-alert.sh'), 'utf8');

  it('alert-rules.yml 里没有活规则（groups: []，无 - alert: 行）', () => {
    assert.ok(/^groups:\s*\[\]\s*$/m.test(rules), '规则文件应保持 groups: []');
    assert.ok(!/^\s*-\s*alert:/m.test(rules), '不应再有 - alert: 行（死规则已删除）');
  });

  it('alert-rules.yml 的**非注释内容**不再引用已删导出器 / 未部署 exporter 的指标', () => {
    // 只查非注释行：头部 tombstone 注释要解释「为什么删」，合法地会提到这些指标名。
    // 这里防的是「有人把规则抄回来」，不是防注释里出现名字。
    const active = rules.split('\n')
      .filter((line) => !line.trim().startsWith('#'))
      .join('\n');
    for (const dead of ['jvm_memory_used_bytes', 'hikaricp_connections_active',
      'node_filesystem_avail_bytes', 'probe_success', 'up{job=']) {
      assert.ok(!active.includes(dead), `规则文件不应再引用 ${dead}（无采集方）`);
    }
  });

  it('health-alert.sh 第 5 节覆盖网易主/备 + QQ 四个口径', () => {
    assert.ok(healthAlert.includes('COOKIE_STATUS_URL'), '应有 cookie-status 探活地址');
    assert.ok(healthAlert.includes('/cookie-status'), '探活路径应为 /cookie-status');
    for (const field of ['netease', 'neteasePrimary', 'neteaseBackup', 'qq']) {
      assert.ok(healthAlert.includes(`"${field}"`), `探活解析应断言字段 ${field}`);
    }
  });

  it('探活解析对「字段缺失」有防护（403 信封不能误判成全失效）', () => {
    assert.ok(healthAlert.includes('if not all(k in d for k in'),
      '缺字段时应按「探活无结果」跳过，而不是当成 Cookie 全失效去推送');
  });
});
