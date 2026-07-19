/**
 * musicapi 集成测试 — 速率限制 + 参数校验 + Cookie 状态
 *
 * 运行: npx vitest run
 * 或:  node --test test/server.test.js
 *
 * 注意: 测试前确保 musicapi 已启动 (node server.js)
 */

const http = require('http');

const BASE_URL = 'http://localhost:3000';

/** 封装 HTTP GET 请求 */
function get(path, timeout = 5000) {
  return new Promise((resolve, reject) => {
    const req = http.get(`${BASE_URL}${path}`, { timeout }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, body: JSON.parse(data) });
        } catch (e) {
          resolve({ status: res.statusCode, body: data });
        }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, body: { message: 'timeout' } }); });
  });
}

// ======================== 1. 健康检查 ========================
async function testHealthCheck() {
  console.log('\n=== 测试组: 健康检查 ===');
  const res = await get('/health');
  console.assert(res.status === 200, `健康检查应返回 200, 实际: ${res.status}`);
  console.assert(res.body.message, '健康检查应包含 message');
  console.assert(res.body.data, '健康检查应包含 data 字段');
  console.log('✅ 健康检查通过');
}

// ======================== 2. Cookie 状态 ========================
async function testCookieStatus() {
  console.log('\n=== 测试组: Cookie 状态 ===');
  const res = await get('/cookie-status');
  console.assert(res.status === 200, `Cookie状态应返回 200, 实际: ${res.status}`);
  console.assert('netease' in (res.body.data || {}), '应包含 netease 状态');
  console.assert('qq' in (res.body.data || {}), '应包含 qq 状态');
  console.log('✅ Cookie 状态检查通过');
}

// ======================== 3. 搜索参数校验 ========================
async function testSearchValidation() {
  console.log('\n=== 测试组: 搜索参数校验 ===');

  // 3.1 缺少 keyword
  const r1 = await get('/search');
  console.assert(r1.status === 400, `缺少 keyword 应返回 400, 实际: ${r1.status}`);

  // 3.2 keyword 过长
  const longKw = 'x'.repeat(101);
  const r2 = await get(`/search?keyword=${encodeURIComponent(longKw)}`);
  console.assert(r2.status === 400, `超长 keyword 应返回 400, 实际: ${r2.status}`);

  // 3.3 size 超大应自动截断
  const r3 = await get('/search?keyword=周杰伦&size=99999');
  console.assert(r3.status === 200, `超大 size 应返回 200(截断), 实际: ${r3.status}`);

  // 3.4 prefer 非法值应被忽略
  const r4 = await get('/search?keyword=周杰伦&prefer=invalid');
  console.assert(r4.status === 200, `非法 prefer 应返回 200(忽略), 实际: ${r4.status}`);

  console.log('✅ 搜索参数校验通过');
}

// ======================== 4. ID 参数校验 ========================
async function testIdValidation() {
  console.log('\n=== 测试组: ID 参数校验 ===');

  // 4.1 缺少 id
  const r1 = await get('/lyric');
  console.assert(r1.status === 400, `缺少 id 应返回 400, 实际: ${r1.status}`);

  // 4.2 非法 id 格式 (非数字)
  const r2 = await get('/lyric?id=../etc/passwd');
  console.assert(r2.status === 400, `非法 id 格式应返回 400, 实际: ${r2.status}`);

  // 4.3 /song/url/v1 缺少 id
  const r3 = await get('/song/url/v1');
  console.assert(r3.status === 400, `缺少 id 应返回 400, 实际: ${r3.status}`);

  // 4.4 /song/url/qq 非法 id
  const r4 = await get('/song/url/qq?id=<script>alert(1)</script>');
  console.assert(r4.status === 400, `非法 id 格式应返回 400, 实际: ${r4.status}`);

  console.log('✅ ID 参数校验通过');
}

// ======================== 5. 搜索功能 ========================
async function testSearchFunction() {
  console.log('\n=== 测试组: 搜索功能 ===');
  const res = await get('/search?keyword=周杰伦&size=5', 15000);
  console.assert(res.status === 200, `搜索应返回 200, 实际: ${res.status}`);
  if (res.body.data && res.body.data.list) {
    console.assert(Array.isArray(res.body.data.list), '搜索结果应为数组');
    console.assert(res.body.data.list.length <= 5, `结果数应 <= 5, 实际: ${res.body.data.list.length}`);
    if (res.body.data.list.length > 0) {
      const first = res.body.data.list[0];
      console.assert(first.name, '歌曲应有 name');
      console.assert(first.artists, '歌曲应有 artists');
    }
  }
  console.log('✅ 搜索功能通过');
}

// ======================== 6. Prometheus 指标 ========================
async function testMetrics() {
  console.log('\n=== 测试组: Prometheus 指标 ===');
  const res = await get('/metrics');
  console.assert(res.status === 200, `指标端点应返回 200, 实际: ${res.status}`);
  if (typeof res.body === 'string') {
    console.assert(res.body.includes('musicapi_http_requests_total'), '应包含 http_requests_total 指标');
    console.assert(res.body.includes('musicapi_cache_hits_total'), '应包含 cache_hits_total 指标');
    console.assert(res.body.includes('musicapi_cookie_status'), '应包含 cookie_status 指标');
    console.assert(res.body.includes('musicapi_up'), '应包含 up 指标');
  }
  console.log('✅ Prometheus 指标通过');
}

// ======================== 主函数 ========================
async function runAll() {
  let passed = 0;
  let failed = 0;

  const tests = [
    testHealthCheck,
    testCookieStatus,
    testSearchValidation,
    testIdValidation,
    testSearchFunction,
    testMetrics,
  ];

  for (const testFn of tests) {
    try {
      await testFn();
      passed++;
    } catch (e) {
      failed++;
      console.error(`❌ 测试失败: ${e.message}`);
    }
  }

  console.log(`\n========================================`);
  console.log(`测试完成: ${passed} 通过, ${failed} 失败, ${tests.length} 总计`);
  console.log(`========================================\n`);

  process.exit(failed > 0 ? 1 : 0);
}

runAll();
