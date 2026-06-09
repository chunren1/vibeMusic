import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getToken, setToken } from '@/api/request'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const user = ref(tryParse(localStorage.getItem('user')))
  const showLoginModal = ref(false)
  const redirectPath = ref(null)  // Bug2: 登录后跳转目标页面

  const isLoggedIn = computed(() => !!token.value)

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

  return { token, user, isLoggedIn, login, logout, showLoginModal, openLogin, closeLogin, redirectPath, openLoginWithRedirect, consumeRedirect }
})

function tryParse(str) {
  if (!str) return null
  try { return JSON.parse(str) } catch { return null }
}
