import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export const useAuthStore = defineStore('auth', () => {
  // ---------- 状态 ----------
  const token = ref(localStorage.getItem('token'))
  const user = ref(tryParse(localStorage.getItem('user')))

  // ---------- 计算属性 ----------
  const isLoggedIn = computed(() => !!token.value)

  // ---------- 方法 ----------
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

  return { token, user, isLoggedIn, login, logout }
})

function tryParse(str) {
  if (!str) return null
  try {
    return JSON.parse(str)
  } catch {
    return null
  }
}
