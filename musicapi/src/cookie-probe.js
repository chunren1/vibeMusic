// ==================== 网易云 Cookie 探针（登录有效性 + 播放能力）====================
// 背景：cloudsearch 探活只证明"登录有效"，证明不了"还能播放 VIP 曲目"。
// 实测 2026-10：主 Cookie 掉 VIP 后 cloudsearch 依旧 200 有结果，
// 但 VIP-only 曲目取链只回 30s 试听（fee=1 && payed=0 && freeTrialInfo），
// 于是 decideActiveSlot 永远停在主槽、备 Cookie 永不接管，App 侧取链拿到试听被拒。
//
// 本模块补一条"播放能力"探针：对可配置的 VIP 曲目调 song_url_v1，
// 要求 url 非空且非试听。健康 = 登录有效性 且 播放能力（能力探针可被 kill switch 关闭）。
// 日志只打长度/槽位/结果，绝不打 Cookie 字节。
const NeteaseCloudMusicApi = require('NeteaseCloudMusicApi');
const config = require('./config-loader');
const { writeLog } = require('./logger');

/** 默认探针曲目：273114《失眠》（VIP-only；实测有 VIP 的 Cookie 返回 payed=1 完整链接）。 */
const DEFAULT_PROBE_TRACK_ID = '273114';

/** 能力探针开关（kill switch）：关闭时健康判定等价于旧 probeNeteaseCookie（仅登录有效性）。 */
function isCapabilityProbeEnabled() {
  return config.neteaseProbeEnabled !== false;
}

/** 当前探针曲目 id：配置优先，缺失回落内置默认值。 */
function probeTrackId() {
  return config.neteaseProbeTrackId || DEFAULT_PROBE_TRACK_ID;
}

/**
 * 取链响应可播判定（纯函数）：{@code data[0].url} 非空且无试听信息才是完整可播。
 * 试听兜底（fee=1 && payed=0 && freeTrialInfo）、空链、脏形一律判不可播。
 */
function isPlayableUrlPayload(body) {
  const item = body && Array.isArray(body.data) ? body.data[0] : null;
  if (!item || typeof item.url !== 'string' || !item.url) return false;
  return item.freeTrialInfo === null || item.freeTrialInfo === undefined;
}

/** 登录/搜索有效性探针：cloudsearch 200+有结果即有效；空串/异常判无效，绝不抛错。 */
async function probeNeteaseLogin(cookieStr) {
  if (!cookieStr) return false;
  try {
    const res = await NeteaseCloudMusicApi.cloudsearch({ keywords: '周杰伦', limit: 1, type: 1, cookie: cookieStr });
    return !!(res && res.body && res.body.code === 200 && res.body.result);
  } catch (e) {
    return false;
  }
}

/**
 * 播放能力探针：对 VIP 探针曲目取链，能拿到完整可播链接才算通过。
 * 异常与上游报错一律判不可播（与登录探针口径一致，绝不抛错，避免打断 15 分钟巡检）。
 */
async function probeNeteasePlayback(cookieStr, opts = {}) {
  if (!cookieStr) return false;
  const id = opts.trackId || probeTrackId();
  const level = opts.level || 'exhigh';
  try {
    const res = await NeteaseCloudMusicApi.song_url_v1({ id, level, cookie: cookieStr });
    return isPlayableUrlPayload(res && res.body);
  } catch (e) {
    return false;
  }
}

/**
 * 组合探针（槽位健康唯一口径）：健康 = 登录有效性 且 播放能力。
 * 能力探针被 kill switch 关闭时，行为与旧 probeNeteaseCookie 完全一致（只探登录）。
 */
async function probeNeteaseCookie(cookieStr) {
  const loginOk = await probeNeteaseLogin(cookieStr);
  if (!loginOk) return false;
  if (!isCapabilityProbeEnabled()) return true;
  const playable = await probeNeteasePlayback(cookieStr);
  if (!playable) {
    writeLog('cookie', 'WARN', `播放能力探针未通过（探针曲目 ${probeTrackId()} 无完整播放权限），该槽位判为不健康`);
  }
  return playable;
}

module.exports = {
  DEFAULT_PROBE_TRACK_ID,
  isCapabilityProbeEnabled,
  probeTrackId,
  isPlayableUrlPayload,
  probeNeteaseLogin,
  probeNeteasePlayback,
  probeNeteaseCookie,
};
