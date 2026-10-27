// 网易云源适配器 —— **全仓唯一** require('NeteaseCloudMusicApi') 的地方
//
// 2026-10-27 网关重构 pass 1：把散在 4 个文件的第三方库调用收成一道隔离墙。
// 改造前 require 点：search.js / routes.js / cookie-probe.js（共 3 个文件、5 处调用）。
// 收益：① 上游库升级只改这一个文件；② 换实现（自建客户端）只换这一层；
//      ③ 调用点全部走 invoke()，`typeof lib[x] === 'function'` 这种内省被关进 isKnown()。
//
// 不变量：**行为零变更**。searchNetease 的 4 策略降级链、重试节奏、日志文案原样搬过来，
// 唯一改动是把 `NeteaseCloudMusicApi[fnName]` 换成同文件内的 `lib[fnName]`。

const lib = require('NeteaseCloudMusicApi');
const { UPSTREAM_TIMEOUT, SEARCH_TOTAL_BUDGET, withTimeout } = require('./shared');
const { writeLog } = require('../logger');
const { okOutcome, failOutcome } = require('../source-health');

// ==================== 循环 require 陷阱（2026-10-27 实测踩到）====================
// 冷启动链路是 cookie.js(121 行 require cookie-probe) → cookie-probe.js → 本文件 → cookie.js。
// 也就是说**本文件被加载时 cookie.js 还没执行完**。此时：
//   ① 顶层解构 `const { withNeteaseCookie } = require('../cookie')` 拿到的是 undefined；
//   ② 连"先拿 module 对象、用时再取属性"也救不了 —— cookie.js 结尾是
//      `module.exports = {...}` 整体替换，加载时捕获的那个对象永远停在空壳。
// 后果不是报错而是**静默降级**：makeParam 抛 TypeError 被 call() 的重试/吞错链路吃掉，
// 4 条策略全失败，最终只留一行 all-strategies-failed，表现为"网易云永远搜不到歌"。
// 因此这里坚持**调用时**再 require（Node 有模块缓存，成本只是一次 Map 查找）。
// 回归守卫：test/netease-cold-start.test.js 用独立子进程复现冷启动顺序。
function cookieHelper() {
  return require('../cookie');
}

/** 上游是否存在该 API。取代调用点直接对第三方库做 typeof 内省。 */
function isKnown(apiName) {
  return typeof lib[apiName] === 'function';
}

/**
 * 统一的裸调用入口。**不做超时**——超时口径由调用点决定（search 用 withTimeout +
 * SEARCH_TOTAL_BUDGET，cookie-probe 用 withHardTimeout + 更短阈值），此处强行套一层会改行为。
 */
function invoke(apiName, params) {
  if (!isKnown(apiName)) {
    throw new Error(`netease api not available: ${apiName}`);
  }
  return lib[apiName](params);
}

async function searchNetease(keyword, limit, req) {
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
      // 译名/别名：网易把中文译名放在 tns（翻译名），alia 多为空。
      // 不透传会让「中文搜英文歌」彻底失配（原版曲名是英文，中文查询命中不了）。
      const aliasList = []
        .concat(Array.isArray(s.tns) ? s.tns : [])
        .concat(Array.isArray(s.alia) ? s.alia : [])
        .map(x => String(x == null ? '' : x).trim())
        .filter(Boolean)
      return {
        id: s.id, name: s.name,
        artists: (s.ar || s.artists || []).map(a => a.name).join(' / ') || '未知歌手',
        album: s.al ? s.al.name : (s.album ? s.album.name : ''),
        cover,
        duration: s.dt || 0,
        vip: (s.fee === 1 || s.fee === 4 || s.fee === 8 || s.st === -1),
        alias: aliasList,
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
    return withCookie ? cookieHelper().withNeteaseCookie(base, req) : base
  }

  // 策略链: cloudsearch(cookie) → search(cookie) → cloudsearch(无cookie) → search(无cookie)
  // 总预算 SEARCH_TOTAL_BUDGET 内串行降级，超时即早退（降级顺序不变）。
  const strategies = [
    ['cloudsearch', true],
    ['search', true],
    ['cloudsearch', false],
    ['search', false],
  ]

  const runStrategies = (async () => {
    for (const [fnName, withCookie] of strategies) {
      const fn = lib[fnName]
      if (!fn) continue
      try {
        const result = await withTimeout(
          call(fn, makeParam(withCookie), fnName + (withCookie ? '+cookie' : '-cookie')),
          UPSTREAM_TIMEOUT, 'netease/' + fnName
        )
        if (result.length > 0) return okOutcome('netease', result)
      } catch (e) {
        // 继续下一种策略（仅所有策略失败时打印一次）
      }
    }

    console.error('[Netease] 所有搜索策略均失败，请检查 Cookie / 网络')
    return failOutcome('netease', 'all-strategies-failed')
  })()

  try {
    return await withTimeout(runStrategies, SEARCH_TOTAL_BUDGET, 'netease/search-total')
  } catch (e) {
    writeLog('degradation', 'ERROR', `网易云搜索总预算超时(${SEARCH_TOTAL_BUDGET}ms)，降级为空: ${e.message}`)
    return failOutcome('netease', 'total-budget-timeout')
  }
}

module.exports = {
  isKnown,
  invoke,
  searchNetease,
};
