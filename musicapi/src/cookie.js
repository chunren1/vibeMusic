// ==================== Cookie 统一管理（组合层）====================
//
// 2026-10-27 网关重构 pass 3：本文件原本 337 纯 LOC（超 250 硬约束），按职责拆成三块，
// 这里只做组合 + re-export，**保持 `require('./cookie')` 旧契约不变**（20+ 调用点零改动）：
//
//   ./cookie-slots.js  —— 网易云槽位状态（主/备）、per-request 用户 Cookie、gauge 口径
//   ./cookie-patrol.js —— 巡检（checkCookies）与快切（rotate*）
//   ./cookie-qq.js     —— QQ Cookie（检查/重载/请求头字符串）
//
// ⚠ 循环 require 陷阱（2026-10-27 实测）：冷启动链路是
//   cookie.js → cookie-patrol.js → cookie-probe.js → sources/netease.js → (调用时) ../cookie
// 因此**本文件里不许有任何业务逻辑**，也绝不能被下游顶层 require 回来。
// 回归守卫：test/netease-cold-start.test.js 用独立子进程复现该顺序。
//
// 加载顺序有意固定为 slots → qq → patrol：启动日志保持原顺序（网易云在前、QQ 在后）。

const slots = require('./cookie-slots');
const qq = require('./cookie-qq');
const patrol = require('./cookie-patrol');
// 探针原语由 cookie.js 转出（历史上调用方从这里取，勿改来源）。
const {
  probeNeteaseCookie,
  probeNeteaseLogin,
  probeNeteasePlayback,
} = require('./cookie-probe');

module.exports = {
  // 槽位与注入
  cookieStatus: slots.cookieStatus,
  withNeteaseCookie: slots.withNeteaseCookie,
  resolveNeteaseCookie: slots.resolveNeteaseCookie,
  resolveSharedCookie: slots.resolveSharedCookie,
  getNeteaseActive: slots.getNeteaseActive,
  decideActiveSlot: slots.decideActiveSlot,
  vipHealthState: slots.vipHealthState,
  getVipHealth: slots.getVipHealth,
  setSlotGauges: slots.setSlotGauges,
  // 探针原语
  probeNeteaseCookie,
  probeNeteaseLogin,
  probeNeteasePlayback,
  // 巡检与快切
  rotateNeteaseActive: patrol.rotateNeteaseActive,
  rotateNeteaseActiveForced: patrol.rotateNeteaseActiveForced,
  pickCapableSlot: patrol.pickCapableSlot,
  checkCookies: patrol.checkCookies,
  // QQ
  hasQQCookie: qq.hasQQCookie,
  checkQQCookie: qq.checkQQCookie,
  failQQCookie: qq.failQQCookie,
  reloadQQCookie: qq.reloadQQCookie,
  getQQCookieString: qq.getQQCookieString,
  // 测试缝
  _setSharedForTest: slots._setSharedForTest,
  _resetSharedForTest: slots._resetSharedForTest,
};
