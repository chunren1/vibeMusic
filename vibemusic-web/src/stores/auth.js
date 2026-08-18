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
  const sessionRestored = ref(false)
  let _restorePending = null

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
    if (sessionRestored.value) return
    if (_restorePending) return _restorePending
    _restorePending = (async () => {
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
      } catch (_) { /* 未登录 */ }
      sessionChecked.value = true
      _restorePending = null
    })()
    return _restorePending
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
