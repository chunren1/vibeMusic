// vibeMusic 运维面板 (跨平台)
// 用法: node scripts/ops.cjs
// 等同于: npm run ops

const { spawn, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

const LOG_DIR = path.join(__dirname, '..', 'musicapi', 'logs');

function menu() {
  console.log('\n  ╔════════════════════════════════════╗');
  console.log('  ║       vibeMusic 运维面板          ║');
  console.log('  ╚════════════════════════════════════╝');
  console.log('');
  console.log('  [1] cpolar 隧道监控 (启动/前台运行)');
  console.log('  [2] 查看日志');
  console.log('  [3] 检查服务状态');
  console.log('  [4] 检查 Cookie 存活');
  console.log('  [5] 停止所有服务');
  console.log('  [0] 退出');
  console.log('');
}

function ask(question) {
  return new Promise(resolve => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    rl.question(question, answer => { rl.close(); resolve(answer.trim()); });
  });
}

function startCpolorMonitor() {
  console.log('\n  启动 cpolar 监控...');
  // Windows: 直接调 bat
  if (process.platform === 'win32') {
    spawn('cmd', ['/c', path.join(__dirname, 'ops', 'start-cpolar-monitor.bat')], {
      cwd: path.join(__dirname, '..'), shell: true, stdio: 'inherit',
    });
  } else {
    spawn('python3', [path.join(__dirname, 'ops', 'cpolar-monitor.py')], {
      cwd: path.join(__dirname, '..'), stdio: 'inherit',
    });
  }
}

function viewLogs() {
  console.log(`\n  日志目录: ${LOG_DIR}\n`);
  if (!fs.existsSync(LOG_DIR)) {
    console.log('  暂无日志文件');
    return;
  }
  const logs = fs.readdirSync(LOG_DIR).filter(f => f.endsWith('.log'));
  if (logs.length === 0) {
    console.log('  暂无日志文件');
    return;
  }
  logs.forEach(f => console.log(`  - ${f}`));
  console.log('  在 IDE 中直接打开 musicapi/logs/ 查看内容\n');
}

function checkStatus() {
  console.log('');
  const checks = [
    ['musicapi (3000)', 'http://localhost:3000/health'],
    ['前端 (5173)', 'http://localhost:5173'],
  ];
  checks.forEach(([name, url]) => {
    try {
      execSync(`curl -s -o /dev/null -w "%{http_code}" ${url}`, { timeout: 3000 });
      console.log(`  ✅ ${name}`);
    } catch {
      console.log(`  ❌ ${name}`);
    }
  });
  console.log('');
}

async function checkCookies() {
  console.log('');
  const { execSync } = require('child_process');
  try {
    execSync(`python "${path.join(__dirname, 'ops', 'check-cookies.py')}"`, {
      cwd: path.join(__dirname, '..'), stdio: 'inherit',
    });
  } catch {
    console.log('  Cookie 检测失败（请先启动 musicapi）');
  }
  console.log('');
}

function stopAll() {
  console.log('\n  正在停止...');
  try { execSync('taskkill /FI "WINDOWTITLE eq musicapi-3000*" /F', { stdio: 'ignore' }); } catch {}
  try { execSync('taskkill /FI "WINDOWTITLE eq vibemusic-web*" /F', { stdio: 'ignore' }); } catch {}
  try { execSync('taskkill /FI "WINDOWTITLE eq cpolar-monitor*" /F', { stdio: 'ignore' }); } catch {}
  console.log('  ✅ 已停止\n');
}

(async () => {
  while (true) {
    menu();
    const choice = await ask('  请选择: ');
    switch (choice) {
      case '1': startCpolorMonitor(); break;
      case '2': viewLogs(); break;
      case '3': checkStatus(); break;
      case '4': await checkCookies(); break;
      case '5': stopAll(); break;
      case '0': console.log('  已退出\n'); process.exit(0);
    }
  }
})();
