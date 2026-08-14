// ==================== 上游 API 搜索代理 ====================
const axios = require('axios');
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const { LRUCache } = require('lru-cache');
const { withNeteaseCookie } = require('./cookie');
const { writeLog } = require('./logger');

// ==================== 上游 API 超时控制 ====================
const UPSTREAM_TIMEOUT = 10000; // 10s

function withTimeout(promise, ms, label) {
  let timer
  const timeoutPromise = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`Upstream timeout: ${label} (${ms}ms)`)), ms)
  })
  return Promise.race([promise, timeoutPromise]).finally(() => clearTimeout(timer))
}

// ==================== LRU 缓存（使用 lru-cache 库） ====================

const CACHE_TTL = 5 * 60 * 1000; // 5分钟

const searchCache = new LRUCache({
  max: 200,
  ttl: CACHE_TTL,
  updateAgeOnGet: true,
});

const urlCache = new LRUCache({
  max: 200,
  // QQ vkey URL 有效期仅数分钟（实测几分钟内返回 403），缓存 60s 足够覆盖重试/刷新，
  // 避免缓存过期 URL 导致播放失败；网易云 URL 不走此缓存
  ttl: 60 * 1000,
  updateAgeOnGet: true,
});

// ==================== 辅助: 搜索函数 (增强版) ====================

async function searchNetease(keyword, limit) {
  const extractSongs = (result) => {
    if (!result || result.body.code !== 200) return null
    const songs = result.body?.result?.songs || result.body?.result?.songs
    if (!songs?.length) return null
    return songs.map(s => {
      // 封面提取：兼容多种字段 + HTTP→HTTPS 升级(移动端HTTPS页面不拦截)
      let cover = ''
      if (s.al && s.al.picUrl) {
        cover = s.al.picUrl
      } else if (s.album && s.album.picUrl) {
        cover = s.album.picUrl
      } else if (s.album && s.album.blurPicUrl) {
        cover = s.album.blurPicUrl
      } else if (s.al && s.al.pic_str) {
        cover = `https://p2.music.126.net/${s.al.pic_str}.jpg`
      } else if (s.al && s.al.pic) {
        cover = `https://p2.music.126.net/${s.al.pic}.jpg`
      }
      // 网易云 CDN 支持 HTTPS，强制升级避免移动端混合内容拦截
      if (cover.startsWith('http://')) {
        cover = cover.replace('http://', 'https://')
      }
      return {
        id: s.id, name: s.name,
        artists: (s.ar || s.artists || []).map(a => a.name).join(' / ') || '未知歌手',
        album: s.al ? s.al.name : (s.album ? s.album.name : ''),
        cover,
        duration: s.dt || 0,
        vip: (s.fee === 1 || s.fee === 4 || s.fee === 8 || s.st === -1),
        _raw: { playCount: s.pop || 0 },
      }
    })
  }

  // 尝试一个 API 调用（带重试）
  const call = async (apiFn, params, name) => {
    for (let i = 0; i <= 2; i++) {
      try {
        const r = await apiFn(params)
        const songs = extractSongs(r)
        if (songs) return songs
        if (i < 2) await new Promise(r => setTimeout(r, 500 * (i + 1)))
      } catch (e) {
        if (i < 2) {
          await new Promise(r => setTimeout(r, 500 * (i + 1)))
        } else {
          throw e
        }
      }
    }
    return []
  }

  const makeParam = (withCookie) => {
    const base = { keywords: keyword, limit, type: 1 }
    return withCookie ? withNeteaseCookie(base) : base
  }

  // 策略链: cloudsearch(cookie) → search(cookie) → cloudsearch(无cookie) → search(无cookie)
  const strategies = [
    ['cloudsearch', true],
    ['search', true],
    ['cloudsearch', false],
    ['search', false],
  ]

  for (const [fnName, withCookie] of strategies) {
    const fn = NeteaseCloudMusicApi[fnName]
    if (!fn) continue
    try {
      const result = await withTimeout(
        call(fn, makeParam(withCookie), fnName + (withCookie ? '+cookie' : '-cookie')),
        UPSTREAM_TIMEOUT, 'netease/' + fnName
      )
      if (result.length > 0) return result
    } catch (e) {
      // 继续下一种策略（仅所有策略失败时打印一次）
    }
  }

  console.error('[Netease] 所有搜索策略均失败，请检查 Cookie / 网络')
  return []
}

async function searchQQ(keyword, limit) {
  // 使用经典 c.y.qq.com 搜索接口（无需 Cookie），u.y.qq.com 新版接口需登录已弃用
  const searchUrl = `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${encodeURIComponent(keyword)}&format=json&p=1&n=${limit}&cr=1&aggr=1`;
  try {
    const resp = await axios.get(searchUrl, {
      headers: { Referer: 'https://y.qq.com', 'User-Agent': 'Mozilla/5.0' },
      timeout: UPSTREAM_TIMEOUT,
    });
    const data = resp.data;
    if (data.code !== 0 || !data.data?.song?.list) {
      writeLog('degradation', 'WARN', `QQ搜索异常: code=${data.code}, keyword=${keyword}`);
      return [];
    }
    return data.data.song.list.map(s => {
      let cover = '';
      if (s.albummid) {
        cover = `https://y.gtimg.cn/music/photo_new/T002R300x300M000${s.albummid}.jpg`;
      }
      return {
        id: s.songmid,
        name: s.songname,
        artists: s.singer ? s.singer.map(a => a.name).join(' / ') : '',
        album: s.albumname || '',
        cover: cover,
        duration: s.interval ? s.interval * 1000 : 0,
        vip: !!(s.pay && s.pay.pay_play),
        _raw: { listenCount: s.listennum || 0 },
      };
    });
  } catch (error) {
    writeLog('degradation', 'ERROR', `QQ搜索失败: keyword=${keyword}, ${error.message}`);
    return [];
  }
}

module.exports = {
  UPSTREAM_TIMEOUT,
  withTimeout,
  searchCache,
  urlCache,
  searchNetease,
  searchQQ,
};