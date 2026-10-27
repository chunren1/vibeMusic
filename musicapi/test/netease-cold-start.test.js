/**
 * 网易云冷启动顺序回归（2026-10-27 网关重构 pass 1 实测踩到的坑）
 *
 * 运行: node --test --test-force-exit test/netease-cold-start.test.js
 *
 * 为什么必须用独立子进程：这个 bug 只在**首次加载顺序**下出现 ——
 *   cookie.js(121 行 require cookie-probe) → cookie-probe.js → sources/netease.js → cookie.js
 * 本进程里模块早已加载完，再怎么断言都是绿的（假绿）。所以每个用例 spawn 一个
 * 全新 node 进程，按冷启动顺序 require，再看真实行为。
 *
 * 守的是什么：sources/netease.js 若把 withNeteaseCookie 写回顶层解构，
 * 拿到的是 cookie.js 尚未赋值的 exports（undefined），makeParam 抛 TypeError。
 * **注意这个 bug 不报错、不返回空**：TypeError 被 4 条策略链的 catch 吃掉，
 * 自动降级到第 3/4 条「无 Cookie」策略 —— 搜索照样有结果，只是全部变成匿名搜索
 * （VIP 相关性丢失、网易云风控更容易命中）。所以断言必须是「Cookie 真的透传到上游」，
 * 而不是「有结果就行」——后者在有 bug 时同样绿（我第一版就是这么写的，已废弃）。
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');

const MUSICAPI_DIR = path.resolve(__dirname, '..');

/** 起一个全新 node 进程执行 `script`，总是返回 status/stdout/stderr（成功也带 stderr）。 */
function runCold(script) {
  const r = spawnSync(process.execPath, ['-e', script], {
    cwd: MUSICAPI_DIR,
    timeout: 30000,
    encoding: 'utf8',
    env: {
      ...process.env,
      // 日志目录隔离：绝不写生产 logs/
      MUSICAPI_LOG_DIR: path.join(os.tmpdir(), 'vibemusic-musicapi-cold-test'),
      // 不依赖真实 Cookie / 真实网络
      MUSIC_NETEASE_COOKIE: '',
      MUSIC_NETEASE_COOKIE_BACKUP: '',
      MUSIC_QQ_COOKIE: '{}',
      // 探针关掉：子进程起手不打真实上游（本用例只关心 require 顺序）
      MUSIC_NETEASE_PROBE_ENABLED: 'false',
    },
  });
  return {
    status: typeof r.status === 'number' ? r.status : -1,
    stdout: r.stdout || '',
    stderr: r.stderr || '',
  };
}

// 公共前奏：先 require cookie（连带加载 cookie-probe → sources/netease，复现冷启动循环），
// 再 stub 上游库并记录每次收到的 params。
const PRELUDE = `
  const lib = require('NeteaseCloudMusicApi');
  require('./src/cookie');
  const seen = [];
  const song = { id: 42, name: 'n', ar: [{ name: 'a' }], al: { name: 'al', picUrl: '' }, dt: 1000, fee: 0 };
  lib.cloudsearch = async (p) => { seen.push(p); return { body: { code: 200, result: { songs: [song] } } }; };
  lib.search = async (p) => { seen.push(p); return { body: { code: 200, result: { songs: [song] } } }; };
`;

describe('网易云冷启动：循环 require 不得吃掉 withNeteaseCookie', () => {
  it('per-request Cookie 必须真的透传到上游（有 bug 时会静默变匿名搜索）', () => {
    const r = runCold(`${PRELUDE}
      const { searchNetease } = require('./src/sources/netease');
      const req = { headers: { 'x-vibe-user-cookie': 'MUSIC_U=cold-start-probe' } };
      searchNetease('测试', 1, req).then((songs) => {
        const withCookie = seen.filter((p) => p && p.cookie === 'MUSIC_U=cold-start-probe');
        if (songs.length === 1 && withCookie.length > 0) process.exit(0);
        console.error('SONGS=' + songs.length + ' SEEN=' + JSON.stringify(seen));
        process.exit(1);
      }).catch((e) => { console.error('THREW ' + e.message); process.exit(2); });
    `);
    assert.equal(
      r.status, 0,
      `Cookie 未透传（说明走了无 Cookie 降级链）status=${r.status} stdout=${r.stdout.slice(-400)} stderr=${r.stderr.slice(-400)}`,
    );
  });

  it('冷启动不产生 circular dependency 警告（Node 只在取到空壳时打）', () => {
    const r = runCold(`${PRELUDE}
      const { searchNetease } = require('./src/sources/netease');
      searchNetease('测试', 1, {}).then(() => process.exit(0)).catch(() => process.exit(1));
    `);
    assert.equal(r.status, 0, `status=${r.status} stderr=${r.stderr.slice(-400)}`);
    assert.ok(
      !/circular dependency/i.test(r.stderr),
      `不应再有循环依赖警告，实际 stderr:\n${r.stderr.slice(-800)}`,
    );
  });

  it('无 Cookie 的两条降级策略同样可用（带 Cookie 的两条全失败时才走到）', () => {
    const r = runCold(`
      const lib = require('NeteaseCloudMusicApi');
      require('./src/cookie');
      const song = { id: 7, name: 'n', ar: [], al: {}, dt: 1000, fee: 0 };
      lib.cloudsearch = async (p) => {
        if (p && p.cookie) throw new Error('cookie path deliberately broken');
        return { body: { code: 200, result: { songs: [song] } } };
      };
      lib.search = async (p) => {
        if (p && p.cookie) throw new Error('cookie path deliberately broken');
        return { body: { code: 200, result: { songs: [] } } };
      };
      const { searchNetease } = require('./src/sources/netease');
      const req = { headers: { 'x-vibe-user-cookie': 'MUSIC_U=fake-for-test' } };
      searchNetease('测试', 1, req).then((songs) => {
        process.exit(songs.length === 1 && songs[0].id === 7 ? 0 : 1);
      }).catch(() => process.exit(2));
    `);
    assert.equal(r.status, 0, `status=${r.status} stderr=${r.stderr.slice(-400)}`);
  });
});

describe('隔离墙不变量：全仓只有 sources/netease.js 直接 require 第三方库', () => {
  it("src/ 顶层目录无 require('NeteaseCloudMusicApi')", () => {
    const offenders = [];
    for (const name of fs.readdirSync(path.join(MUSICAPI_DIR, 'src'))) {
      if (!name.endsWith('.js')) continue;
      const text = fs.readFileSync(path.join(MUSICAPI_DIR, 'src', name), 'utf8');
      if (/require\('NeteaseCloudMusicApi'\)/.test(text)) offenders.push(`src/${name}`);
    }
    assert.deepEqual(offenders, [], `这些文件绕过了隔离墙: ${offenders.join(', ')}`);
  });

  it('src/sources/ 下只有 netease.js 允许 require 第三方库', () => {
    const dir = path.join(MUSICAPI_DIR, 'src', 'sources');
    const offenders = [];
    for (const name of fs.readdirSync(dir)) {
      if (!name.endsWith('.js') || name === 'netease.js') continue;
      const text = fs.readFileSync(path.join(dir, name), 'utf8');
      if (/require\('NeteaseCloudMusicApi'\)/.test(text)) offenders.push(`src/sources/${name}`);
    }
    assert.deepEqual(offenders, [], `这些文件绕过了隔离墙: ${offenders.join(', ')}`);
  });
});
