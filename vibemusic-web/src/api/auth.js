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
