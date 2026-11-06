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
const inviteCode = ref('')
const inviteRequired = ref(false)
const errorMsg = ref('')
const isRegister = ref(false)
const loading = ref(false)

/** 与后端 RegistrationGuard 的白名单保持一致（字母/数字/下划线，3-30 位） */
const USERNAME_RE = /^[A-Za-z0-9_]{3,30}$/

/** 服务端是否需要邀请码；拿不到配置就按「不需要」处理（真要求时提交会返回 403 文案） */
async function loadRegisterConfig() {
  try {
    const res = await request.get('/auth/register-config')
    inviteRequired.value = res?.data?.inviteCodeRequired === true
  } catch {
    inviteRequired.value = false
  }
}

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
        avatar: res.data.avatar,
        bgImage: res.data.bgImage,
        gender: res.data.gender,
        birthday: res.data.birthday,
      })
      const redirect = /** @type {string} */ (route.query.redirect || '/')
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
  if (!USERNAME_RE.test(username.value.trim())) {
    errorMsg.value = '用户名只能包含字母、数字、下划线，3-30 位'
    return
  }
  // 原来是 length < 4，与后端（≥8，AuthRequest.RegisterRequest 的 @Size）不一致，
  // 前端放过去也会被后端打回，纯属让用户白填一次。
  if (password.value.length < 8) {
    errorMsg.value = '密码至少8位'
    return
  }
  if (inviteRequired.value && !inviteCode.value.trim()) {
    errorMsg.value = '请输入邀请码'
    return
  }
  loading.value = true
  try {
    const res = await request.post('/auth/register', {
      username: username.value.trim(),
      password: password.value,
      nickname: nickname.value || username.value,
      inviteCode: inviteCode.value.trim() || undefined,
    })
    if (res.code === 200) {
      authStore.login(res.data.token, {
        userId: res.data.userId,
        username: res.data.username,
        nickname: res.data.nickname,
        avatar: res.data.avatar,
        bgImage: res.data.bgImage,
        gender: res.data.gender,
        birthday: res.data.birthday,
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
  if (isRegister.value) loadRegisterConfig()
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="title">{{ isRegister ? '注册' : '登录' }}</h1>

      <div class="form">
        <!-- 欺骗浏览器密码管理器 - 隐藏的诱饵字段 -->
        <input type="text" style="display:none" autocomplete="username" />
        <input type="password" style="display:none" autocomplete="current-password" />
        
        <input v-model="username" placeholder="用户名" class="input" autocomplete="new-username" />
        <input
          v-if="isRegister"
          v-model="nickname"
          placeholder="昵称（可选）"
          class="input"
          autocomplete="off"
        />
        <input
          v-if="isRegister && inviteRequired"
          v-model="inviteCode"
          placeholder="邀请码"
          class="input"
          autocomplete="off"
        />
        <input
          v-model="password"
          type="password"
          placeholder="密码"
          class="input"
          autocomplete="new-password"
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
  background: var(--bg-base);
}
.login-card {
  width: 380px;
  padding: 44px 40px;
  background: var(--bg-card);
  border-radius: 14px;
  box-shadow: var(--shadow-2);
}
.title {
  font-size: 24px; font-weight: 700; color: var(--text-primary);
  text-align: center; margin-bottom: 32px;
}
.form { display: flex; flex-direction: column; gap: 16px; }
.input {
  padding: 12px 16px; border: 1px solid var(--bg-hover); border-radius: 8px;
  background: var(--bg-elevated); color: var(--text-primary); font-size: 14px; outline: none;
}
.input:focus { border-color: var(--primary); background: var(--bg-hover); }
.input::placeholder { color: var(--text-tertiary); }
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
