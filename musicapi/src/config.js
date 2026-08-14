// ==================== 统一配置加载 ====================
// 优先使用 musicapi/config.js（本地开发，gitignore 不入库）；
// 全新 checkout / Docker 构建中该文件缺失时回退到环境变量，保证服务可启动。
//
// 环境变量：
//   MUSIC_QQ_COOKIE      — QQ音乐 Cookie JSON 对象
//   MUSIC_NETEASE_COOKIE  — 网易云音乐 Cookie 字符串

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

module.exports = {
  // QQ音乐 Cookie（JSON 对象）
  qq: localConfig.qq || parseQQCookie(process.env.MUSIC_QQ_COOKIE),

  // 网易云 Cookie（字符串）
  netease: localConfig.netease || process.env.MUSIC_NETEASE_COOKIE || '',

  // 允许的网易云 API 方法白名单
  neteaseApis: localConfig.neteaseApis || [
    'cloudsearch', 'search', 'song_url_v1', 'song_detail', 'lyric',
    'personalized', 'toplist', 'toplist_detail', 'playlist_detail',
    'artist_songs', 'album', 'banner', 'login_status', 'user_detail',
  ],
};
