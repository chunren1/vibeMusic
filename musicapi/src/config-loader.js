// ==================== 统一配置加载 ====================
// 优先使用 musicapi/config.js（本地开发，gitignore 不入库）；
// 全新 checkout / Docker 构建中该文件缺失时回退到环境变量，保证服务可启动。
//
// 环境变量：
//   MUSIC_QQ_COOKIE      — QQ音乐 Cookie JSON 对象
//   MUSIC_NETEASE_COOKIE  — 网易云音乐 Cookie 字符串（主）
//   MUSIC_NETEASE_COOKIE_BACKUP — 网易云音乐 Cookie 字符串（备，主失效时自动切换）
//   MUSIC_NETEASE_PROBE_TRACK_ID — VIP 播放能力探针曲目 id（默认 273114《失眠》）
//   MUSIC_NETEASE_PROBE_ENABLED  — 能力探针开关（'false'/'0'/'off' 关闭，关闭后行为与旧版一致）

let localConfig = {};
try {
  localConfig = require('../config.js');
} catch (e) {
  // config.js 不存在（gitignore 文件未随 checkout/Docker 上下文提供），静默回退 env
}

function parseQQCookie(raw) {
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch (e) {
    console.error('[config] MUSIC_QQ_COOKIE JSON 解析失败，回退为空对象');
    return {};
  }
}

/** 布尔开关解析：只有显式 false/0/off（大小写不敏感）才关闭，缺失/其它值一律视为开启。 */
function parseBool(raw, defaultValue) {
  if (raw === undefined || raw === null || raw === '') return defaultValue;
  return !/^(false|0|off|no)$/i.test(String(raw).trim());
}

module.exports = {
  // QQ音乐 Cookie（JSON 对象）
  qq: localConfig.qq || parseQQCookie(process.env.MUSIC_QQ_COOKIE),

  // 网易云 Cookie（字符串）
  netease: localConfig.netease || process.env.MUSIC_NETEASE_COOKIE || '',

  // 网易云备用 Cookie（字符串，主失效时自动切换；未配置则保持单 Cookie 行为）
  neteaseBackup: localConfig.neteaseBackup || process.env.MUSIC_NETEASE_COOKIE_BACKUP || '',

  // VIP 播放能力探针曲目 id（VIP-only 曲目；留空则用探针模块内置默认值）
  neteaseProbeTrackId: localConfig.neteaseProbeTrackId || process.env.MUSIC_NETEASE_PROBE_TRACK_ID || '',

  // VIP 播放能力探针开关（kill switch：设为 false 时健康判定回退到"仅登录有效性"，行为与旧版一致）
  neteaseProbeEnabled: parseBool(
    localConfig.neteaseProbeEnabled !== undefined
      ? localConfig.neteaseProbeEnabled
      : process.env.MUSIC_NETEASE_PROBE_ENABLED,
    true
  ),

  // QQ 音乐源开关（2026-10-27）。QQ 会员到期后该源大量曲目不可播，
  // 先把整个源停用（聚合搜索不再混入 QQ 结果），**代码与端点全部保留**：
  // 充值后把 MUSICAPI_QQ_ENABLED 设回 true 重启容器即可恢复，不需要改代码。
  // 做成函数而不是静态值：测试需要在同进程内切换开关，静态值会读到 require 时的旧环境。
  isQQEnabled: () => parseBool(
    localConfig.qqEnabled !== undefined ? localConfig.qqEnabled : process.env.MUSICAPI_QQ_ENABLED,
    true,
  ),

  // 允许的网易云 API 方法白名单
  neteaseApis: localConfig.neteaseApis || [
    'cloudsearch', 'search', 'song_url_v1', 'song_detail', 'lyric',
    'personalized', 'toplist', 'toplist_detail', 'playlist_detail',
    'artist_songs', 'album', 'banner', 'login_status', 'user_detail',
  ],
};
