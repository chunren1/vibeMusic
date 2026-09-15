/**
 * 一键提取 QQ 音乐 Cookie
 * 用法: node scripts/get_qq_cookie.mjs
 * 浏览器窗口会打开 y.qq.com，扫码登录后自动提取并写入 .env
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'fs';

const NEEDED = ['uin','qqmusic_key','psrf_qqaccess_token','psrf_qqopenid',
  'psrf_qqrefresh_token','psrf_qqunionid','ptcz'];

import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
const __dirname = dirname(fileURLToPath(import.meta.url));
const ENV = join(__dirname, '..', '.env');

console.log('🚀 正在启动浏览器...\n');

const browser = await chromium.launch({
  headless: false,
  args: ['--no-sandbox', '--disable-blink-features=AutomationControlled'],
});

const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
});

const page = await context.newPage();
await page.goto('https://y.qq.com', { waitUntil: 'networkidle', timeout: 30000 });

console.log('📱 请在打开的浏览器窗口中扫码登录 QQ 音乐');
console.log('⏳ 等待登录完成 (检测到 qqmusic_key 后自动继续)...\n');

let loggedIn = false;
for (let i = 0; i < 90; i++) {
  await page.waitForTimeout(2000);
  const cookies = await context.cookies('https://y.qq.com');
  const hasKey = cookies.some(c => c.name === 'qqmusic_key' && c.value.length > 10);
  if (hasKey) {
    loggedIn = true;
    break;
  }
  process.stdout.write(`\r   等待中... ${Math.floor((i + 1) * 2)}s`);
}

if (!loggedIn) {
  console.log('\n❌ 超时 (3分钟)，未检测到登录');
  await browser.close();
  process.exit(1);
}

console.log('\n✅ 检测到登录成功! 正在提取 Cookie...');

await page.waitForTimeout(2000);
const cookies = await context.cookies('https://y.qq.com');
const result = {};
for (const c of cookies) {
  if (NEEDED.includes(c.name)) {
    result[c.name] = c.value;
  }
}

console.log(`\n📋 提取到 ${Object.keys(result).length}/${NEEDED.length} 个字段:`);
for (const [k, v] of Object.entries(result)) {
  console.log(`  ${k} = *** (len=${v.length})`);
}

// 更新 .env
let content = readFileSync(ENV, 'utf8');
const newLine = 'MUSIC_QQ_COOKIE=' + JSON.stringify(result);
content = content.replace(/MUSIC_QQ_COOKIE=.*/g, newLine);
writeFileSync(ENV, content, 'utf8');

console.log(`\n💾 已更新 .env`);
console.log('\n✅ 完成! 重启 musicapi 使新 Cookie 生效:');
console.log('   Ctrl+C 停掉当前服务 → npm run dev');

await browser.close();
