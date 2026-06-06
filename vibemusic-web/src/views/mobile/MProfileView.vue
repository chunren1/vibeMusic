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
  <div class="m-page">
    <!-- 用户卡片 -->
    <div class="m-user-card">
      <div v-if="auth.isLoggedIn" class="m-logged-in">
        <div class="m-avatar">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</div>
        <div class="m-user-info">
          <div class="m-nickname">{{ auth.user?.nickname || auth.user?.username }}</div>
          <div class="m-uid">@{{ auth.user?.username }}</div>
        </div>
        <button class="m-logout" @click="auth.logout()">退出</button>
      </div>
      <div v-else class="m-not-login" @click="openLogin">
        <div class="m-avatar ghost">?</div>
        <div class="m-user-info">
          <div class="m-nickname">点击登录</div>
          <div class="m-uid">登录后同步收藏和歌单</div>
        </div>
      </div>
    </div>

    <!-- 功能菜单 -->
    <div class="m-menu">
      <div class="m-menu-item" @click="!auth.isLoggedIn ? openLogin() : router.push('/m/recent')">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" class="m-menu-icon"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
        <span class="m-menu-label">最近播放</span>
        <span class="m-menu-arrow">›</span>
      </div>
      <div class="m-menu-item" @click="!auth.isLoggedIn ? openLogin() : router.push('/m/likes')">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" class="m-menu-icon"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
        <span class="m-menu-label">我的收藏</span>
        <span class="m-menu-arrow">›</span>
      </div>
      <div class="m-menu-item" @click="!auth.isLoggedIn ? openLogin() : router.push('/m/playlists')">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" class="m-menu-icon"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
        <span class="m-menu-label">我的歌单</span>
        <span class="m-menu-arrow">›</span>
      </div>
      <div v-if="auth.isLoggedIn" class="m-menu-item" @click="showPwdModal = true">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" class="m-menu-icon"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
        <span class="m-menu-label">修改密码</span>
        <span class="m-menu-arrow">›</span>
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
.m-page { padding: 16px; }
.m-user-card {
  background: rgba(255,255,255,.04); border-radius: 14px; padding: 20px; margin-bottom: 20px;
}
.m-logged-in, .m-not-login { display: flex; align-items: center; gap: 14px; }
.m-avatar {
  width: 52px; height: 52px; border-radius: 50%; flex-shrink: 0;
  background: linear-gradient(135deg, #31c27c, #1a8a4a);
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; font-weight: 700; color: #fff;
}
.m-avatar.ghost { background: rgba(255,255,255,.1); color: #666; }
.m-user-info { flex: 1; min-width: 0; }
.m-nickname { font-size: 17px; font-weight: 600; color: #e0e0e0; }
.m-uid { font-size: 12px; color: #888; margin-top: 3px; }
.m-logout {
  padding: 6px 14px; border: 1px solid rgba(255,255,255,.15);
  border-radius: 16px; background: transparent; color: #888; font-size: 12px; cursor: pointer;
}
.m-menu { border-radius: 14px; overflow: hidden; background: rgba(255,255,255,.04); }
.m-menu-item {
  display: flex; align-items: center; gap: 12px; padding: 16px;
  border-bottom: 1px solid rgba(255,255,255,.04);
}
.m-menu-item:last-child { border-bottom: none; }
.m-menu-item:active { background: rgba(255,255,255,.03); }
.m-menu-icon { font-size: 18px; }
.m-menu-label { flex: 1; font-size: 15px; color: #ddd; }
.m-menu-arrow { font-size: 18px; color: #555; }

/* 修改密码弹窗 */
.pwd-overlay {
  position: fixed; inset: 0; z-index: 300; background: rgba(0,0,0,0.6);
  display: flex; align-items: center; justify-content: center;
}
.pwd-modal {
  width: 300px; background: #1a1a1a; border-radius: 14px; padding: 24px;
}
.pwd-title { font-size: 17px; font-weight: 600; color: #e0e0e0; margin-bottom: 16px; text-align: center; }
.pwd-input {
  width: 100%; padding: 10px 12px; margin-bottom: 10px; border: 1px solid rgba(255,255,255,.1);
  border-radius: 8px; background: rgba(255,255,255,.06); color: #e0e0e0; font-size: 14px; outline: none;
}
.pwd-input:focus { border-color: #31c27c; }
.pwd-error { color: #ec4141; font-size: 12px; margin-bottom: 8px; }
.pwd-btns { display: flex; gap: 10px; }
.pwd-cancel {
  flex: 1; padding: 10px; border: 1px solid rgba(255,255,255,.1);
  border-radius: 8px; background: transparent; color: #888; cursor: pointer;
}
.pwd-submit {
  flex: 1; padding: 10px; border: none; border-radius: 8px;
  background: #31c27c; color: #fff; cursor: pointer;
}
.pwd-submit:disabled { opacity: .5; }
</style>
