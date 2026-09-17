// ==================== 日志系统（按天轮转 + 30天自动清理） ====================
const fs = require('fs');
const path = require('path');

const LOG_DIR = process.env.MUSICAPI_LOG_DIR || path.join(__dirname, '..', 'logs');
if (!fs.existsSync(LOG_DIR)) fs.mkdirSync(LOG_DIR, { recursive: true });

class LogManager {
  constructor(retentionDays = 30) {
    this.retentionDays = retentionDays;
    this.streams = {};
    this.currentDate = '';
    this._rotate();
    this._scheduleMidnightRotation();
  }

  /** 获取当前日期字符串 YYYY-MM-DD */
  _getDate() {
    return new Date().toISOString().slice(0, 10);
  }

  /** 按需轮转：日期变了就切文件 */
  _rotate() {
    const date = this._getDate();
    if (date === this.currentDate) return;
    this.currentDate = date;

    // 关闭旧流
    for (const key of Object.keys(this.streams)) {
      try { this.streams[key].end(); } catch (e) { /* ignore */ }
    }
    this.streams = {};

    // 打开新流（带日期后缀）
    for (const name of ['api-errors', 'cookie-monitor', 'degradation', 'access']) {
      const filePath = path.join(LOG_DIR, `${name}.${date}.log`);
      const stream = fs.createWriteStream(filePath, { flags: 'a' });
      // 日志文件不可写（权限/只读卷）不应拖垮进程：吞掉 stream error 并降级为 console 告警
      stream.on('error', (e) => {
        console.error(`[LogManager] 日志文件不可写 ${filePath}: ${e.message}`);
      });
      this.streams[name] = stream;
    }

    this._cleanupOldLogs();
  }

  /** 调度午夜轮转（精确到次日 00:00） */
  _scheduleMidnightRotation() {
    const now = new Date();
    const msToMidnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 0) - now;
    setTimeout(() => {
      this._rotate();
      setInterval(() => this._rotate(), 24 * 60 * 60 * 1000);
    }, msToMidnight);
  }

  /** 删除 retentionDays 天前的日志文件 */
  _cleanupOldLogs() {
    const cutoff = Date.now() - this.retentionDays * 24 * 60 * 60 * 1000;
    try {
      const files = fs.readdirSync(LOG_DIR);
      for (const file of files) {
        // 匹配 date-suffixed 日志：xxx.2026-06-01.log
        if (/^.+\.[12]\d{3}-\d{2}-\d{2}\.log$/.test(file)) {
          const filePath = path.join(LOG_DIR, file);
          const stat = fs.statSync(filePath);
          if (stat.mtimeMs < cutoff) {
            fs.unlinkSync(filePath);
          }
        }
      }
    } catch (e) {
      console.error('[LogManager] 清理过期日志失败:', e.message);
    }
  }

  write(category, level, message) {
    this._rotate(); // 按需轮转
    const stream = this.streams[category];
    if (stream) {
      const timestamp = new Date().toISOString();
      const line = `[${timestamp}] [${level}] ${message}\n`;
      // 使用异步写入避免阻塞事件循环
      fs.appendFile(path.join(LOG_DIR, `${category}.${this.currentDate}.log`), line, () => {});
    }
  }
}

const logManager = new LogManager(30);

// 写日志类别 → 落盘文件流的映射。
// 调用方写作 'api' / 'cookie'，而流键是 'api-errors' / 'cookie-monitor'；
// 此前未做映射，导致这两类日志只进 console、从不落盘（文件长期 0 字节）。
const STREAM_BY_CATEGORY = {
  api: 'api-errors',
  cookie: 'cookie-monitor',
  degradation: 'degradation',
  access: 'access',
};

function writeLog(category, level, message) {
  logManager.write(STREAM_BY_CATEGORY[category] || category, level, message);
  if (category === 'api' || category === 'cookie') console.log(`[${new Date().toISOString()}] [${level}] ${message}`);
}

module.exports = { writeLog, logManager, LOG_DIR };
