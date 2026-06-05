import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token'))
  const user = ref(tryParse(localStorage.getItem('user')))
  const showLoginModal = ref(false)
  const redirectPath = ref(null)  // Bug2: 登录后跳转目标页面

  const isLoggedIn = computed(() => !!token.value)

  function login(newToken, newUser) {
    token.value = newToken
    user.value = newUser
    localStorage.setItem('token', newToken)
    localStorage.setItem('user', JSON.stringify(newUser))
  }

  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem('token')
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
