<script setup>
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useRouter, useRoute } from 'vue-router'
import request from '@/api/request'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const nickname = ref('')
const errorMsg = ref('')
const isRegister = ref(false)
const loading = ref(false)

async function handleLogin() {
  errorMsg.value = ''
  if (!username.value || !password.value) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    const res = await request.post('/auth/login', {
      username: username.value,
      password: password.value,
    })
    if (res.code === 200) {
      authStore.login(res.data.token, {
        userId: res.data.userId,
        username: res.data.username,
        nickname: res.data.nickname,
      })
      const redirect = route.query.redirect || '/'
      router.push(redirect)
    } else {
      errorMsg.value = res.message || '登录失败'
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.message || '登录失败，请检查用户名密码'
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  errorMsg.value = ''
  if (!username.value || !password.value) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  if (password.value.length < 4) {
    errorMsg.value = '密码至少4位'
    return
  }
  loading.value = true
  try {
    const res = await request.post('/auth/register', {
      username: username.value,
      password: password.value,
      nickname: nickname.value || username.value,
    })
    if (res.code === 200) {
      authStore.login(res.data.token, {
        userId: res.data.userId,
        username: res.data.username,
        nickname: res.data.nickname,
      })
      router.push('/')
    } else {
      errorMsg.value = res.message || '注册失败'
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.message || '注册失败'
  } finally {
    loading.value = false
  }
}

function toggleMode() {
  isRegister.value = !isRegister.value
  errorMsg.value = ''
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="title">{{ isRegister ? '注册' : '登录' }}</h1>

      <div class="form">
        <input v-model="username" placeholder="用户名" class="input" autocomplete="username" />
        <input
          v-if="isRegister"
          v-model="nickname"
          placeholder="昵称（可选）"
          class="input"
        />
        <input
          v-model="password"
          type="password"
          placeholder="密码"
          class="input"
          autocomplete="current-password"
          @keyup.enter="isRegister ? handleRegister() : handleLogin()"
        />
        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
        <button
          v-if="!isRegister"
          @click="handleLogin"
          class="btn"
          :disabled="loading"
        >{{ loading ? '登录中...' : '登 录' }}</button>
        <button
          v-else
          @click="handleRegister"
          class="btn register-btn"
          :disabled="loading"
        >{{ loading ? '注册中...' : '注 册' }}</button>
        <p class="toggle" @click="toggleMode">
          {{ isRegister ? '已有账号？去登录' : '没有账号？去注册' }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
}
.login-card {
  width: 380px;
  padding: 44px 40px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 4px 24px rgba(0,0,0,.08);
}
.title {
  font-size: 24px; font-weight: 700; color: #1a1a1a;
  text-align: center; margin-bottom: 32px;
}
.form { display: flex; flex-direction: column; gap: 16px; }
.input {
  padding: 12px 16px; border: 1px solid #e0e0e0; border-radius: 8px;
  background: #f9f9f9; color: #333; font-size: 14px; outline: none;
}
.input:focus { border-color: #31c27c; background: #fff; }
.input::placeholder { color: #bbb; }
.error { color: #ec4141; font-size: 13px; text-align: center; margin: 0; }
.btn {
  padding: 12px; border: none; border-radius: 8px;
  background: #31c27c; color: #fff; font-size: 16px;
  font-weight: 600; cursor: pointer; transition: .2s;
}
.btn:hover { background: #28a86b; }
.btn:disabled { opacity: .6; cursor: default; }
.register-btn { background: #5b3cc4; }
.register-btn:hover { background: #4a2fa3; }
.toggle {
  text-align: center; color: #31c27c; font-size: 13px;
  cursor: pointer; margin-top: 4px;
}
.toggle:hover { text-decoration: underline; }
</style>
