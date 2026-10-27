// ==================== QQ 音乐上游调用件（取链 / 歌词 / 请求头）====================
//
// 2026-10-27 网关重构 pass 3：从 routes.js 原样搬出，**行为零变更**。
// 单独成文件的原因：这两段是"上游协议适配"（musicu.fcg 请求体 + vkey/CDN 探活），
// 与路由层的参数校验/响应信封无关，混在一起会让 qq-routes.js 超 250 纯 LOC。
const config = require('../config-loader');
const cookie = require('../cookie');
const { upstreamGet, describeUrl } = require('../upstream');
const { writeLog } = require('../logger');
const { UPSTREAM_TIMEOUT } = require('../search');

// QQ 上游请求头：Cookie 为可选项，仅在已配置时携带；无 Cookie 直接请求，服务端按无登录态返回
function qqRequestHeaders() {
  const headers = {
    Referer: 'https://y.qq.com',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  };
  const cookieStr = cookie.getQQCookieString();
  if (cookieStr) headers.Cookie = cookieStr;
  return headers;
}

// QQ 播放地址：无 Cookie 可用；VIP/鉴权限制内容返回 null（同形），任何异常都降级为 null，永不抛错
async function fetchQQPlayUrl(id) {
  try {
    const uin = (config.qq && config.qq.uin) || '0';
    const qqmusicKey = (config.qq && config.qq.qqmusic_key) || '';
    const reqData = JSON.stringify({
      req_0: {
        module: 'vkey.GetVkeyServer', method: 'CgiGetVkey',
        param: {
          filename: [`M800${id}.mp3`, `M500${id}.mp3`, `C400${id}.m4a`],
          guid: '126548448', songmid: [id], songtype: [0],
          uin, loginflag: 1, platform: '20', authst: qqmusicKey,
        },
      },
      comm: { uin, format: 'json', ct: 19, cv: 0 },
    });
    const qqResp = await upstreamGet('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      label: 'qq/vkey',
      params: { format: 'json', data: reqData },
      headers: qqRequestHeaders(),
      timeoutMs: UPSTREAM_TIMEOUT,
    });
    const mi = qqResp.data?.req_0?.data?.midurlinfo;
    const sip = qqResp.data?.req_0?.data?.sip?.[0] || 'https://aqqmusic.tc.qq.com';

    // vkey URL 有时效且部分歌曲 CDN 已失效：候选音质并行探测，取第一个可访问的
    // （此前串行逐个探测，最坏 3×2.5s 叠加；Promise.any 任一命中即返回）
    if (mi) {
      const candidates = [];
      for (const m of mi) {
        if (!m || !m.purl) continue;
        candidates.push(m.purl.includes('://') ? m.purl : sip + '/' + m.purl);
      }
      if (candidates.length > 0) {
        const probeOne = async (candidate) => {
          try {
            const probe = await upstreamGet(candidate, {
              label: 'qq/cdn-probe',
              headers: { 'User-Agent': 'Mozilla/5.0', Range: 'bytes=0-1023' },
              timeoutMs: 2500,
              validateStatus: () => true,
              maxRedirects: 0,
            });
            if (probe.status === 200 || probe.status === 206) return candidate;
            const ep = describeUrl(candidate);
            writeLog('api', 'INFO', `[/song/url/qq] ${id} CDN ${probe.status} 失效: ${ep.host}${ep.path}`);
            throw new Error(`CDN probe ${probe.status}`);
          } catch (e) {
            if (e.message && e.message.startsWith('CDN probe')) throw e;
            writeLog('api', 'INFO', `[/song/url/qq] ${id} 探测异常: ${e.message}`);
            throw e;
          }
        };
        try {
          return await Promise.any(candidates.map(probeOne));
        } catch (e) {
          writeLog('api', 'INFO', `[/song/url/qq] ${id} 全部音质 CDN 探测失败，降级(null)`);
        }
      }
    }
    return null;
  } catch (error) {
    writeLog('api', 'INFO', `[/song/url/qq] ${id} 取链降级(null): ${error.message}`);
    return null;
  }
}

// QQ 歌词：无 Cookie 可用；缺歌词/异常返回 null，永不抛错
async function fetchQQLyric(songmid) {
  try {
    const uin = (config.qq && config.qq.uin) || '0';
    const reqData = JSON.stringify({
      req_0: {
        module: 'music.musichallSong.PlayLyricInfo',
        method: 'GetPlayLyricInfo',
        param: { songMID: songmid, songID: 0, songType: 0, needNewOffset: 1 },
      },
      comm: { uin, format: 'json', ct: 19, cv: 0 },
    });
    const qqResp = await upstreamGet('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      label: 'qq/lyric',
      params: { format: 'json', data: reqData },
      headers: qqRequestHeaders(),
      timeoutMs: UPSTREAM_TIMEOUT,
    });
    const ly = qqResp.data?.req_0?.data?.lyric;
    if (!ly) return null;
    // QQ 返回 base64 编码的 LRC 文本
    return Buffer.from(ly, 'base64').toString('utf8');
  } catch (error) {
    writeLog('api', 'INFO', `[/qq/lyric] ${songmid} 歌词降级(空): ${error.message}`);
    return null;
  }
}

module.exports = {
  qqRequestHeaders,
  fetchQQPlayUrl,
  fetchQQLyric,
};
