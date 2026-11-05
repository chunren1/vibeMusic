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
const os = require('node:os');
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

describe('health-alert.sh：告警去抖（2026-11-06 刷屏修复）', () => {
  const src = code(ALERT);

  it('状态文件新格式 down <start> <pushed> [last_push] 与旧格式 down <ts> 都能解析', () => {
    assert.match(src, /read -r LAST_STATE LAST_TS LAST_PUSHED LAST_PUSH_TS < "\$STATE_FILE"/,
      '必须一次读入 4 个字段（缺省留空）才能兼容旧格式');
    assert.match(src, /printf 'down %s %s %s\\n' "\$DOWN_START" "\$PUSHED" "\$LAST_PUSH"/,
      '写盘必须是 down <起点> <是否推送过> <上次推送时刻>');
    assert.match(src, /LEGACY_STATE/, '旧格式 down <ts> 必须有兼容分支（视为已推送、起点未知）');
  });

  it('连续确认：首轮异常只记日志，连续 STREAK_CONFIRM 轮才推 🔴', () => {
    assert.match(src, /STREAK_CONFIRM=2/);
    assert.match(src, /STREAK=\$\(\( STREAK \+ 1 \)\)/);
    assert.match(src, /"\$STREAK" -ge "\$STREAK_CONFIRM"/, '推送必须以连续轮数为门槛');
    assert.match(src, /STREAK_FILE/, '连续轮数必须落盘（cron 每分钟一轮，不落盘就永远是 1）');
  });

  it('恢复推送有 min-down 门槛，且必须「确实推送过异常」', () => {
    assert.match(src, /RECOVERY_MIN_DOWN_SEC=300/);
    assert.match(src, /NOW - LAST_TS \)\) -lt \$RECOVERY_MIN_DOWN_SEC/, '短抖动恢复不得推送');
    assert.match(src, /"\$LAST_PUSHED" != "1"/, '从未推过异常的抖动恢复不得推送');
  });

  it('REPEAT_SEC 重复提醒仍在（且锚点是上次推送时刻，不是每轮刷新）', () => {
    assert.match(src, /REPEAT_SEC=1800/);
    assert.match(src, /NOW - ANCHOR \)\) -ge \$REPEAT_SEC/);
    assert.match(src, /ANCHOR=\$LAST_PUSH/, '重复提醒必须以上次推送时刻为锚点');
  });
});

// ---------------------------------------------------------------------------
// 行为级去抖验证：把 scripts/health-alert.sh 复制到临时目录，改写三处**沙箱**路径
// （状态/日志/key）+ 把 send() 换成写文件 + 把第 1~5 节检测替换成"喂 PROBLEMS"，
// 然后真跑一遍脚本断言推送次数。这样测的是**生产脚本本身**的去抖逻辑，
// 而不是测试里另写一份逻辑（静态断言只能证明"代码里出现过这个词"）。
// ---------------------------------------------------------------------------
describe('health-alert.sh：去抖行为（沙箱真跑，无需 root/docker/真 Server酱）', () => {
  const DATE_SHIM = '#!/bin/sh\nif [ -n "${SIM_NOW:-}" ]; then\n  case "$1" in\n    +%s) echo "$SIM_NOW"; exit 0;;\n  esac\nfi\nexec /bin/date "$@"\n';

  /** 造一个沙箱副本；结构锚点变了就明确报错，不许静默跳过。 */
  function buildSandbox() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vibemusic-alert-sim-'));
    for (const sub of ['etc', 'state', 'log', 'shim']) fs.mkdirSync(path.join(dir, sub));
    let out = read('scripts/health-alert.sh');
    const paths = [
      ['/etc/vibeMusic/serverchan.key', 'etc/serverchan.key'],
      ['/var/run/vibemusic-alert.state', 'state/alert.state'],
      ['/var/run/vibemusic-alert-note.state', 'state/note.state'],
      ['/var/log/vibemusic-alert.log', 'log/alert.log'],
      ['/var/run/vibemusic-alert-restarts.state', 'state/restarts.state'],
      ['/var/run/vibemusic-alert.lock', 'state/alert.lock'],
    ];
    for (const [from, to] of paths) out = out.split(from).join(path.join(dir, to));

    const lines = out.split('\n');
    const sendAt = lines.findIndex((l) => l.includes('curl -s --max-time 15 -X POST'));
    const sendEnd = lines.findIndex((l, i) => i >= sendAt && l.includes('推送失败'));
    assert.ok(sendAt > 0 && sendEnd > sendAt, 'send() 结构变了：找不到 curl 推送块，请同步更新本测试');
    lines.splice(sendAt, sendEnd - sendAt + 1,
      '    printf \'%s\\n\' "${title}" >> "${SIM_PUSH_LOG:?}"',
      '    log "推送成功(sim): ${title}"; return 0');

    out = lines.join('\n');
    const detectAt = out.indexOf('PROBLEMS=()');
    const stateAt = out.indexOf('NOW=$(date +%s); LAST_STATE=');
    assert.ok(detectAt > 0 && stateAt > detectAt,
      '巡检主体结构变了：找不到 PROBLEMS/NOW 锚点，请同步更新本测试');
    const stub = [
      'PROBLEMS=()',
      'RESTARTS_NEW=()',
      'if [ -n "${SIM_PROBLEMS:-}" ]; then PROBLEMS+=("$SIM_PROBLEMS"); fi',
      'SW_TOTAL=4194304; SW_USED=0; SW_PCT=0; DISK=42',
      '',
    ].join('\n');
    out = out.slice(0, detectAt) + stub + out.slice(stateAt);

    const script = path.join(dir, 'health-alert.sh');
    fs.writeFileSync(script, out);
    fs.writeFileSync(path.join(dir, 'shim', 'date'), DATE_SHIM, { mode: 0o755 });
    fs.writeFileSync(path.join(dir, 'etc', 'serverchan.key'), 'sim-key\n');
    const pushes = path.join(dir, 'pushes.log');
    fs.writeFileSync(pushes, '');
    return { dir, script, pushes };
  }

  function runRound(box, now, problem) {
    const r = spawnSync('bash', [box.script], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${path.join(box.dir, 'shim')}:${process.env.PATH}`,
        SIM_NOW: String(now),
        SIM_PROBLEMS: problem,
        SIM_PUSH_LOG: box.pushes,
      },
    });
    assert.equal(r.status, 0, `health-alert.sh 沙箱执行失败: ${r.stderr}`);
    const pushed = fs.readFileSync(box.pushes, 'utf8').split('\n').filter(Boolean);
    const state = fs.readFileSync(path.join(box.dir, 'state', 'alert.state'), 'utf8').trim();
    return { pushes: pushed.length, last: pushed[pushed.length - 1] || '', state };
  }

  it('连续 2 轮才推 🔴；短抖动恢复不推 🟢；持续 ≥5 分钟恢复才推 🟢', () => {
    const box = buildSandbox();
    try {
      const DOWN = '网易云 Cookie 主备全失效（VIP 掉档）';

      const r1 = runRound(box, 1_000_000, DOWN);
      assert.equal(r1.pushes, 0, '首轮异常只记日志，不能推送');
      assert.equal(r1.state, 'down 1000000 0 0');

      const r2 = runRound(box, 1_000_060, DOWN);
      assert.equal(r2.pushes, 1, '连续第 2 轮必须推 🔴');
      assert.match(r2.last, /🔴/);

      // 2 分钟后恢复：异常只持续 ~2 分钟 < 5 分钟 → 只允许 1 条（那条 🔴）
      const r3 = runRound(box, 1_000_120, '');
      assert.equal(r3.pushes, 1, '短抖动恢复不得推 🟢');
      assert.equal(r3.state, 'ok 1000120');

      // 新故障持续 11 分钟：第 2 轮推 🔴，恢复时必须补 🟢
      assert.equal(runRound(box, 1_000_200, 'backend 健康检查失败').pushes, 1);
      assert.equal(runRound(box, 1_000_260, 'backend 健康检查失败').pushes, 2);
      const r6 = runRound(box, 1_000_900, '');
      assert.equal(r6.pushes, 3, '异常持续 ≥5 分钟，恢复必须推送');
      assert.match(r6.last, /🟢/);
    } finally {
      fs.rmSync(box.dir, { recursive: true, force: true });
    }
  });

  it('旧格式 down <ts> 兼容：视为已推送、起点未知 → 恢复不推送，也不误判成新故障', () => {
    const box = buildSandbox();
    try {
      fs.writeFileSync(path.join(box.dir, 'state', 'alert.state'), 'down 999000\n');

      const r1 = runRound(box, 1_000_000, '');
      assert.equal(r1.pushes, 0, '旧格式起点未知，不得立刻推恢复');
      assert.equal(r1.state, 'ok 1000000');

      fs.writeFileSync(path.join(box.dir, 'state', 'alert.state'), 'down 999000\n');
      const r2 = runRound(box, 1_000_000, 'backend 健康检查失败');
      assert.equal(r2.pushes, 0, '旧格式已视为推送过，不重复推 🔴');
      assert.match(r2.state, /^down 1000000 1 \d+$/, '起点不可信时从本轮重新计时并保留 pushed=1');
    } finally {
      fs.rmSync(box.dir, { recursive: true, force: true });
    }
  });

  it('REPEAT_SEC：持续故障每 30 分钟才提醒一次（不会每分钟重推）', () => {
    const box = buildSandbox();
    try {
      fs.writeFileSync(path.join(box.dir, 'state', 'alert.state'), 'down 1000000 1 1000000\n');
      fs.writeFileSync(path.join(box.dir, 'state', 'vibemusic-alert-streak'), '5\n');

      assert.equal(runRound(box, 1_001_700, 'backend 健康检查失败').pushes, 0, '未到 30 分钟不得重推');
      assert.equal(runRound(box, 1_001_860, 'backend 健康检查失败').pushes, 1, '满 30 分钟提醒一次');
    } finally {
      fs.rmSync(box.dir, { recursive: true, force: true });
    }
  });
});
