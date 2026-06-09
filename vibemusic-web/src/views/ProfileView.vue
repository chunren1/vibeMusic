<script setup>
import { ref, onMounted, computed } from 'vue'
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

// 背景图
const bgLoading = ref(false)
const bgInput = ref(null)

// 头像占位图柔色色板
const AVATAR_PALETTE = ['#e8b4b4', '#b4c8e8', '#b4e8c2', '#e8d9b4', '#d4b4e8', '#e8b4d4', '#b4e4e8', '#c2e8b4', '#e8c4b4', '#b4b4e8']

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

// ===== 头像占位符背景色（取自昵称 hash） =====
const avatarPlaceholderBg = computed(() => {
  const name = auth.user?.nickname || auth.user?.username || '?'
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash)
  return AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length]
})

// ===== 头像首字符 =====
const avatarInitial = computed(() => {
  return (auth.user?.nickname || auth.user?.username || '?')[0]?.toUpperCase()
})

// ===== 生日格式化 + 星座 =====
function formatBirthday(dateStr) {
  if (!dateStr) return '未设置'
  const [y, m, d] = dateStr.split('-')
  if (!m || !d) return dateStr
  const zodiac = getZodiac(parseInt(m), parseInt(d))
  return `${parseInt(m)}月${parseInt(d)}日 · ${zodiac}`
}

function getZodiac(month, day) {
  const dates = [20, 19, 21, 20, 21, 22, 23, 23, 23, 24, 23, 22]
  const signs = ['水瓶座', '双鱼座', '白羊座', '金牛座', '双子座', '巨蟹座', '狮子座', '处女座', '天秤座', '天蝎座', '射手座', '摩羯座']
  return day < dates[month - 1] ? signs[month - 1] : signs[month % 12]
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

// ===== 背景图上传 =====
function triggerBg() { bgInput.value?.click() }
async function onBgChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 2 * 1024 * 1024) { alert('文件不能超过 2MB'); return }
  if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type)) { alert('仅支持 JPG/PNG/GIF/WebP'); return }
  bgLoading.value = true
  try { await auth.uploadUserBgImage(file) } catch (err) { alert(err.response?.data?.message || err.message || '上传失败') }
  finally { bgLoading.value = false }
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

    <!-- ========== 已登录 ========== -->
    <template v-else>
      <!-- 头部区域：品牌渐变背景 + 自定义背景图 + 噪点纹理 -->
      <div class="hero-area">
        <div class="hero-bg" :class="{ 'has-image': auth.bgImageSrc }">
          <img v-if="auth.bgImageSrc" :src="auth.bgImageSrc" class="hero-bg-img" />
        </div>

        <!-- 头像 -->
        <div class="hero-avatar" @click="router.push('/profile/detail')" title="查看详情">
          <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="hero-av-img" />
          <span v-else class="hero-av-text" :style="{ background: avatarPlaceholderBg }">{{ avatarInitial }}</span>
        </div>

        <!-- 昵称 + ID -->
        <h1 class="hero-name">{{ auth.user?.nickname || auth.user?.username }}</h1>
        <p class="hero-id">@{{ auth.user?.username }}</p>

        <!-- 胶囊编辑按钮 -->
        <button class="hero-edit-btn" @click="openEdit">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          编辑
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

              <!-- 背景图 -->
              <div class="form-row">
                <label>个人页背景</label>
                <div class="bg-preview" @click="triggerBg">
                  <img v-if="auth.bgImageSrc" :src="auth.bgImageSrc" class="bg-preview-img" />
                  <div v-else class="bg-preview-empty">
                    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
                    <span>点击上传</span>
                  </div>
                  <div v-if="bgLoading" class="avatar-spinner"></div>
                </div>
                <input ref="bgInput" type="file" accept="image/*" class="avatar-input" @change="onBgChange" />
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

/* ===== 头部区域 ===== */
.hero-area {
  position: relative; padding: 64px 40px 40px;
  display: flex; flex-direction: column; align-items: center; text-align: center;
  overflow: hidden;
}

/* 品牌渐变 + 自定义背景图 + SVG 噪点纹理叠加 */
.hero-bg {
  position: absolute; inset: 0;
  background: linear-gradient(160deg, #1a6b3a 0%, #28a86b 40%, #31c27c 70%, #5ddb92 100%);
}
.hero-bg.has-image { background: none; }
.hero-bg-img {
  position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover;
}
.hero-bg::after {
  content: ''; position: absolute; inset: 0; opacity: 0.25;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  background-repeat: repeat; background-size: 128px 128px;
}

/* 头像 */
.hero-avatar {
  position: relative; z-index: 1;
  width: 96px; height: 96px; border-radius: 50%; margin-bottom: 18px;
  border: 3px solid rgba(255,255,255,0.4); cursor: pointer;
  overflow: hidden; transition: transform .2s, border-color .2s;
}
.hero-avatar:hover { transform: scale(1.04); border-color: rgba(255,255,255,0.7); }
.hero-av-img { width: 100%; height: 100%; object-fit: cover; }
.hero-av-text {
  width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;
  font-size: 42px; font-weight: 700; color: #fff; user-select: none;
}

/* 昵称 */
.hero-name {
  position: relative; z-index: 1;
  font-size: 24px; font-weight: 700; color: #fff; margin-bottom: 4px;
}

/* ID */
.hero-id {
  position: relative; z-index: 1;
  font-size: 13px; color: rgba(255,255,255,0.55); margin-bottom: 16px;
}

/* 胶囊编辑按钮 */
.hero-edit-btn {
  position: relative; z-index: 1;
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 20px; border: 1px solid rgba(255,255,255,0.35);
  border-radius: 20px; background: rgba(255,255,255,0.1);
  color: rgba(255,255,255,0.85); font-size: 13px; font-weight: 500;
  cursor: pointer; transition: all .2s;
  backdrop-filter: blur(4px);
}
.hero-edit-btn:hover {
  background: rgba(255,255,255,0.2);
  border-color: rgba(255,255,255,0.6);
  color: #fff;
}

/* ===== 信息卡片 ===== */
.info-card {
  margin: -12px 28px 20px; position: relative; z-index: 1;
  background: #fff; border-radius: 14px; padding: 8px 0;
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
}
.info-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 15px 24px;
}
.info-label { font-size: 14px; color: #999; }
.info-value { font-size: 14px; color: #333; font-weight: 500; }

/* ===== 快捷入口 ===== */
.menu-card {
  margin: 0 28px 32px; background: #fff; border-radius: 14px;
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

/* 背景图预览 */
.bg-preview {
  width: 100%; height: 100px; border-radius: 10px; overflow: hidden;
  border: 2px dashed #ddd; cursor: pointer; position: relative;
  transition: border-color .2s;
}
.bg-preview:hover { border-color: #31c27c; }
.bg-preview-img { width: 100%; height: 100%; object-fit: cover; }
.bg-preview-empty {
  width: 100%; height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 6px;
  color: #bbb; font-size: 13px; background: #f9f9f9;
}

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
