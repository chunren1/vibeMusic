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
    this._pending = 0; // 在飞 appendFile 计数（flush 用）
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
      // 异步写入避免阻塞事件循环；失败必须留痕（此前回调用空函数，写失败完全静默）
      this._pending++;
      fs.appendFile(path.join(LOG_DIR, `${category}.${this.currentDate}.log`), line, (err) => {
        this._pending--;
        if (err) console.error(`[LogManager] 写入失败 ${category}: ${err.message}`);
      });
    }
  }

  /**
   * 等待在飞写入落盘（优雅退出用），最多等 ms 毫秒。
   * 返回值：true=已全部落盘，false=超时仍有未完成写入。
   */
  async flush(ms = 2000) {
    const deadline = Date.now() + ms;
    while (this._pending > 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    return this._pending === 0;
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

// ==================== traceId 关联（与后端日志对齐） ====================
// 后端 NeteaseApiService 出站首部透传 X-Trace-Id（见后端 TraceHeaderSupport），
// 此处只负责把它安全地带进访问日志：没有该头就不打这个字段，绝不编造。
// 字符集/长度校验与 rate-limiters 的计数键口径一致：不可信头值直接丢弃，
// 防止换行/控制符伪造日志行（日志注入）。
const TRACE_ID_HEADER = 'x-trace-id';
const TRACE_ID_SAFE_RE = /^[A-Za-z0-9._:@-]{1,128}$/;

/**
 * 取请求里可安全落日志的 traceId。
 * @param {import('express').Request} req
 * @returns {string} 合法 traceId，缺失/非法返回 ''
 */
function traceIdOf(req) {
  const raw = req && req.headers ? req.headers[TRACE_ID_HEADER] : undefined;
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value !== 'string') return '';
  const trimmed = value.trim();
  return TRACE_ID_SAFE_RE.test(trimmed) ? trimmed : '';
}

/** 访问日志的 trace 片段：无 traceId 时返回空串，保持既有日志格式不变。 */
function traceTag(req) {
  const traceId = traceIdOf(req);
  return traceId ? ` [trace=${traceId}]` : '';
}

module.exports = { writeLog, logManager, LOG_DIR, traceIdOf, traceTag };
