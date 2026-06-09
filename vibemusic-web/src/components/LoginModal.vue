<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import request from '@/api/request'

const router = useRouter()

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:visible', 'success'])

const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const nickname = ref('')
const errorMsg = ref('')
const isRegister = ref(false)
const loading = ref(false)

// 关闭弹窗
function close() {
  emit('update:visible', false)
  resetForm()
}

// 重置表单
function resetForm() {
  username.value = ''
  password.value = ''
  nickname.value = ''
  errorMsg.value = ''
  isRegister.value = false
}

// 监听 visible 变化
watch(() => props.visible, (val) => {
  if (!val) resetForm()
})

// 点击遮罩关闭
function onMaskClick(e) {
  if (e.target === e.currentTarget) {
    close()
  }
}

function onAuthSuccess() {
  emit('success')
  close()
  // Bug2修复: 登录后跳转到目标页面
  const redirect = authStore.consumeRedirect()
  if (redirect) {
    router.push(redirect)
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
        gender: res.data.gender,
        birthday: res.data.birthday,
      })
      onAuthSuccess()
    } else {
      errorMsg.value = res.message || '登录失败'
    }
  } catch (e) {
    // 优先取 axios 响应中的 msg，其次取拦截器 reject 的 error.message
    const msg = e.response?.data?.message || e.message || '登录失败，请检查用户名密码'
    errorMsg.value = msg
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
        avatar: res.data.avatar,
        gender: res.data.gender,
        birthday: res.data.birthday,
      })
      onAuthSuccess()
    } else {
      errorMsg.value = res.message || '注册失败'
    }
  } catch (e) {
    const msg = e.response?.data?.message || e.message || '注册失败'
    errorMsg.value = msg
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
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="modal-overlay" @click="onMaskClick">
        <div class="modal-content">
          <!-- 关闭按钮 -->
          <button class="close-btn" @click="close">×</button>
          
          <!-- 标题 -->
          <div class="modal-header">
            <h2 class="title">{{ isRegister ? '注册' : '登录' }}</h2>
            <p class="subtitle">{{ isRegister ? '创建新账号' : '欢迎回来' }}</p>
          </div>
          
          <!-- 表单 -->
          <div class="form">
            <!-- 欺骗浏览器的隐藏字段 -->
            <input type="text" style="display:none" autocomplete="username" />
            <input type="password" style="display:none" autocomplete="password" />
            
            <div class="input-group">
              <input 
                v-model="username" 
                type="text" 
                placeholder="用户名" 
                class="input"
                autocomplete="new-username"
              />
            </div>
            
            <div v-if="isRegister" class="input-group">
              <input 
                v-model="nickname" 
                type="text" 
                placeholder="昵称（可选）" 
                class="input"
                autocomplete="off"
              />
            </div>
            
            <div class="input-group">
              <input 
                v-model="password" 
                type="password" 
                placeholder="密码" 
                class="input"
                autocomplete="new-password"
                @keyup.enter="isRegister ? handleRegister() : handleLogin()"
              />
            </div>
            
            <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
            
            <button 
              v-if="!isRegister"
              class="submit-btn"
              :disabled="loading"
              @click="handleLogin"
            >
              {{ loading ? '登录中...' : '登 录' }}
            </button>
            <button 
              v-else
              class="submit-btn register"
              :disabled="loading"
              @click="handleRegister"
            >
              {{ loading ? '注册中...' : '注 册' }}
            </button>
          </div>
          
          <!-- 底部链接 -->
          <div class="modal-footer">
            <span class="toggle-text" @click="toggleMode">
              {{ isRegister ? '已有账号？去登录' : '没有账号？去注册' }}
            </span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
}

.modal-content {
  position: relative;
  width: 400px;
  padding: 40px 48px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.close-btn {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  font-size: 24px;
  color: #999;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: all 0.2s;
}

.close-btn:hover {
  background: #f5f5f5;
  color: #333;
}

.modal-header {
  text-align: center;
  margin-bottom: 32px;
}

.title {
  font-size: 24px;
  font-weight: 600;
  color: #333;
  margin: 0 0 8px 0;
}

.subtitle {
  font-size: 14px;
  color: #999;
  margin: 0;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.input-group {
  position: relative;
}

.input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  background: #fff;
  outline: none;
  transition: all 0.2s;
  box-sizing: border-box;
}

.input:focus {
  border-color: #31c27c;
  box-shadow: 0 0 0 3px rgba(49, 194, 124, 0.1);
}

.input::placeholder {
  color: #bbb;
}

.error-msg {
  color: #ec4141;
  font-size: 13px;
  text-align: center;
  margin: 0;
}

.submit-btn {
  width: 100%;
  padding: 14px;
  margin-top: 8px;
  border: none;
  border-radius: 8px;
  background: #31c27c;
  color: #fff;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.submit-btn:hover {
  background: #28a86b;
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.submit-btn.register {
  background: #5b3cc4;
}

.submit-btn.register:hover {
  background: #4a2fa3;
}

.modal-footer {
  margin-top: 24px;
  text-align: center;
}

.toggle-text {
  font-size: 14px;
  color: #31c27c;
  cursor: pointer;
  transition: all 0.2s;
}

.toggle-text:hover {
  text-decoration: underline;
}

/* 动画 */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-active .modal-content,
.modal-leave-active .modal-content {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from .modal-content,
.modal-leave-to .modal-content {
  transform: scale(0.9);
  opacity: 0;
}
</style>
