import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getToken, setToken, API_HOST } from '@/api/request'
import { getMe, updateProfile as apiUpdateProfile, uploadAvatar as apiUploadAvatar, uploadBgImage as apiUploadBgImage } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const user = ref(null)
  const showLoginModal = ref(false)
  const redirectPath = ref(null)
  const sessionChecked = ref(false)
  const sessionRestored = ref(false) // 标记 session 已从 cookie 恢复，不依赖假 token值

  const isLoggedIn = computed(() => sessionRestored.value && !!user.value)

  // 头像完整 URL
  const avatarSrc = computed(() => {
    const avatar = user.value?.avatar
    if (!avatar) return ''
    if (avatar.startsWith('http')) return avatar
    return API_HOST + avatar
  })

  // 背景图完整 URL
  const bgImageSrc = computed(() => {
    const bg = user.value?.bgImage
    if (!bg) return ''
    if (bg.startsWith('http')) return bg
    return API_HOST + bg
  })

  function login(newToken, newUser) {
    if (!newToken) {
      console.error('[Auth] login() called with null/empty token')
      return false
    }
    token.value = newToken
    user.value = newUser
    sessionChecked.value = true
    sessionRestored.value = true
    setToken(newToken)
    return true
  }

  function logout() {
    token.value = null
    user.value = null
    redirectPath.value = null
    showLoginModal.value = false
    sessionChecked.value = true
    sessionRestored.value = false
    setToken(null)
  }

  function openLogin() {
    showLoginModal.value = true
  }

  function closeLogin() {
    showLoginModal.value = false
  }

  function openLoginWithRedirect(path) {
    redirectPath.value = path
    showLoginModal.value = true
  }

  function consumeRedirect() {
    const p = redirectPath.value
    redirectPath.value = null
    return p
  }

  /** 从 httpOnly cookie 恢复会话（浏览器自动发送 Cookie，前端无需手动传 Token） */
  async function tryRestoreSession() {
    // 已恢复成功则跳过；未成功则允许重试（不再永久锁定）
    if (sessionRestored.value) return
    try {
      const res = await getMe()
      if (res.code === 200 && res.data) {
        sessionRestored.value = true
        user.value = {
          userId: res.data.userId,
          username: res.data.username,
          nickname: res.data.nickname,
          avatar: res.data.avatar,
          bgImage: res.data.bgImage,
          gender: res.data.gender,
          birthday: res.data.birthday,
        }
        // 尝试获取 Bearer token（用于跨域/内网穿透场景下 cookie 可能无法发送）
        try {
          const refreshRes = await fetch(`${API_HOST}/api/auth/refresh`, {
            method: 'POST',
            credentials: 'include',
          })
          if (refreshRes.ok) {
            const data = await refreshRes.json()
            if (data.code === 200 && data.data?.token) {
              token.value = data.data.token
              setToken(data.data.token)
            }
          }
        } catch (_) { /* cookie-based auth already works without Bearer token */ }
      }
    } catch (_) { /* 未登录，下次路由导航时自动重试 */ }
    sessionChecked.value = true
  }

  /** 从后端刷新用户信息 */
  async function refreshUser() {
    if (!sessionRestored.value) return null
    try {
      const res = await getMe()
      if (res.code === 200 && res.data) {
        user.value = {
          userId: res.data.userId,
          username: res.data.username,
          nickname: res.data.nickname,
          avatar: res.data.avatar,
          bgImage: res.data.bgImage,
          gender: res.data.gender,
          birthday: res.data.birthday,
        }
        return user.value
      }
    } catch (e) {
      console.warn('[Auth] refreshUser failed:', e.message)
    }
    return null
  }

  /** 更新用户资料 */
  async function updateUserProfile(data) {
    const res = await apiUpdateProfile(data)
    if (res.code === 200 && res.data) {
      user.value = { ...user.value, ...res.data }
    }
    return res
  }

  /** 上传头像 */
  async function uploadUserAvatar(file) {
    const res = await apiUploadAvatar(file)
    if (res.code === 200 && res.data) {
      user.value = { ...user.value, ...res.data }
    }
    return res
  }

  /** 上传背景图 */
  async function uploadUserBgImage(file) {
    const res = await apiUploadBgImage(file)
    if (res.code === 200 && res.data) {
      user.value = { ...user.value, ...res.data }
    }
    return res
  }

  return {
    token, user, isLoggedIn, avatarSrc, bgImageSrc, sessionChecked, sessionRestored,
    login, logout, tryRestoreSession,
    showLoginModal, openLogin, closeLogin,
    redirectPath, openLoginWithRedirect, consumeRedirect,
    refreshUser, updateUserProfile, uploadUserAvatar, uploadUserBgImage,
  }
})
