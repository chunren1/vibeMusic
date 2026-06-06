<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginModal from '@/components/LoginModal.vue'
import request from '@/api/request'

const router = useRouter()
const auth = useAuthStore()
const showLogin = ref(false)
const showPwdModal = ref(false)
const oldPwd = ref('')
const newPwd = ref('')
const pwdError = ref('')
const pwdLoading = ref(false)

function openLogin() { showLogin.value = true }
function onLoginSuccess() { showLogin.value = false }

async function changePassword() {
  pwdError.value = ''
  if (!oldPwd.value || !newPwd.value) { pwdError.value = '请填写新旧密码'; return }
  if (newPwd.value.length < 4) { pwdError.value = '新密码至少4位'; return }
  pwdLoading.value = true
  try {
    await request.post('/auth/change-password', { oldPassword: oldPwd.value, newPassword: newPwd.value })
    showPwdModal.value = false
    oldPwd.value = ''; newPwd.value = ''
    alert('密码修改成功')
  } catch (e) {
    pwdError.value = e.response?.data?.message || e.message || '修改失败'
  } finally { pwdLoading.value = false }
}
</script>

<template>
  <div class="profile-page">
    <!-- 用户卡片 -->
    <div class="user-card">
      <div v-if="auth.isLoggedIn" class="logged-in">
        <div class="avatar">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</div>
        <div class="user-info">
          <div class="nickname">{{ auth.user?.nickname || auth.user?.username }}</div>
          <div class="uid">@{{ auth.user?.username }}</div>
        </div>
        <button class="logout-btn" @click="auth.logout()">退出登录</button>
      </div>
      <div v-else class="not-login" @click="openLogin">
        <div class="avatar ghost">?</div>
        <div class="user-info">
          <div class="nickname">点击登录</div>
          <div class="uid">登录后同步收藏和歌单</div>
        </div>
      </div>
    </div>

    <!-- 功能菜单 -->
    <div class="menu">
      <div class="menu-item" @click="!auth.isLoggedIn ? openLogin() : router.push('/recent')">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
        <span class="menu-label">最近播放</span>
        <span class="menu-arrow">›</span>
      </div>
      <div class="menu-item" @click="!auth.isLoggedIn ? openLogin() : router.push('/likes')">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
        <span class="menu-label">我的收藏</span>
        <span class="menu-arrow">›</span>
      </div>
      <div class="menu-item" @click="!auth.isLoggedIn ? openLogin() : router.push('/playlists')">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
        <span class="menu-label">我的歌单</span>
        <span class="menu-arrow">›</span>
      </div>
      <div v-if="auth.isLoggedIn" class="menu-item" @click="showPwdModal = true">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
        <span class="menu-label">修改密码</span>
        <span class="menu-arrow">›</span>
      </div>
    </div>

    <!-- 修改密码弹窗 -->
    <Teleport to="body">
      <div v-if="showPwdModal" class="pwd-overlay" @click="showPwdModal = false">
        <div class="pwd-modal" @click.stop>
          <h3 class="pwd-title">修改密码</h3>
          <input v-model="oldPwd" type="password" placeholder="原密码" class="pwd-input" />
          <input v-model="newPwd" type="password" placeholder="新密码（至少4位）" class="pwd-input" />
          <p v-if="pwdError" class="pwd-error">{{ pwdError }}</p>
          <div class="pwd-btns">
            <button class="pwd-cancel" @click="showPwdModal = false">取消</button>
            <button class="pwd-submit" @click="changePassword" :disabled="pwdLoading">{{ pwdLoading ? '修改中...' : '确认' }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <LoginModal v-model:visible="showLogin" @success="onLoginSuccess" />
  </div>
</template>

<style scoped>
.profile-page { padding: 32px; max-width: 600px; }
.user-card {
  background: #f5f5f5; border-radius: 14px; padding: 24px; margin-bottom: 20px;
}
.logged-in, .not-login { display: flex; align-items: center; gap: 16px; }
.avatar {
  width: 56px; height: 56px; border-radius: 50%; flex-shrink: 0;
  background: linear-gradient(135deg, #31c27c, #1a8a4a);
  display: flex; align-items: center; justify-content: center;
  font-size: 24px; font-weight: 700; color: #fff;
}
.avatar.ghost { background: #d9d9d9; color: #999; }
.user-info { flex: 1; }
.nickname { font-size: 18px; font-weight: 600; color: #1a1a1a; }
.uid { font-size: 13px; color: #888; margin-top: 3px; }
.logout-btn {
  padding: 6px 16px; border: 1px solid #ddd; border-radius: 16px;
  background: transparent; color: #888; font-size: 13px; cursor: pointer;
}
.menu { border-radius: 14px; overflow: hidden; background: #f5f5f5; }
.menu-item {
  display: flex; align-items: center; gap: 14px; padding: 16px 20px;
  border-bottom: 1px solid #e8e8e8; cursor: pointer; transition: background .15s;
}
.menu-item:last-child { border-bottom: none; }
.menu-item:hover { background: #eee; }
.menu-label { flex: 1; font-size: 15px; color: #333; }
.menu-arrow { font-size: 18px; color: #ccc; }

.pwd-overlay {
  position: fixed; inset: 0; z-index: 300; background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center;
}
.pwd-modal {
  width: 320px; background: #fff; border-radius: 14px; padding: 28px; box-shadow: 0 12px 40px rgba(0,0,0,.15);
}
.pwd-title { font-size: 17px; font-weight: 600; color: #333; margin-bottom: 16px; text-align: center; }
.pwd-input {
  width: 100%; padding: 10px 12px; margin-bottom: 10px; border: 1px solid #e0e0e0;
  border-radius: 8px; font-size: 14px; outline: none; box-sizing: border-box;
}
.pwd-input:focus { border-color: #31c27c; }
.pwd-error { color: #ec4141; font-size: 12px; margin-bottom: 8px; }
.pwd-btns { display: flex; gap: 10px; }
.pwd-cancel {
  flex: 1; padding: 10px; border: 1px solid #e0e0e0;
  border-radius: 8px; background: transparent; color: #666; cursor: pointer;
}
.pwd-submit {
  flex: 1; padding: 10px; border: none; border-radius: 8px;
  background: #31c27c; color: #fff; cursor: pointer;
}
.pwd-submit:disabled { opacity: .5; }
</style>
