/**
 * ops 脚本守卫（纯静态 + 语法检查，无需 root、无需 docker）
 *
 * 运行: node --test test/ops-scripts.test.js
 *
 * 为什么用静态断言：watchdog.sh 与 health-alert.sh 要写 /var/lib、/var/run、
 * 调 docker/cron，没法在 CI 里真跑。但"语法坏了"和"代码里又用回无超时的 docker"
 * 这两类回归是能在零环境条件下抓到的——2026-11-03 那 8 条 ops 提交里，
 * 有 3 条就是在修同类问题（一处修好、另一处没同步）。
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const WATCHDOG = read('scripts/watchdog.sh');
const ALERT = read('scripts/health-alert.sh');

// 去掉注释行，避免"注释里写了就算过"的假绿
const code = (src) =>
  src
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');

describe('ops 脚本：语法必须可解析', () => {
  it('watchdog.sh 用 /bin/sh，必须同时通过 sh -n 与 dash -n', () => {
    for (const shell of ['sh', 'dash']) {
      const r = spawnSync(shell, ['-n', path.join(ROOT, 'scripts/watchdog.sh')], {
        encoding: 'utf8',
      });
      // dash 可能未安装（容器里），跳过而不是误报
      if (r.error && r.error.code === 'ENOENT') continue;
      assert.equal(r.status, 0, `${shell} -n 失败: ${r.stderr}`);
    }
  });

  it('health-alert.sh 用 bash，必须通过 bash -n', () => {
    const r = spawnSync('bash', ['-n', path.join(ROOT, 'scripts/health-alert.sh')], {
      encoding: 'utf8',
    });
    assert.equal(r.status, 0, `bash -n 失败: ${r.stderr}`);
  });
});

describe('watchdog.sh：容器状态必须覆盖 exited', () => {
  const src = code(WATCHDOG);

  it('接管 exited / dead（restart 在 start 阶段超时会把容器留在 exited）', () => {
    assert.match(src, /exited\|dead\)/, '状态分支必须包含 exited|dead');
  });

  it('restarting 只记录、不重复 restart（别打断 Docker 自己的退避）', () => {
    assert.match(src, /restarting\)/, '应有 restarting 分支');
    assert.match(src, /restarting[\s\S]{0,200}continue/, 'restarting 分支应 continue');
  });

  it('exited 容器要尊重 restart policy=no（一次性/人工管理的容器不自动拉起）', () => {
    assert.match(src, /RestartPolicy\.Name/, '应读取 HostConfig.RestartPolicy.Name');
  });

  it('先记账再重启（重启途中被杀也不会丢掉退避时间戳）', () => {
    const rec = src.indexOf('record_restart "$name"');
    const rst = src.indexOf('dk_restart "$name"');
    assert.ok(rec > 0 && rst > 0, '应同时存在 record_restart 与 dk_restart 调用');
    assert.ok(rec < rst, 'record_restart 必须出现在 dk_restart 之前');
  });

  it('状态文件里的非数字时间戳不能让整轮脚本退出', () => {
    assert.match(src, /case "\$prev" in[\s\S]{0,80}\*\[!0-9\]\*\)/, 'prev 必须做字符校验');
  });
});

describe('health-alert.sh：告警链路不能静默失效', () => {
  const src = code(ALERT);

  it('所有 docker 调用都经过带 timeout 的 dk()', () => {
    assert.match(src, /timeout "\$DK_TIMEOUT" docker/, 'dk() 必须带 timeout');
    assert.ok(!/^\s*docker\s+(inspect|restart|ps)/m.test(src),
      '不应再出现裸 docker 调用（会随 dockerd 卡死无限挂起）');
  });

  it('有 flock 互斥（cron 每分钟一轮，重叠会重复推送/丢计数）', () => {
    assert.match(src, /flock -n 9 \|\| exit 0/);
  });

  it('崩溃循环按窗口内新增重启计数，不用累计 RestartCount', () => {
    assert.match(src, /RESTART_STATE/, '需要持久化窗口基线');
    assert.match(src, /restarts - base_count/, '应只统计窗口内新增的重启次数');
    assert.ok(!/\[ "\$restarts" -ge 3 \]/.test(src),
      '累计 RestartCount ≥3 的判据会因 watchdog 自己重启而误报');
  });

  it('探活被 403 拒绝时要单独告警，不能当成"探活无结果"静默跳过', () => {
    assert.match(src, /DENIED/, '403 必须有独立分支');
    assert.match(src, /PROBLEMS\+=\([^)]*403/, '403 必须进入告警列表');
  });

  it('日志有轮转（否则每分钟一行会自己把磁盘告警推高）', () => {
    assert.match(src, /rotate_log/);
  });
});
