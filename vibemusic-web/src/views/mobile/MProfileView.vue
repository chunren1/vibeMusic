<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginModal from '@/components/LoginModal.vue'
import request from '@/api/request'

const router = useRouter()
const auth = useAuthStore()
const showLogin = ref(false)
const editMode = ref(false)

const formLoading = ref(false)
const formMsg = ref('')
const formError = ref(false)
const nickname = ref('')
const gender = ref('')
const birthday = ref('')

const showPwdModal = ref(false)
const oldPwd = ref('')
const newPwd = ref('')
const pwdError = ref('')
const pwdLoading = ref(false)
const pwdSuccess = ref(false)

const avatarLoading = ref(false)
const avatarInput = ref(null)

const bgLoading = ref(false)
const bgInput = ref(null)

const AVATAR_PALETTE = ['#e8b4b4', '#b4c8e8', '#b4e8c2', '#e8d9b4', '#d4b4e8', '#e8b4d4', '#b4e4e8', '#c2e8b4', '#e8c4b4', '#b4b4e8']

onMounted(() => { if (auth.isLoggedIn) refreshForm() })

function refreshForm() {
  nickname.value = auth.user?.nickname || ''
  gender.value = auth.user?.gender || '保密'
  birthday.value = auth.user?.birthday || ''
}

function openLogin() { showLogin.value = true }
function onLoginSuccess() { showLogin.value = false; refreshForm() }
function openEdit() { refreshForm(); formMsg.value = ''; formError.value = false; editMode.value = true }
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
  } catch (e) { formMsg.value = e.response?.data?.message || e.message || '保存失败'; formError.value = true }
  finally { formLoading.value = false }
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

const avatarPlaceholderBg = computed(() => {
  const name = auth.user?.nickname || auth.user?.username || '?'
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash)
  return AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length]
})

const avatarInitial = computed(() => (auth.user?.nickname || auth.user?.username || '?')[0]?.toUpperCase())

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
</script>

<template>
  <div class="m-page">
    <div v-if="!auth.isLoggedIn" class="m-not-login" @click="openLogin">
      <div class="m-ghost-avatar">?</div>
      <h2>点击登录</h2>
      <p>登录后同步收藏和歌单</p>
    </div>

    <template v-else>
      <!-- 头部区域 -->
      <div class="m-hero">
        <div class="m-hero-bg" :class="{ 'has-image': auth.bgImageSrc }">
          <img v-if="auth.bgImageSrc" :src="auth.bgImageSrc" class="m-hero-bg-img" />
        </div>

        <div class="m-avatar" @click="router.push('/m/profile/detail')">
          <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="m-av-img" />
          <span v-else class="m-av-text" :style="{ background: avatarPlaceholderBg }">{{ avatarInitial }}</span>
        </div>

        <h1 class="m-name">{{ auth.user?.nickname || auth.user?.username }}</h1>
        <p class="m-id">@{{ auth.user?.username }}</p>

        <button class="m-edit-btn" @click="openEdit">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          编辑
        </button>
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

    <!-- 编辑弹窗 -->
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
              <div class="s-avatar-row">
                <div class="s-avatar" @click="triggerAvatar">
                  <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="s-av-img" />
                  <span v-else class="s-av-text">{{ avatarInitial }}</span>
                  <div v-if="avatarLoading" class="s-av-spinner"></div>
                  <div class="s-av-camera"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg></div>
                </div>
                <input ref="avatarInput" type="file" accept="image/*" style="display:none" @change="onAvatarChange" />
                <span class="s-av-hint">点击更换头像</span>
              </div>

              <!-- 背景图 -->
              <div class="s-row">
                <label>个人页背景</label>
                <div class="s-bg-preview" @click="triggerBg">
                  <img v-if="auth.bgImageSrc" :src="auth.bgImageSrc" class="s-bg-img" />
                  <div v-else class="s-bg-empty">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
                    <span>点击上传</span>
                  </div>
                  <div v-if="bgLoading" class="s-av-spinner"></div>
                </div>
                <input ref="bgInput" type="file" accept="image/*" style="display:none" @change="onBgChange" />
              </div>

              <div class="s-row"><label>昵称</label><input v-model="nickname" type="text" placeholder="请输入昵称" maxlength="30" /></div>
              <div class="s-row">
                <label>性别</label>
                <div class="s-gender">
                  <label v-for="g in ['男', '女', '保密']" :key="g" class="s-g-opt" :class="{ active: gender === g }"><input type="radio" v-model="gender" :value="g" style="display:none" />{{ g }}</label>
                </div>
              </div>
              <div class="s-row"><label>生日</label><input v-model="birthday" type="date" /></div>
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

.m-not-login { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 100px 32px; gap: 10px; }
.m-ghost-avatar { width: 80px; height: 80px; border-radius: 50%; background: rgba(255,255,255,0.08); display: flex; align-items: center; justify-content: center; font-size: 36px; font-weight: 700; color: #555; }
.m-not-login h2 { font-size: 18px; color: #888; }
.m-not-login p { font-size: 13px; color: #555; }

/* 头部 */
.m-hero {
  position: relative; padding: 52px 20px 36px;
  display: flex; flex-direction: column; align-items: center; text-align: center;
  overflow: hidden;
}
.m-hero-bg {
  position: absolute; inset: 0;
  background: linear-gradient(160deg, #1a6b3a 0%, #28a86b 40%, #31c27c 70%, #5ddb92 100%);
}
.m-hero-bg.has-image { background: none; }
.m-hero-bg-img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.m-hero-bg::after {
  content: ''; position: absolute; inset: 0; opacity: 0.22;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  background-repeat: repeat; background-size: 128px 128px;
}

.m-avatar {
  width: 88px; height: 88px; border-radius: 50%; margin-bottom: 16px; position: relative; z-index: 1;
  border: 3px solid rgba(255,255,255,0.4); overflow: hidden;
}
.m-avatar:active { transform: scale(0.96); }
.m-av-img { width: 100%; height: 100%; object-fit: cover; }
.m-av-text {
  width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;
  font-size: 36px; font-weight: 700; color: #fff; user-select: none;
}

.m-name { position: relative; z-index: 1; font-size: 22px; font-weight: 700; color: #fff; margin-bottom: 2px; }
.m-id { position: relative; z-index: 1; font-size: 12px; color: rgba(255,255,255,0.5); margin-bottom: 14px; }

.m-edit-btn {
  position: relative; z-index: 1;
  display: inline-flex; align-items: center; gap: 5px;
  padding: 7px 18px; border: 1px solid rgba(255,255,255,0.3);
  border-radius: 18px; background: rgba(255,255,255,0.08);
  color: rgba(255,255,255,0.8); font-size: 12px; cursor: pointer;
  backdrop-filter: blur(4px);
}
.m-edit-btn:active { background: rgba(255,255,255,0.18); }

/* 信息卡片 */
.m-info-card {
  margin: -10px 16px 16px; position: relative; z-index: 1;
  background: rgba(255,255,255,0.04); border-radius: 14px; padding: 4px 0;
}
.m-info-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 15px 16px;
}
.m-info-k { font-size: 13px; color: #888; }
.m-info-v { font-size: 14px; color: #ccc; }

/* 菜单 */
.m-menu { margin: 0 16px 20px; border-radius: 14px; overflow: hidden; background: rgba(255,255,255,0.04); }
.m-menu-item { display: flex; justify-content: space-between; align-items: center; padding: 15px 16px; font-size: 14px; color: #ccc; border-bottom: 1px solid rgba(255,255,255,0.04); }
.m-menu-item:last-child { border-bottom: none; }
.m-menu-item:active { background: rgba(255,255,255,0.02); }
.m-arrow { font-size: 18px; color: #555; }
.m-logout-item { justify-content: center; color: #ec4141; font-size: 14px; }

/* 底部编辑弹窗 */
.sheet-overlay { position: fixed; inset: 0; z-index: 500; background: rgba(0,0,0,0.5); display: flex; align-items: flex-end; }
.sheet-panel { width: 100%; max-height: 85vh; background: #1a1a1a; border-radius: 20px 20px 0 0; display: flex; flex-direction: column; overflow: hidden; }
.sheet-bar { width: 40px; height: 4px; background: rgba(255,255,255,0.2); border-radius: 2px; margin: 10px auto 0; }
.sheet-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid rgba(255,255,255,0.06); }
.sheet-header h3 { font-size: 16px; color: #ddd; }
.sheet-close { width: 30px; height: 30px; border: none; border-radius: 50%; background: rgba(255,255,255,0.06); color: #888; font-size: 18px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.sheet-body { flex: 1; overflow-y: auto; padding: 20px; }
.s-avatar-row { display: flex; flex-direction: column; align-items: center; gap: 8px; margin-bottom: 24px; }
.s-avatar { width: 72px; height: 72px; border-radius: 50%; overflow: hidden; position: relative; background: linear-gradient(135deg, #31c27c, #1a6b3a); display: flex; align-items: center; justify-content: center; }
.s-av-img { width: 100%; height: 100%; object-fit: cover; }
.s-av-text { font-size: 28px; font-weight: 700; color: #fff; }
.s-av-spinner { position: absolute; inset: 0; background: rgba(0,0,0,0.3); border: 3px solid rgba(255,255,255,0.3); border-top-color: #fff; border-radius: 50%; animation: spin .6s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.s-av-camera { position: absolute; bottom: 0; right: 0; width: 24px; height: 24px; border-radius: 50%; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; color: #fff; }
.s-av-hint { font-size: 12px; color: #888; }

.s-bg-preview {
  width: 100%; height: 80px; border-radius: 10px; overflow: hidden;
  border: 1px dashed rgba(255,255,255,0.12); cursor: pointer; position: relative;
}
.s-bg-img { width: 100%; height: 100%; object-fit: cover; }
.s-bg-empty {
  width: 100%; height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 4px;
  color: #666; font-size: 12px; background: rgba(255,255,255,0.03);
}

.s-row { margin-bottom: 16px; }
.s-row label { display: block; font-size: 12px; color: #888; margin-bottom: 6px; }
.s-row input { width: 100%; padding: 10px 12px; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; background: rgba(255,255,255,0.04); color: #ddd; font-size: 14px; outline: none; box-sizing: border-box; }
.s-row input:focus { border-color: #31c27c; }
.s-gender { display: flex; gap: 8px; }
.s-g-opt { flex: 1; padding: 10px; text-align: center; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; font-size: 13px; color: #888; cursor: pointer; }
.s-g-opt.active { border-color: #31c27c; color: #31c27c; background: rgba(49,194,124,0.1); }
.s-msg { text-align: center; font-size: 12px; padding: 6px 0; color: #31c27c; }
.s-msg.error { color: #ec4141; }
.s-save { width: 100%; padding: 13px; border: none; border-radius: 10px; background: #31c27c; color: #fff; font-size: 15px; font-weight: 500; cursor: pointer; margin-top: 8px; }
.s-save:disabled { opacity: .5; }

.sheet-enter-active { transition: all .3s ease; }
.sheet-leave-active { transition: all .25s ease; }
.sheet-enter-from .sheet-panel { transform: translateY(100%); }
.sheet-leave-to .sheet-panel { transform: translateY(100%); }
.sheet-enter-from, .sheet-leave-to { background: transparent; }

/* 密码弹窗 */
.pwd-overlay { position: fixed; inset: 0; z-index: 300; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; }
.pwd-box { width: 300px; background: #1a1a1a; border-radius: 14px; padding: 24px; }
.pwd-box h3 { font-size: 16px; color: #ddd; text-align: center; margin-bottom: 16px; }
.pwd-box input { width: 100%; padding: 10px 12px; margin-bottom: 10px; border: 1px solid rgba(255,255,255,0.1); border-radius: 8px; background: rgba(255,255,255,0.05); color: #ddd; font-size: 14px; outline: none; }
.pwd-box input:focus { border-color: #31c27c; }
.pwd-err { color: #ec4141; font-size: 12px; margin-bottom: 6px; }
.pwd-ok { color: #31c27c; font-size: 12px; margin-bottom: 6px; text-align: center; }
.pwd-btns { display: flex; gap: 10px; }
.pwd-btns button { flex: 1; padding: 10px; border-radius: 8px; font-size: 14px; cursor: pointer; }
.pwd-btns button:first-child { border: 1px solid rgba(255,255,255,0.1); background: transparent; color: #888; }
.pwd-btns button:last-child { border: none; background: #31c27c; color: #fff; }
.pwd-btns button:last-child:disabled { opacity: .5; }
</style>
