import request from './request'

/** 注册 */
export function register(data) {
  return request.post('/auth/register', data)
}

/** 登录 */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 获取当前用户信息 */
export function getMe() {
  return request.get('/auth/me')
}

/** 登出：通知后端销毁会话（best-effort，调用方必须吞掉网络错误） */
export function logout() {
  return request.post('/auth/logout', {}, { timeout: 5000 })
}

/** 修改密码 */
export function changePassword(data) {
  return request.post('/auth/change-password', data)
}

/** 更新个人资料 */
export function updateProfile(data) {
  return request.put('/auth/profile', data)
}

/** 上传头像 */
export function uploadAvatar(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/auth/avatar', formData)
}

/** 上传背景图 */
export function uploadBgImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/auth/bg-image', formData)
}

// Phase-2 note: settings/profile row (网易 Cookie 绑定状态行) CUT —
// 需要新路由/移动端镜像，超出本次 trivial 配额；待 Phase-2 复用现有设置行组件补齐。本文件仅交付 API。

/** 绑定网易云 Cookie（内存/服务端持有，前端不落地 localStorage） */
export function saveNeteaseCookie(cookie) {
  return request.put('/cookies/netease', { cookie })
}

/** 解绑网易云 Cookie */
export function deleteNeteaseCookie() {
  return request.delete('/cookies/netease')
}

/** 查询 Cookie 绑定状态（含 bili reserved 占位） */
export function cookieStatus() {
  return request.get('/cookies/status')
}
