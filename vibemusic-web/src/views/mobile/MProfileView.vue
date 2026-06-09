<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginModal from '@/components/LoginModal.vue'
import request from '@/api/request'

const router = useRouter()
const auth = useAuthStore()
const showLogin = ref(false)
const editMode = ref(false)

// 表单
const formLoading = ref(false)
const formMsg = ref('')
const formError = ref(false)
const nickname = ref('')
const gender = ref('')
const birthday = ref('')

// 密码弹窗
const showPwdModal = ref(false)
const oldPwd = ref('')
const newPwd = ref('')
const pwdError = ref('')
const pwdLoading = ref(false)
const pwdSuccess = ref(false)

// 头像
const avatarLoading = ref(false)
const avatarInput = ref(null)

onMounted(() => {
  if (auth.isLoggedIn) refreshForm()
})

function refreshForm() {
  nickname.value = auth.user?.nickname || ''
  gender.value = auth.user?.gender || '保密'
  birthday.value = auth.user?.birthday || ''
}

function openLogin() { showLogin.value = true }
function onLoginSuccess() { showLogin.value = false; refreshForm() }

function openEdit() {
  refreshForm()
  formMsg.value = ''
  formError.value = false
  editMode.value = true
}

function closeEdit() { editMode.value = false }

async function saveProfile() {
  formLoading.value = true; formMsg.value = ''; formError.value = false
  if (!nickname.value.trim()) { formMsg.value = '昵称不能为空'; formError.value = true; formLoading.value = false; return }
  if (nickname.value.length > 30) { formMsg.value = '昵称过长'; formError.value = true; formLoading.value = false; return }
  if (birthday.value && !/^\d{4}-\d{2}-\d{2}$/.test(birthday.value)) { formMsg.value = '生日格式不正确'; formError.value = true; formLoading.value = false; return }
  try {
    await auth.updateUserProfile({ nickname: nickname.value.trim(), gender: gender.value, birthday: birthday.value || null })
    formMsg.value = '保存成功'
    setTimeout(() => { editMode.value = false }, 800)
  } catch (e) {
    formMsg.value = e.response?.data?.message || e.message || '保存失败'
    formError.value = true
  } finally { formLoading.value = false }
}

async function changePassword() {
  pwdError.value = ''; pwdSuccess.value = false
  if (!oldPwd.value || !newPwd.value) { pwdError.value = '请填写新旧密码'; return }
  if (newPwd.value.length < 4) { pwdError.value = '新密码至少4位'; return }
  pwdLoading.value = true
  try {
    await request.post('/auth/change-password', { oldPassword: oldPwd.value, newPassword: newPwd.value })
    pwdSuccess.value = true; oldPwd.value = ''; newPwd.value = ''
    setTimeout(() => { showPwdModal.value = false; pwdSuccess.value = false }, 1500)
  } catch (e) { pwdError.value = e.response?.data?.message || e.message || '修改失败' }
  finally { pwdLoading.value = false }
}

function triggerAvatar() { avatarInput.value?.click() }
async function onAvatarChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 2 * 1024 * 1024) { alert('文件不能超过 2MB'); return }
  if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type)) { alert('仅支持 JPG/PNG/GIF/WebP'); return }
  avatarLoading.value = true
  try { await auth.uploadUserAvatar(file) } catch (err) { alert(err.response?.data?.message || err.message || '上传失败') }
  finally { avatarLoading.value = false }
}

function genderLabel(v) {
  return { '男': '♂ 男', '女': '♀ 女', '保密': '保密' }[v] || '保密'
}
</script>

<template>
  <div class="m-page">
    <!-- 未登录 -->
    <div v-if="!auth.isLoggedIn" class="m-not-login" @click="openLogin">
      <div class="m-ghost-avatar">?</div>
      <h2>点击登录</h2>
      <p>登录后同步收藏和歌单</p>
    </div>

    <template v-else>
      <!-- 顶部绿色横幅 -->
      <div class="m-banner">
        <div class="m-banner-bg">
          <div class="m-circle c1"></div>
          <div class="m-circle c2"></div>
        </div>
        <div class="m-avatar" @click="router.push('/m/profile/detail')">
          <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="m-av-img" />
          <span v-else class="m-av-text">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</span>
        </div>
        <h1 class="m-name">{{ auth.user?.nickname || auth.user?.username }}</h1>
        <p class="m-uid">@{{ auth.user?.username }}</p>
      </div>

      <!-- 信息卡片 + 编辑按钮 -->
      <div class="m-info-section">
        <div class="m-info-card">
          <div class="m-info-row"><span class="m-info-k">昵称</span><span class="m-info-v">{{ auth.user?.nickname || auth.user?.username }}</span></div>
          <div class="m-info-row"><span class="m-info-k">性别</span><span class="m-info-v">{{ genderLabel(auth.user?.gender) }}</span></div>
          <div class="m-info-row"><span class="m-info-k">生日</span><span class="m-info-v">{{ auth.user?.birthday || '未设置' }}</span></div>
        </div>
        <button class="m-edit-btn" @click="openEdit">编辑资料</button>
      </div>

      <!-- 快捷入口 -->
      <div class="m-menu">
        <div class="m-menu-item" @click="router.push('/m/recent')"><span>最近播放</span><span class="m-arrow">›</span></div>
        <div class="m-menu-item" @click="router.push('/m/likes')"><span>我的收藏</span><span class="m-arrow">›</span></div>
        <div class="m-menu-item" @click="router.push('/m/playlists')"><span>我的歌单</span><span class="m-arrow">›</span></div>
        <div class="m-menu-item" @click="showPwdModal = true"><span>修改密码</span><span class="m-arrow">›</span></div>
        <div class="m-menu-item m-logout-item" @click="auth.logout()">退出登录</div>
      </div>
    </template>

    <!-- ========== 编辑弹窗 - 底部滑出 ========== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="editMode" class="sheet-overlay" @click="closeEdit">
          <div class="sheet-panel" @click.stop>
            <div class="sheet-bar"></div>
            <div class="sheet-header">
              <h3>编辑个人资料</h3>
              <button class="sheet-close" @click="closeEdit">×</button>
            </div>

            <div class="sheet-body">
              <!-- 头像 -->
              <div class="s-avatar-row">
                <div class="s-avatar" @click="triggerAvatar">
                  <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="s-av-img" />
                  <span v-else class="s-av-text">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</span>
                  <div v-if="avatarLoading" class="s-av-spinner"></div>
                  <div class="s-av-camera">
                    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
                  </div>
                </div>
                <input ref="avatarInput" type="file" accept="image/*" style="display:none" @change="onAvatarChange" />
                <span class="s-av-hint">点击更换头像</span>
              </div>

              <div class="s-row">
                <label>昵称</label>
                <input v-model="nickname" type="text" placeholder="请输入昵称" maxlength="30" />
              </div>

              <div class="s-row">
                <label>性别</label>
                <div class="s-gender">
                  <label v-for="g in ['男', '女', '保密']" :key="g" class="s-g-opt" :class="{ active: gender === g }">
                    <input type="radio" v-model="gender" :value="g" style="display:none" />
                    {{ g === '保密' ? '保密' : g === '男' ? '♂' : '♀' }}
                  </label>
                </div>
              </div>

              <div class="s-row">
                <label>生日</label>
                <input v-model="birthday" type="date" />
              </div>

              <p v-if="formMsg" class="s-msg" :class="{ error: formError }">{{ formMsg }}</p>

              <button class="s-save" @click="saveProfile" :disabled="formLoading">{{ formLoading ? '保存中...' : '保存' }}</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 密码弹窗 -->
    <Teleport to="body">
      <div v-if="showPwdModal" class="pwd-overlay" @click="showPwdModal = false">
        <div class="pwd-box" @click.stop>
          <h3>修改密码</h3>
          <input v-model="oldPwd" type="password" placeholder="原密码" />
          <input v-model="newPwd" type="password" placeholder="新密码（至少4位）" />
          <p v-if="pwdError" class="pwd-err">{{ pwdError }}</p>
          <p v-if="pwdSuccess" class="pwd-ok">修改成功</p>
          <div class="pwd-btns">
            <button @click="showPwdModal = false">取消</button>
            <button @click="changePassword" :disabled="pwdLoading">{{ pwdLoading ? '...' : '确认' }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <LoginModal v-model:visible="showLogin" @success="onLoginSuccess" />
  </div>
</template>

<style scoped>
.m-page { min-height: 100vh; min-height: 100dvh; padding-bottom: 80px; }

.m-not-login {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 100px 32px; gap: 10px;
}
.m-ghost-avatar {
  width: 80px; height: 80px; border-radius: 50%; background: rgba(255,255,255,0.08);
  display: flex; align-items: center; justify-content: center; font-size: 36px; font-weight: 700; color: #555;
}
.m-not-login h2 { font-size: 18px; color: #888; }
.m-not-login p { font-size: 13px; color: #555; }

/* 横幅 */
.m-banner {
  position: relative; padding: 48px 20px 32px; text-align: center;
}
.m-banner-bg {
  position: absolute; inset: 0;
  background: linear-gradient(160deg, #1a6b3a 0%, #31c27c 50%, #5ddb92 100%);
  overflow: hidden;
}
.m-circle {
  position: absolute; border-radius: 50%; background: rgba(255,255,255,0.08);
}
.c1 { width: 160px; height: 160px; top: -50px; right: -30px; }
.c2 { width: 100px; height: 100px; bottom: -20px; left: -20px; }

.m-avatar {
  width: 80px; height: 80px; border-radius: 50%; margin: 0 auto; position: relative;
  border: 3px solid rgba(255,255,255,0.5); overflow: hidden;
  background: rgba(255,255,255,0.2);
  display: flex; align-items: center; justify-content: center;
}
.m-avatar:active { transform: scale(0.96); }
.m-av-img { width: 100%; height: 100%; object-fit: cover; }
.m-av-text { font-size: 32px; font-weight: 700; color: #fff; }
.m-name { position: relative; font-size: 20px; font-weight: 700; color: #fff; margin: 14px 0 2px; }
.m-uid { position: relative; font-size: 12px; color: rgba(255,255,255,0.6); }

/* 信息卡片 */
.m-info-section {
  margin: -14px 16px 16px; position: relative; z-index: 1;
}
.m-info-card {
  border-radius: 14px 14px 0 0; overflow: hidden;
  background: rgba(255,255,255,0.04);
}
.m-info-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 15px 16px; border-bottom: 1px solid rgba(255,255,255,0.04);
}
.m-info-row:last-child { border-bottom: none; }
.m-info-k { font-size: 13px; color: #888; }
.m-info-v { font-size: 14px; color: #ccc; }
.m-edit-btn {
  width: 100%; padding: 13px; border: none; border-radius: 0 0 14px 14px;
  background: #31c27c; color: #fff; font-size: 14px; font-weight: 500; cursor: pointer;
}

/* 菜单 */
.m-menu {
  margin: 0 16px 20px; border-radius: 14px; overflow: hidden;
  background: rgba(255,255,255,0.04);
}
.m-menu-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 15px 16px; font-size: 14px; color: #ccc;
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.m-menu-item:last-child { border-bottom: none; }
.m-menu-item:active { background: rgba(255,255,255,0.02); }
.m-arrow { font-size: 18px; color: #555; }
.m-logout-item { justify-content: center; color: #ec4141; font-size: 14px; }

/* ===== 底部编辑弹窗 ===== */
.sheet-overlay {
  position: fixed; inset: 0; z-index: 500; background: rgba(0,0,0,0.5);
  display: flex; align-items: flex-end;
}
.sheet-panel {
  width: 100%; max-height: 85vh; background: #1a1a1a;
  border-radius: 20px 20px 0 0;
  display: flex; flex-direction: column; overflow: hidden;
}
.sheet-bar {
  width: 40px; height: 4px; background: rgba(255,255,255,0.2);
  border-radius: 2px; margin: 10px auto 0;
}
.sheet-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-bottom: 1px solid rgba(255,255,255,0.06);
}
.sheet-header h3 { font-size: 16px; color: #ddd; }
.sheet-close {
  width: 30px; height: 30px; border: none; border-radius: 50%;
  background: rgba(255,255,255,0.06); color: #888; font-size: 18px;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
}
.sheet-body { flex: 1; overflow-y: auto; padding: 20px; }

.s-avatar-row { display: flex; flex-direction: column; align-items: center; gap: 8px; margin-bottom: 24px; }
.s-avatar {
  width: 72px; height: 72px; border-radius: 50%; overflow: hidden; position: relative;
  background: linear-gradient(135deg, #31c27c, #1a6b3a);
  display: flex; align-items: center; justify-content: center;
}
.s-av-img { width: 100%; height: 100%; object-fit: cover; }
.s-av-text { font-size: 28px; font-weight: 700; color: #fff; }
.s-av-spinner {
  position: absolute; inset: 0; background: rgba(0,0,0,0.3);
  border: 3px solid rgba(255,255,255,0.3); border-top-color: #fff;
  border-radius: 50%; animation: spin .6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.s-av-camera {
  position: absolute; bottom: 0; right: 0; width: 24px; height: 24px;
  border-radius: 50%; background: rgba(0,0,0,0.5);
  display: flex; align-items: center; justify-content: center; color: #fff;
}
.s-av-hint { font-size: 12px; color: #888; }

.s-row { margin-bottom: 16px; }
.s-row label { display: block; font-size: 12px; color: #888; margin-bottom: 6px; }
.s-row input {
  width: 100%; padding: 10px 12px; border: 1px solid rgba(255,255,255,0.08);
  border-radius: 8px; background: rgba(255,255,255,0.04); color: #ddd;
  font-size: 14px; outline: none; box-sizing: border-box;
}
.s-row input:focus { border-color: #31c27c; }

.s-gender { display: flex; gap: 8px; }
.s-g-opt {
  flex: 1; padding: 10px; text-align: center;
  border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
  font-size: 13px; color: #888; cursor: pointer;
}
.s-g-opt.active { border-color: #31c27c; color: #31c27c; background: rgba(49,194,124,0.1); }

.s-msg { text-align: center; font-size: 12px; padding: 6px 0; color: #31c27c; }
.s-msg.error { color: #ec4141; }

.s-save {
  width: 100%; padding: 13px; border: none; border-radius: 10px;
  background: #31c27c; color: #fff; font-size: 15px; font-weight: 500;
  cursor: pointer; margin-top: 8px;
}
.s-save:disabled { opacity: .5; }

/* 底部滑入动画 */
.sheet-enter-active { transition: all .3s ease; }
.sheet-leave-active { transition: all .25s ease; }
.sheet-enter-from .sheet-panel { transform: translateY(100%); }
.sheet-leave-to .sheet-panel { transform: translateY(100%); }
.sheet-enter-from, .sheet-leave-to { background: transparent; }

/* 密码弹窗 */
.pwd-overlay { position: fixed; inset: 0; z-index: 300; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; }
.pwd-box { width: 300px; background: #1a1a1a; border-radius: 14px; padding: 24px; }
.pwd-box h3 { font-size: 16px; color: #ddd; text-align: center; margin-bottom: 16px; }
.pwd-box input {
  width: 100%; padding: 10px 12px; margin-bottom: 10px; border: 1px solid rgba(255,255,255,0.1);
  border-radius: 8px; background: rgba(255,255,255,0.05); color: #ddd; font-size: 14px; outline: none;
}
.pwd-box input:focus { border-color: #31c27c; }
.pwd-err { color: #ec4141; font-size: 12px; margin-bottom: 6px; }
.pwd-ok { color: #31c27c; font-size: 12px; margin-bottom: 6px; text-align: center; }
.pwd-btns { display: flex; gap: 10px; }
.pwd-btns button {
  flex: 1; padding: 10px; border-radius: 8px; font-size: 14px; cursor: pointer;
}
.pwd-btns button:first-child { border: 1px solid rgba(255,255,255,0.1); background: transparent; color: #888; }
.pwd-btns button:last-child { border: none; background: #31c27c; color: #fff; }
.pwd-btns button:last-child:disabled { opacity: .5; }
</style>
