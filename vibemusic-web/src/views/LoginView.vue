<script setup>
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'

const authStore = useAuthStore()
const router = useRouter()

const username = ref('')
const password = ref('')
const errorMsg = ref('')

async function handleLogin() {
  errorMsg.value = ''
  if (!username.value || !password.value) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  // TODO: 调用登录 API
  console.log('登录:', username.value, password.value)
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="title">登录</h1>

      <div class="form">
        <input
          v-model="username"
          placeholder="用户名"
          class="input"
        />
        <input
          v-model="password"
          type="password"
          placeholder="密码"
          class="input"
          @keyup.enter="handleLogin"
        />
        <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
        <button @click="handleLogin" class="btn">登 录</button>
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
}

.login-card {
  width: 360px;
  padding: 40px;
  background: #1e2024;
  border-radius: 12px;
  border: 1px solid #2a2a2a;
}

.title {
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  text-align: center;
  margin-bottom: 32px;
}

.form { display: flex; flex-direction: column; gap: 16px; }

.input {
  padding: 12px 16px;
  border: 1px solid #333;
  border-radius: 8px;
  background: #16181c;
  color: #fff;
  font-size: 14px;
  outline: none;
}
.input:focus { border-color: #ec4141; }
.input::placeholder { color: #666; }

.error { color: #ec4141; font-size: 13px; text-align: center; }

.btn {
  padding: 12px;
  border: none;
  border-radius: 8px;
  background: #ec4141;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: background .2s;
}
.btn:hover { background: #d63434; }
</style>
