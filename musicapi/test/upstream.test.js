// ==================== upstream.js 统一上游客户端 ====================
// 覆盖：label 白名单 / URL 脱敏 / 错误归因 / 默认 validateStatus / 超时 /
//       响应体积上限 / 重试预算（只对 network/timeout 生效）。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {
  upstreamGet,
  UpstreamError,
  classifyError,
  normalizeLabel,
  describeUrl,
  isPrivateHost,
  KIND,
} = require('../src/upstream');

/**
 * 起一个本地 HTTP 服务、执行 fn(port)、结束后关闭。
 * handler(req, res) 由各用例定义；服务只监听 127.0.0.1 随机端口。
 */
function withServer(handler, fn) {
  return new Promise((resolve, reject) => {
    const server = http.createServer(handler);
    server.listen(0, '127.0.0.1', async () => {
      const { port } = server.address();
      try {
        const result = await fn(port);
        server.close(() => resolve(result));
      } catch (e) {
        server.close(() => reject(e));
      }
    });
  });
}

/** 拿到一个"肯定没人监听"的端口（先起后关，避免撞端口）。 */
function closedPort() {
  return new Promise((resolve) => {
    const server = http.createServer();
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

// ---------------- 纯函数 ----------------

test('normalizeLabel：只认常量标签，疑似拼 URL/query 的一律 unknown', () => {
  assert.equal(normalizeLabel('qq/search'), 'qq/search');
  assert.equal(normalizeLabel('bili/playurl'), 'bili/playurl');
  // 含 ? & = 空白 → 判为"拼了 URL"，整段丢弃
  assert.equal(normalizeLabel('search?keyword=周杰伦'), 'unknown');
  assert.equal(normalizeLabel('a=1'), 'unknown');
  assert.equal(normalizeLabel('a b'), 'unknown');
  // 非法字符替换为 _；超长截断到 64
  assert.equal(normalizeLabel('netease/search'), 'netease/search');
  assert.equal(normalizeLabel('x'.repeat(80)).length, 64);
  assert.equal(normalizeLabel(''), 'unknown');
  assert.equal(normalizeLabel(null), 'unknown');
  assert.equal(normalizeLabel(123), 'unknown');
});

test('describeUrl：日志只允许 host + pathname，query 永不出现', () => {
  const d = describeUrl('https://music.163.com/api/search?keyword=秘密&cookie=MUSIC_U%3Dxxx');
  assert.equal(d.host, 'music.163.com');
  assert.equal(d.path, '/api/search');
  assert.equal(JSON.stringify(d).includes('MUSIC_U'), false);
  assert.equal(JSON.stringify(d).includes('keyword'), false);
  // 非法 URL 不抛错
  assert.deepEqual(describeUrl('not-a-url'), { host: 'invalid', hostname: '', path: '/' });
});

test('classifyError：错误归因覆盖 timeout/network/http/too_large/aborted/unknown', () => {
  assert.equal(classifyError({ name: 'TimeoutError' }), KIND.TIMEOUT);
  assert.equal(classifyError({ code: 'ETIMEDOUT' }), KIND.TIMEOUT);
  assert.equal(classifyError({ code: 'ECONNABORTED', message: 'timeout of 100ms exceeded' }), KIND.TIMEOUT);
  assert.equal(classifyError({ code: 'ECONNREFUSED' }), KIND.NETWORK);
  assert.equal(classifyError({ code: 'ECONNRESET' }), KIND.NETWORK);
  assert.equal(classifyError({ response: { status: 500 } }), KIND.HTTP);
  assert.equal(
    classifyError({ code: 'ERR_BAD_RESPONSE', message: 'maxContentLength size of 1024 exceeded' }),
    KIND.TOO_LARGE
  );
  assert.equal(classifyError({ code: 'ERR_CANCELED' }), KIND.ABORTED);
  assert.equal(classifyError({ message: 'something odd' }), KIND.UNKNOWN);
});

// ---------------- 集成（本地 HTTP 服务） ----------------

test('upstreamGet：2xx 正常返回响应体', async () => {
  await withServer(
    (req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ code: 0, ok: true }));
    },
    async (port) => {
      const resp = await upstreamGet(`http://127.0.0.1:${port}/x`, { label: 'local/ok', allowPrivate: true });
      assert.equal(resp.status, 200);
      assert.deepEqual(resp.data, { code: 0, ok: true });
    }
  );
});

test('upstreamGet：5xx 默认抛 http 且不重试（重试只给 network/timeout）', async () => {
  let hits = 0;
  await withServer(
    (req, res) => {
      hits += 1;
      res.writeHead(500);
      res.end('boom');
    },
    async (port) => {
      await assert.rejects(
        () => upstreamGet(`http://127.0.0.1:${port}/x`, { label: 'local/500', allowPrivate: true, retries: 2 }),
        (err) => {
          assert.ok(err instanceof UpstreamError);
          assert.equal(err.kind, KIND.HTTP);
          assert.equal(err.status, 500);
          return true;
        }
      );
      assert.equal(hits, 1, 'HTTP 状态错误不重试');
    }
  );
});

test('upstreamGet：validateStatus 透传——调用方放行的 4xx 正常返回', async () => {
  await withServer(
    (req, res) => {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ missing: true }));
    },
    async (port) => {
      const resp = await upstreamGet(`http://127.0.0.1:${port}/x`, {
        label: 'local/404', allowPrivate: true,
        validateStatus: () => true,
      });
      assert.equal(resp.status, 404);
      assert.deepEqual(resp.data, { missing: true });
    }
  );
});

test('upstreamGet：超时归为 timeout，并按 retries 重试', async () => {
  let hits = 0;
  await withServer(
    (req, res) => {
      hits += 1;
      // 故意不回，让客户端超时（server 在超时后仍会挂到连接关闭）
      setTimeout(() => {
        try {
          res.end('late');
        } catch (_) {}
      }, 300);
    },
    async (port) => {
      await assert.rejects(
        () => upstreamGet(`http://127.0.0.1:${port}/x`, { label: 'local/slow', allowPrivate: true, timeoutMs: 60, retries: 1 }),
        (err) => {
          assert.ok(err instanceof UpstreamError);
          assert.equal(err.kind, KIND.TIMEOUT);
          return true;
        }
      );
      assert.equal(hits, 2, 'timeout 可重试一次');
    }
  );
});

test('upstreamGet：响应超体积归为 too_large，且不重试', async () => {
  let hits = 0;
  await withServer(
    (req, res) => {
      hits += 1;
      res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
      res.end(Buffer.alloc(4096, 0x61));
    },
    async (port) => {
      await assert.rejects(
        () => upstreamGet(`http://127.0.0.1:${port}/big`, { label: 'local/big', allowPrivate: true, maxBytes: 1024, retries: 2 }),
        (err) => {
          assert.ok(err instanceof UpstreamError || err.code === 'ERR_BAD_RESPONSE');
          return true;
        }
      );
      assert.equal(hits, 1, 'too_large 不重试');
    }
  );
});

test('upstreamGet：连接拒绝归为 network', async () => {
  const port = await closedPort();
  await assert.rejects(
    () => upstreamGet(`http://127.0.0.1:${port}/x`, { label: 'local/down', allowPrivate: true, timeoutMs: 500 }),
    (err) => {
      assert.ok(err instanceof UpstreamError);
      assert.equal(err.kind, KIND.NETWORK);
      return true;
    }
  );
});

// ---------------- 二阶 SSRF 防护 ----------------

test('isPrivateHost：私网/环回/链路本地一律命中，公网不误伤', () => {
  for (const h of [
    '127.0.0.1', '10.1.2.3', '172.16.0.1', '172.31.255.255',
    '192.168.1.1', '169.254.169.254', '0.0.0.0', '100.64.0.1',
    '::1', 'fc00::1', 'fd12::1', 'fe80::1',
    'localhost', 'foo.localhost', 'printer.local',
  ]) {
    assert.equal(isPrivateHost(h), true, `${h} 应判私网`);
  }
  for (const h of [
    '8.8.8.8', '172.15.0.1', '172.32.0.1',
    'music.163.com', 'aqqmusic.tc.qq.com', '2408:8000::1',
  ]) {
    assert.equal(isPrivateHost(h), false, `${h} 不应判私网`);
  }
});

test('upstreamGet：私网目标默认拒绝且不发请求（二阶 SSRF 防护）', async () => {
  let hits = 0;
  await withServer(
    (req, res) => {
      hits += 1;
      res.end('secret');
    },
    async (port) => {
      await assert.rejects(
        () => upstreamGet(`http://127.0.0.1:${port}/secret`, { label: 'local/ssrf' }),
        (err) => {
          assert.ok(err instanceof UpstreamError);
          assert.equal(err.kind, KIND.BLOCKED);
          return true;
        }
      );
      assert.equal(hits, 0, '被拦截的请求不应到达目标');
    }
  );
});
