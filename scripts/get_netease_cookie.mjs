/**
 * 一键提取网易云音乐 Cookie
 * 用法: node scripts/get_netease_cookie.mjs
 * 浏览器窗口会打开 music.163.com，扫码登录后自动提取并写入 .env
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ENV = join(__dirname, '..','.env');

console.log('🚀 正在启动浏览器...\n');

const browser = await chromium.launch({
  headless: false,
  args: ['--no-sandbox'],
});

const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
});

const page = await context.newPage();
await page.goto('https://music.163.com', { waitUntil: 'networkidle', timeout: 30000 });

console.log('📱 请在打开的浏览器窗口中扫码登录网易云音乐');
console.log('⏳ 等待登录完成...\n');

let loggedIn = false;
for (let i = 0; i < 90; i++) {
  await page.waitForTimeout(2000);
  const cookies = await context.cookies('https://music.163.com');
  const hasMusU = cookies.some(c => c.name === 'MUSIC_U' && c.value.length > 10);
  if (hasMusU) {
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
const cookies = await context.cookies('https://music.163.com');

const musU = cookies.find(c => c.name === 'MUSIC_U')?.value || '';
const csrf = cookies.find(c => c.name === '__csrf')?.value || '';

const cookieStr = `MUSIC_U=${musU}; __csrf=${csrf}`;
console.log(`\n📋 MUSIC_U=${musU.substring(0, 20)}...`);
console.log(`   __csrf=${csrf}`);

// 更新 .env
let content = readFileSync(ENV, 'utf8');
const newLine = 'MUSIC_NETEASE_COOKIE=' + cookieStr;
content = content.replace(/MUSIC_NETEASE_COOKIE=.*/g, newLine);
writeFileSync(ENV, content, 'utf8');

console.log(`\n💾 已更新 .env`);
console.log('\n✅ 完成! 重启 musicapi 使新 Cookie 生效');

await browser.close();
