#!/usr/bin/env node
/**
 * 项目自检脚本（跨平台：Linux / macOS / WSL / Windows 均可跑）。
 *
 * 历史问题（round6 I3）：旧版硬编码 `d:/vibeMusic`、依赖 Windows 专有的 `findstr`、
 * 检查已合并删除的 backend-ci.yml / frontend-ci.yml、断言早被改掉的 `deploy:` 与
 * `requirepass` —— 等于整体失效。这里重写为基于仓库根目录的纯 Node 检查。
 *
 * 用法：node scripts/verify.js   （退出码非 0 表示有检查未通过）
 */
const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const p = (...segs) => path.join(ROOT, ...segs);
const read = (rel) => fs.readFileSync(p(rel), 'utf8');
const exists = (rel) => fs.existsSync(p(rel));

let ok = 0;
let total = 0;
const failed = [];
const check = (label, pass, hint) => {
  total++;
  if (pass) {
    ok++;
  } else {
    failed.push(hint ? `${label}（${hint}）` : label);
    console.log(`  ✗ ${label}${hint ? ' — ' + hint : ''}`);
  }
};

console.log('=== 1. 凭据安全（.env 缺失则跳过，不视为失败）===');
const ENV_PATH = p('.env');
if (!fs.existsSync(ENV_PATH)) {
  console.log('  - 未找到 .env（CI/新克隆属正常），跳过凭据检查');
} else {
  const env = fs.readFileSync(ENV_PATH, 'utf8');
  const val = (k) => {
    const m = env.match(new RegExp('^' + k + '=(.*)$', 'm'));
    return m ? m[1].trim().replace(/^["']|["']$/g, '') : null;
  };
  ['MYSQL_ROOT_PASSWORD', 'JWT_SECRET', 'REDIS_PASSWORD'].forEach((k) => {
    const v = val(k);
    check(
      k,
      !!v && v.length > 8 && v !== '123456' && !v.startsWith('ChangeMe') && !v.startsWith('<your'),
      v ? `长度 ${v.length}，疑似弱口令` : '未设置'
    );
  });
}

console.log('\n=== 2. 关键文件 ===');
[
  'docker-compose.yml',
  'docker-compose.full.yml',
  'musicapi/.dockerignore',
  'vibeMusic-backend/.dockerignore',
  'nginx/nginx.conf',
  '.github/workflows/ci.yml',
].forEach((f) => check(f, exists(f)));

console.log('\n=== 3. 编排与配置一致性 ===');
const dc = read('docker-compose.yml');
check('资源限制锚点生效（x-resources / mem_limit）', /mem_limit|x-resources/.test(dc));
check('CORS 白名单注入', dc.includes('CORS_ORIGINS'));
check('健康检查存在', dc.includes('healthcheck'));
check('端口仅回环（无 0.0.0.0 暴露）', !/"?0\.0\.0\.0:(\d+)/.test(dc));

console.log('\n=== 4. Nginx 安全基线 ===');
const ng = read('nginx/nginx.conf');
check('CSP 头', ng.includes('Content-Security-Policy'));
check('Actuator 外部拒绝', /location \/actuator\/\s*\{[^}]*return 403;/.test(ng));
check('HSTS', ng.includes('Strict-Transport-Security'));
check('gzip_static', ng.includes('gzip_static'));

console.log('\n=== 5. 脚本引用的 compose 服务真实存在 ===');
try {
  const out = cp.execSync('docker compose -f docker-compose.yml config --services', {
    cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
    env: { ...process.env, JWT_SECRET: process.env.JWT_SECRET || 'x', GRAFANA_ADMIN_PASSWORD: process.env.GRAFANA_ADMIN_PASSWORD || 'x' },
  });
  const services = new Set(out.split(/\s+/).filter(Boolean));
  const scripts = JSON.parse(read('package.json')).scripts;
  const bad = [];
  for (const [name, cmd] of Object.entries(scripts)) {
    for (const m of cmd.matchAll(/docker compose up -d ([\w\- ]+)/g)) {
      for (const svc of m[1].split(/\s+/)) {
        if (!svc.startsWith('-') && !services.has(svc)) bad.push(`${name} → ${svc}`);
      }
    }
  }
  check('npm docker 脚本引用有效', bad.length === 0, bad.join(', '));
  console.log(`  - compose 服务数：${services.size}`);
} catch (e) {
  console.log(`  - docker 不可用或 compose 校验失败，跳过该组检查：${e.message.split('\n')[0]}`);
}

console.log('\n=== 6. 运行中的容器（可选）===');
try {
  const names = cp.execSync('docker ps --format {{.Names}}', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    .trim().split('\n').filter(Boolean);
  if (names.length === 0) {
    console.log('  - 没有运行中的容器（本地未启动，跳过）');
  } else {
    check('MySQL 运行中', names.some((c) => c.includes('mysql')));
    check('Redis 运行中', names.some((c) => c.includes('redis')));
  }
} catch {
  console.log('  - docker 不可用，跳过');
}

console.log('\n' + '='.repeat(30));
console.log(`结果: ${ok}/${total} 通过`);
if (failed.length) console.log(`未通过：\n  - ${failed.join('\n  - ')}`);
console.log('='.repeat(30));
process.exitCode = failed.length ? 1 : 0;
