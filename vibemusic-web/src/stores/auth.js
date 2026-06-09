import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getToken, setToken, API_HOST } from '@/api/request'
import { getMe, updateProfile as apiUpdateProfile, uploadAvatar as apiUploadAvatar, uploadBgImage as apiUploadBgImage } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const user = ref(tryParse(localStorage.getItem('user')))
  const showLoginModal = ref(false)
  const redirectPath = ref(null)

  const isLoggedIn = computed(() => !!token.value)

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
    setToken(newToken)
    localStorage.setItem('user', JSON.stringify(newUser))
    return true
  }

  function logout() {
    token.value = null
    user.value = null
    redirectPath.value = null
    showLoginModal.value = false
    setToken(null)
    localStorage.removeItem('user')
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

  /** 从后端刷新用户信息 */
  async function refreshUser() {
    if (!token.value) return null
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
        localStorage.setItem('user', JSON.stringify(user.value))
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
      // 合并更新本地 user
      user.value = { ...user.value, ...res.data }
      localStorage.setItem('user', JSON.stringify(user.value))
    }
    return res
  }

  /** 上传头像 */
  async function uploadUserAvatar(file) {
    const res = await apiUploadAvatar(file)
    if (res.code === 200 && res.data) {
      user.value = { ...user.value, ...res.data }
      localStorage.setItem('user', JSON.stringify(user.value))
    }
    return res
  }

  /** 上传背景图 */
  async function uploadUserBgImage(file) {
    const res = await apiUploadBgImage(file)
    if (res.code === 200 && res.data) {
      user.value = { ...user.value, ...res.data }
      localStorage.setItem('user', JSON.stringify(user.value))
    }
    return res
  }

  return {
    token, user, isLoggedIn, avatarSrc, bgImageSrc,
    login, logout,
    showLoginModal, openLogin, closeLogin,
    redirectPath, openLoginWithRedirect, consumeRedirect,
    refreshUser, updateUserProfile, uploadUserAvatar, uploadUserBgImage,
  }
})

function tryParse(str) {
  if (!str) return null
  try { return JSON.parse(str) } catch { return null }
}
