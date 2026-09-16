import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getToken, setToken, refreshOnce, API_HOST } from '@/api/request'
import { getMe, logout as apiLogout, updateProfile as apiUpdateProfile, uploadAvatar as apiUploadAvatar, uploadBgImage as apiUploadBgImage } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const user = ref(null)
  const showLoginModal = ref(false)
  const redirectPath = ref(null)
  const sessionChecked = ref(false)
  const sessionRestored = ref(false)
  let _restorePending = null

  const isLoggedIn = computed(() => sessionRestored.value && !!user.value?.userId)

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
    // 登录成功后拉取本账号收藏（动态导入避免与 favorite store 循环依赖）
    try {
      import('@/stores/favorite').then(({ useFavoriteStore }) => {
        useFavoriteStore().fetchFavIds()
      }).catch(() => {})
    } catch (_) { /* ignore */ }
    return true
  }

  function logout() {
    // best-effort：通知后端销毁会话；网络错误一律吞掉，本地清理照常进行。
    // fire-and-forget（不同步 await），保持同步调用方（路由守卫、401 拦截器）行为不变。
    try {
      const p = apiLogout()
      if (p && typeof p.catch === 'function') p.catch(() => {})
    } catch (_) { /* ignore */ }
    token.value = null
    user.value = null
    redirectPath.value = null
    showLoginModal.value = false
    sessionChecked.value = true
    sessionRestored.value = false
    setToken(null)
    // 同步清收藏镜像 + 异步重置 favorite store，防止同标签页换账号残留上一用户收藏态
    window.vibeFavIds = new Set()
    try {
      import('@/stores/favorite').then(({ useFavoriteStore }) => {
        const fav = useFavoriteStore()
        fav.favIds = new Set()
        fav.loaded = false
        window.vibeFavIds = fav.favIds
      }).catch(() => {})
    } catch (_) { /* ignore */ }
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
          if (res.data.guest) {
            user.value = null
            sessionRestored.value = true
          } else {
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
            // 经 request.js 全局单飞入口续签：与 401 触发的静默续签共用 _refreshPromise，
            // 后端 refresh token 一次性轮换，并发 refresh 会互踢下线
            try {
              if (typeof refreshOnce === 'function') {
                const ok = await refreshOnce()
                if (ok === true) {
                  const t = getToken()
                  if (t) token.value = t
                }
              }
            } catch (_) { /* cookie-based auth already works without Bearer token */ }
            // 会话恢复后拉取本账号收藏（keep-alive 页面不重挂载，靠 store 同步）
            try {
              const { useFavoriteStore } = await import('@/stores/favorite')
              await useFavoriteStore().fetchFavIds()
            } catch (_) { /* 收藏拉取失败不影响登录态 */ }
          }
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
