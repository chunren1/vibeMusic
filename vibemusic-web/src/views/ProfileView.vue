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

function closeEdit() {
  editMode.value = false
}

// ===== 保存资料 =====
async function saveProfile() {
  formLoading.value = true
  formMsg.value = ''
  formError.value = false

  if (!nickname.value.trim()) { formMsg.value = '昵称不能为空'; formError.value = true; formLoading.value = false; return }
  if (nickname.value.length > 30) { formMsg.value = '昵称长度不超过30个字符'; formError.value = true; formLoading.value = false; return }
  if (birthday.value && !/^\d{4}-\d{2}-\d{2}$/.test(birthday.value)) { formMsg.value = '生日格式不正确'; formError.value = true; formLoading.value = false; return }

  try {
    await auth.updateUserProfile({ nickname: nickname.value.trim(), gender: gender.value, birthday: birthday.value || null })
    formMsg.value = '保存成功'
    formError.value = false
    setTimeout(() => { editMode.value = false }, 800)
  } catch (e) {
    formMsg.value = e.response?.data?.message || e.message || '保存失败'
    formError.value = true
  } finally { formLoading.value = false }
}

// ===== 修改密码 =====
async function changePassword() {
  pwdError.value = ''; pwdSuccess.value = false
  if (!oldPwd.value || !newPwd.value) { pwdError.value = '请填写新旧密码'; return }
  if (newPwd.value.length < 4) { pwdError.value = '新密码至少4位'; return }
  pwdLoading.value = true
  try {
    await request.post('/auth/change-password', { oldPassword: oldPwd.value, newPassword: newPwd.value })
    pwdSuccess.value = true; oldPwd.value = ''; newPwd.value = ''
    setTimeout(() => { showPwdModal.value = false; pwdSuccess.value = false }, 1500)
  } catch (e) {
    pwdError.value = e.response?.data?.message || e.message || '修改失败'
  } finally { pwdLoading.value = false }
}

// ===== 头像上传 =====
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
  <div class="profile-page">
    <!-- ========== 未登录 ========== -->
    <div v-if="!auth.isLoggedIn" class="not-login-card">
      <div class="ghost-avatar" @click="openLogin">?</div>
      <h2 class="ghost-title" @click="openLogin">点击登录</h2>
      <p class="ghost-sub">登录后同步收藏和歌单</p>
    </div>

    <!-- ========== 已登录 - 展示模式 ========== -->
    <template v-else>
      <!-- 顶部绿色背景横幅 -->
      <div class="profile-banner">
        <div class="banner-bg">
          <div class="banner-circle banner-circle-1"></div>
          <div class="banner-circle banner-circle-2"></div>
        </div>
        <!-- 头像 -->
        <div class="avatar-section">
          <div class="avatar-wrapper" @click="router.push('/profile/detail')" title="点击查看详情">
            <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="avatar-img" />
            <span v-else class="avatar-text">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</span>
          </div>
        </div>
        <h1 class="profile-name">{{ auth.user?.nickname || auth.user?.username }}</h1>
        <p class="profile-uid">@{{ auth.user?.username }}</p>
      </div>

      <!-- 信息卡片 + 编辑按钮 -->
      <div class="info-section">
        <div class="info-card">
          <div class="info-row">
            <span class="info-label">昵称</span>
            <span class="info-value">{{ auth.user?.nickname || auth.user?.username }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">性别</span>
            <span class="info-value">{{ genderLabel(auth.user?.gender) }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">生日</span>
            <span class="info-value">{{ auth.user?.birthday || '未设置' }}</span>
          </div>
        </div>
        <button class="edit-btn" @click="openEdit">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          编辑资料
        </button>
      </div>

      <!-- 快捷入口 -->
      <div class="menu-card">
        <div class="menu-item" @click="router.push('/recent')"><span>最近播放</span><span class="arrow">›</span></div>
        <div class="menu-item" @click="router.push('/likes')"><span>我的收藏</span><span class="arrow">›</span></div>
        <div class="menu-item" @click="router.push('/playlists')"><span>我的歌单</span><span class="arrow">›</span></div>
        <div class="menu-item" @click="showPwdModal = true"><span>修改密码</span><span class="arrow">›</span></div>
        <div class="menu-item logout-item" @click="auth.logout()"><span>退出登录</span></div>
      </div>
    </template>

    <!-- ========== 编辑弹窗 ========== -->
    <Teleport to="body">
      <Transition name="slide">
        <div v-if="editMode" class="edit-overlay">
          <div class="edit-panel">
            <div class="edit-header">
              <h3>编辑个人资料</h3>
              <button class="close-btn" @click="closeEdit">×</button>
            </div>

            <div class="edit-body">
              <!-- 头像 -->
              <div class="edit-avatar-row">
                <div class="edit-avatar" @click="triggerAvatar">
                  <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="edit-avatar-img" />
                  <span v-else class="edit-avatar-text">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</span>
                  <div v-if="avatarLoading" class="avatar-spinner"></div>
                  <div class="avatar-camera">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg>
                  </div>
                </div>
                <input ref="avatarInput" type="file" accept="image/*" class="avatar-input" @change="onAvatarChange" />
                <span class="avatar-hint">点击更换头像</span>
              </div>

              <!-- 昵称 -->
              <div class="form-row">
                <label>昵称</label>
                <input v-model="nickname" type="text" placeholder="请输入昵称" maxlength="30" />
              </div>

              <!-- 性别 -->
              <div class="form-row">
                <label>性别</label>
                <div class="gender-group">
                  <label v-for="g in ['男', '女', '保密']" :key="g" class="gender-opt" :class="{ active: gender === g }">
                    <input type="radio" v-model="gender" :value="g" />
                    <span>{{ g === '保密' ? '保密' : g === '男' ? '♂ 男' : '♀ 女' }}</span>
                  </label>
                </div>
              </div>

              <!-- 生日 -->
              <div class="form-row">
                <label>生日</label>
                <input v-model="birthday" type="date" placeholder="YYYY-MM-DD" />
              </div>

              <p v-if="formMsg" class="form-msg" :class="{ error: formError }">{{ formMsg }}</p>

              <button class="save-btn" @click="saveProfile" :disabled="formLoading">
                {{ formLoading ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 修改密码弹窗 -->
    <Teleport to="body">
      <div v-if="showPwdModal" class="modal-overlay" @click="showPwdModal = false">
        <div class="modal-box" @click.stop>
          <h3 class="modal-title">修改密码</h3>
          <input v-model="oldPwd" type="password" placeholder="原密码" class="modal-input" />
          <input v-model="newPwd" type="password" placeholder="新密码（至少4位）" class="modal-input" />
          <p v-if="pwdError" class="modal-error">{{ pwdError }}</p>
          <p v-if="pwdSuccess" class="modal-success">密码修改成功</p>
          <div class="modal-btns">
            <button class="btn-cancel" @click="showPwdModal = false">取消</button>
            <button class="btn-confirm" @click="changePassword" :disabled="pwdLoading">{{ pwdLoading ? '修改中...' : '确认' }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <LoginModal v-model:visible="showLogin" @success="onLoginSuccess" />
  </div>
</template>

<style scoped>
.profile-page { min-height: 100%; }

/* ===== 未登录 ===== */
.not-login-card { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 80px 32px; gap: 12px; }
.ghost-avatar { width: 80px; height: 80px; border-radius: 50%; background: linear-gradient(135deg, #ccc, #aaa); display: flex; align-items: center; justify-content: center; font-size: 36px; font-weight: 700; color: #fff; cursor: pointer; transition: transform .2s; }
.ghost-avatar:hover { transform: scale(1.05); }
.ghost-title { font-size: 20px; color: #555; cursor: pointer; }
.ghost-sub { font-size: 13px; color: #999; }

/* ===== 顶部横幅 ===== */
.profile-banner {
  position: relative; padding: 56px 40px 36px; text-align: center;
}
.banner-bg {
  position: absolute; inset: 0;
  background: linear-gradient(160deg, #1a6b3a 0%, #31c27c 50%, #5ddb92 100%);
  overflow: hidden;
}
.banner-circle {
  position: absolute; border-radius: 50%;
  background: rgba(255,255,255,0.08);
}
.banner-circle-1 { width: 200px; height: 200px; top: -60px; right: -40px; }
.banner-circle-2 { width: 140px; height: 140px; bottom: -30px; left: -20px; }

.avatar-section { position: relative; margin-bottom: 16px; }
.avatar-wrapper {
  width: 88px; height: 88px; border-radius: 50%; margin: 0 auto;
  border: 3px solid rgba(255,255,255,0.5); cursor: pointer; overflow: hidden;
  background: rgba(255,255,255,0.2);
  display: flex; align-items: center; justify-content: center;
  transition: transform .2s;
}
.avatar-wrapper:hover { transform: scale(1.04); }
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.avatar-text { font-size: 36px; font-weight: 700; color: #fff; }
.profile-name { position: relative; font-size: 22px; font-weight: 700; color: #fff; margin-bottom: 4px; }
.profile-uid { position: relative; font-size: 13px; color: rgba(255,255,255,0.7); }

/* ===== 信息卡片 ===== */
.info-section {
  margin: -16px 32px 20px; position: relative; z-index: 1;
}
.info-card {
  background: #fff; border-radius: 14px 14px 0 0; overflow: hidden;
  box-shadow: 0 2px 16px rgba(0,0,0,0.06);
}
.info-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 24px; border-bottom: 1px solid #f5f5f5;
}
.info-row:last-child { border-bottom: none; }
.info-label { font-size: 14px; color: #999; }
.info-value { font-size: 14px; color: #333; font-weight: 500; }

.edit-btn {
  width: 100%; padding: 14px; border: none; border-radius: 0 0 14px 14px;
  background: #31c27c; color: #fff; font-size: 15px; font-weight: 500;
  cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px;
  transition: background .2s;
}
.edit-btn:hover { background: #28a86b; }

/* ===== 快捷入口 ===== */
.menu-card {
  margin: 0 32px 32px; background: #fff; border-radius: 14px;
  overflow: hidden; box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.menu-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 24px; cursor: pointer; transition: background .15s;
  border-bottom: 1px solid #f0f0f0; font-size: 15px; color: #333;
}
.menu-item:last-child { border-bottom: none; }
.menu-item:hover { background: #f9f9f9; }
.arrow { font-size: 20px; color: #ccc; }
.logout-item { justify-content: center; color: #ec4141; }

/* ===== 编辑弹窗 - 右侧滑入 ===== */
.edit-overlay {
  position: fixed; inset: 0; z-index: 400;
  background: rgba(0,0,0,0.35); display: flex; justify-content: flex-end;
}
.edit-panel {
  width: 400px; max-width: 90vw; height: 100%; background: #fff;
  box-shadow: -4px 0 30px rgba(0,0,0,0.12);
  display: flex; flex-direction: column; overflow: hidden;
}
.edit-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 20px 24px; border-bottom: 1px solid #eee;
}
.edit-header h3 { font-size: 17px; font-weight: 600; color: #333; }
.close-btn {
  width: 32px; height: 32px; border: none; border-radius: 50%;
  background: #f0f0f0; font-size: 18px; color: #666; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
}
.close-btn:hover { background: #e0e0e0; }

.edit-body { flex: 1; overflow-y: auto; padding: 24px; }

.edit-avatar-row {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  margin-bottom: 28px;
}
.edit-avatar {
  width: 80px; height: 80px; border-radius: 50%; overflow: hidden;
  background: linear-gradient(135deg, #31c27c, #1a8a4a); cursor: pointer;
  display: flex; align-items: center; justify-content: center; position: relative;
}
.edit-avatar-img { width: 100%; height: 100%; object-fit: cover; }
.edit-avatar-text { font-size: 32px; font-weight: 700; color: #fff; }
.avatar-camera {
  position: absolute; bottom: 0; right: 0;
  width: 28px; height: 28px; border-radius: 50%;
  background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center;
  color: #fff;
}
.avatar-spinner {
  position: absolute; inset: 0; background: rgba(0,0,0,0.3);
  border: 3px solid rgba(255,255,255,0.3); border-top-color: #fff;
  border-radius: 50%; animation: spin .6s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.avatar-input { display: none; }
.avatar-hint { font-size: 12px; color: #999; }

.form-row { margin-bottom: 18px; }
.form-row label { display: block; font-size: 13px; font-weight: 500; color: #666; margin-bottom: 8px; }
.form-row input[type="text"], .form-row input[type="date"] {
  width: 100%; padding: 10px 14px; border: 1px solid #e0e0e0;
  border-radius: 8px; font-size: 14px; color: #333; outline: none; box-sizing: border-box; transition: border .2s;
}
.form-row input:focus { border-color: #31c27c; }

.gender-group { display: flex; gap: 10px; }
.gender-opt {
  flex: 1; display: flex; align-items: center; justify-content: center; gap: 4px;
  padding: 10px; border: 1px solid #e0e0e0; border-radius: 8px;
  cursor: pointer; font-size: 14px; color: #666; transition: all .2s;
}
.gender-opt input { display: none; }
.gender-opt.active { border-color: #31c27c; background: rgba(49,194,124,0.06); color: #31c27c; }

.form-msg { text-align: center; font-size: 13px; padding: 8px 0; color: #31c27c; }
.form-msg.error { color: #ec4141; }

.save-btn {
  width: 100%; padding: 13px; border: none; border-radius: 10px;
  background: #31c27c; color: #fff; font-size: 15px; font-weight: 500;
  cursor: pointer; margin-top: 8px; transition: background .2s;
}
.save-btn:hover:not(:disabled) { background: #28a86b; }
.save-btn:disabled { opacity: .6; cursor: not-allowed; }

/* 滑入动画 */
.slide-enter-active { transition: all .3s ease; }
.slide-leave-active { transition: all .25s ease; }
.slide-enter-from .edit-panel { transform: translateX(100%); }
.slide-leave-to .edit-panel { transform: translateX(100%); }
.slide-enter-from, .slide-leave-to { background: transparent; }

/* ===== 密码弹窗 ===== */
.modal-overlay { position: fixed; inset: 0; z-index: 300; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; }
.modal-box { width: 340px; background: #fff; border-radius: 14px; padding: 28px; box-shadow: 0 12px 40px rgba(0,0,0,0.15); }
.modal-title { font-size: 17px; font-weight: 600; color: #333; margin-bottom: 16px; text-align: center; }
.modal-input { width: 100%; padding: 10px 12px; margin-bottom: 10px; border: 1px solid #e0e0e0; border-radius: 8px; font-size: 14px; outline: none; box-sizing: border-box; }
.modal-input:focus { border-color: #31c27c; }
.modal-error { color: #ec4141; font-size: 12px; margin-bottom: 8px; }
.modal-success { color: #31c27c; font-size: 13px; margin-bottom: 8px; text-align: center; }
.modal-btns { display: flex; gap: 10px; }
.btn-cancel { flex: 1; padding: 10px; border: 1px solid #e0e0e0; border-radius: 8px; background: transparent; color: #666; cursor: pointer; }
.btn-confirm { flex: 1; padding: 10px; border: none; border-radius: 8px; background: #31c27c; color: #fff; cursor: pointer; }
.btn-confirm:disabled { opacity: .5; }
</style>
