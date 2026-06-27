// vibeMusic 压力测试脚本 (k6)
// 安装: winget install k6  或  choco install k6
// 运行: k6 run --vus 50 --duration 30s k6-test.js
//
// 测试场景:
//   - 50 个虚拟用户并发
//   - 模拟真实用户行为: 搜索 → 获取歌词 → 播放 → 收藏 → 推荐
//   - 观察三层缓存(Redis→ES→API)下的性能瓶颈

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ---- 自定义指标 ----
const searchTrend = new Trend('search_duration');
const streamTrend = new Trend('stream_first_byte');
const lyricTrend = new Trend('lyric_duration');
const favoriteTrend = new Trend('favorite_duration');
const errorRate = new Rate('errors');

// ---- 配置 ----
const BASE = __ENV.API_HOST || 'http://localhost:8080';
const SEARCH_KEYWORDS = ['周杰伦', '晴天', '陈奕迅', '告白气球', '稻香', '夜曲', '七里香'];

export const options = {
  vus: 50,
  duration: '60s',
  thresholds: {
    'search_duration': ['p(95)<3000'],
    'stream_first_byte': ['p(95)<2000'],
    'errors': ['rate<0.10'],
  },
};

// ---- Setup: 获取 JWT ----
export function setup() {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({
    username: 'admin', password: '123456',
  }), { headers: { 'Content-Type': 'application/json' } });

  const token = res.json('data.token');
  console.log(`[Setup] Login result: ${res.status}, token=${token ? 'OK' : 'FAIL'}`);
  return { token };
}

// ---- 主测试逻辑 ----
export default function (data) {
  const headers = data.token
    ? { Authorization: `Bearer ${data.token}`, 'X-Request-Id': `${__VU}-${__ITER}-${Date.now()}` }
    : { 'X-Request-Id': `${__VU}-${__ITER}-${Date.now()}` };

  const keyword = SEARCH_KEYWORDS[Math.floor(Math.random() * SEARCH_KEYWORDS.length)];

  // 1. 搜索歌曲
  group('search', () => {
    const start = Date.now();
    const res = http.get(`${BASE}/api/songs/search?keyword=${encodeURIComponent(keyword)}&page=1&size=10`, { headers });
    searchTrend.add(Date.now() - start);
    const ok = check(res, { 'search 200': (r) => r.status === 200 });
    if (!ok) errorRate.add(1);

    let songs = [];
    try {
      const searchResult = res.json('data');
      songs = (searchResult && searchResult.list) || [];
    } catch (e) {}

    // 2. 如果有结果，获取第一首的歌词 + 播放
    if (songs.length > 0) {
      const song = songs[0];

      group('lyric', () => {
        const start = Date.now();
        const lrc = http.get(`${BASE}/api/songs/lyric?sourceId=${encodeURIComponent(song.sourceId)}`, { headers });
        lyricTrend.add(Date.now() - start);
        check(lrc, { 'lyric 200': (r) => r.status === 200 });
      });

      group('stream', () => {
        const start = Date.now();
        const stream = http.get(`${BASE}/api/songs/stream?sourceId=${encodeURIComponent(song.sourceId)}&name=${encodeURIComponent(song.name||'')}`, {
          headers: { ...headers, Range: 'bytes=0-65535' },
          timeout: '10s',
        });
        streamTrend.add(Date.now() - start);
        check(stream, { 'stream 2xx': (r) => r.status >= 200 && r.status < 400 });
      });

      // 3. 收藏/取消收藏 (幂等)
      if (data.token) {
        group('favorite', () => {
          const start = Date.now();
          const fav = http.post(`${BASE}/api/favorites/toggle`, JSON.stringify({
            sourceId: song.sourceId,
            songName: song.name || keyword || '',
            artist: song.artist || '未知',
            coverUrl: '',
          }), { headers: { ...headers, 'Content-Type': 'application/json' } });
          favoriteTrend.add(Date.now() - start);
          check(fav, { 'favorite 200': (r) => r.status === 200 || r.status === 201 });
        });
      }
    }
  });

  // 4. 个性化推荐（已登录用户）
  if (data.token) {
    group('recommend', () => {
      const res = http.get(`${BASE}/api/recommend/personalized?refresh=false`, { headers });
      check(res, { 'recommend 200': (r) => r.status === 200 });
    });
  }

  // 5. 首页轮播（公开接口，测试缓存）
  group('banner', () => {
    const res = http.get(`${BASE}/api/songs/banner`, { headers });
    check(res, { 'banner 200': (r) => r.status === 200 });
  });

  sleep(Math.random() * 2 + 0.5); // 模拟真实用户 0.5~2.5s 间隔
}

// ---- Teardown ----
export function teardown(data) {
  console.log('[Teardown] Test completed.');
}

// ---- 结果解读 ----
// search_duration     : 搜索耗时 → 看三级缓存命中率
// stream_first_byte   : 音频流首字节 → 看 RustFS/API 延迟
// lyric_duration      : 歌词耗时 → 看 API 响应
// favorite_duration   : 收藏耗时 → 看 DB 写入 + Redis 幂等
// errors              : 错误率 → 超过 5% 需排查
