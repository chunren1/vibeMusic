// ==================== vibeMusic 统一 Cookie 管理中心（示例） ====================
// 所有音乐平台的 Cookie/Token 集中于此，由 musicapi 统一管理
// 后端不再持有任何 Cookie，所有请求均走 musicapi 代理
//
// 使用方法：复制此文件为 config.js，填入你的真实 Cookie
//   cp musicapi/config.example.js musicapi/config.js
//
// 获取方式：
//   网易云: 浏览器登录 music.163.com → F12 → Application → Cookies → 复制 MUSIC_U
//   QQ音乐: 浏览器登录 y.qq.com → F12 → Application → Cookies → 复制对应字段
//
// ⚠️  config.js 已在 .gitignore 中排除，不要提交到 git！
// ======================================================================

module.exports = {
  // ---- QQ音乐 Cookie ----
  qq: {
    uin: 'YOUR_QQ_UIN',
    qqmusic_key: 'YOUR_QQMUSIC_KEY',
    psrf_qqaccess_token: 'YOUR_ACCESS_TOKEN',
    psrf_qqopenid: 'YOUR_OPENID',
    psrf_qqrefresh_token: 'YOUR_REFRESH_TOKEN',
    psrf_qqunionid: 'YOUR_UNIONID',
    ptcz: 'YOUR_PTCZ',
    qm_keyst: 'YOUR_QM_KEYST',
    tmeLoginType: '2',
  },

  // ---- 网易云 Cookie ----
  netease: 'MUSIC_U=YOUR_MUSIC_U_COOKIE; __csrf=YOUR_CSRF_TOKEN',
};
