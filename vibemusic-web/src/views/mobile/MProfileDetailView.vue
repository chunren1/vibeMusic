<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)

onMounted(async () => {
  if (auth.isLoggedIn) {
    loading.value = true
    await auth.refreshUser()
    loading.value = false
  }
})

function formatDate(dateStr) {
  if (!dateStr) return '未设置'
  try {
    const d = new Date(dateStr + 'T00:00:00')
    const weekDays = ['日', '一', '二', '三', '四', '五', '六']
    return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 星期${weekDays[d.getDay()]}`
  } catch { return dateStr }
}

function calcAge(birthday) {
  if (!birthday) return null
  try {
    const birth = new Date(birthday + 'T00:00:00')
    const now = new Date()
    let age = now.getFullYear() - birth.getFullYear()
    const m = now.getMonth() - birth.getMonth()
    if (m < 0 || (m === 0 && now.getDate() < birth.getDate())) age--
    return age > 0 ? `${age}岁` : null
  } catch { return null }
}

function genderLabel(g) {
  return { '男': '♂ 男', '女': '♀ 女', '保密': '保密' }[g] || '保密'
}
</script>

<template>
  <div class="m-detail-page">
    <!-- 顶栏 -->
    <div class="m-detail-header">
      <button class="m-back-btn" @click="router.back()">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <span>个人详情</span>
    </div>

    <div v-if="!auth.isLoggedIn" class="m-empty">请先登录</div>
    <div v-else-if="loading" class="m-empty">加载中...</div>

    <template v-else>
      <!-- 头像区 -->
      <div class="m-avatar-area">
        <div class="m-avatar-circle">
          <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="m-av-img" />
          <span v-else class="m-av-text">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</span>
        </div>
        <h2>{{ auth.user?.nickname || auth.user?.username }}</h2>
        <p class="m-uname">@{{ auth.user?.username }}</p>
      </div>

      <!-- 详情卡片 -->
      <div class="m-info-card">
        <h3>基本信息</h3>
        <div class="m-info-row"><span class="m-info-k">用户ID</span><span class="m-info-v">{{ auth.user?.userId }}</span></div>
        <div class="m-info-row"><span class="m-info-k">用户名</span><span class="m-info-v">{{ auth.user?.username }}</span></div>
        <div class="m-info-row"><span class="m-info-k">昵称</span><span class="m-info-v">{{ auth.user?.nickname || '—' }}</span></div>
        <div class="m-info-row"><span class="m-info-k">性别</span><span class="m-info-v">{{ genderLabel(auth.user?.gender) }}</span></div>
        <div class="m-info-row">
          <span class="m-info-k">生日</span>
          <span class="m-info-v">
            {{ formatDate(auth.user?.birthday) }}
            <span v-if="calcAge(auth.user?.birthday)" class="m-age-tag">{{ calcAge(auth.user?.birthday) }}</span>
          </span>
        </div>
        <div class="m-info-row"><span class="m-info-k">头像</span><span class="m-info-v">{{ auth.user?.avatar ? '已设置' : '未设置' }}</span></div>
      </div>

      <div class="m-footer-link">
        <router-link to="/m/profile">编辑个人资料 →</router-link>
      </div>
    </template>
  </div>
</template>

<style scoped>
.m-detail-page { min-height: 100vh; min-height: 100dvh; padding-bottom: 60px; }

.m-detail-header {
  display: flex; align-items: center; gap: 12px; padding: 16px;
  position: sticky; top: 0; background: #0a0a0a; z-index: 10;
}
.m-back-btn {
  width: 34px; height: 34px; border: none; border-radius: 50%;
  background: rgba(255,255,255,0.06); cursor: pointer; display: flex;
  align-items: center; justify-content: center; color: #ccc;
}
.m-detail-header span { font-size: 16px; color: #ddd; }

.m-empty { text-align: center; padding: 100px 32px; color: #666; font-size: 14px; }

.m-avatar-area {
  display: flex; flex-direction: column; align-items: center;
  padding: 40px 24px 32px;
}
.m-avatar-circle {
  width: 100px; height: 100px; border-radius: 50%; overflow: hidden;
  background: linear-gradient(135deg, #31c27c, #1a6b3a);
  display: flex; align-items: center; justify-content: center;
  margin-bottom: 16px;
}
.m-av-img { width: 100%; height: 100%; object-fit: cover; }
.m-av-text { font-size: 40px; font-weight: 700; color: #fff; }
.m-avatar-area h2 { font-size: 20px; font-weight: 600; color: #ddd; }
.m-uname { font-size: 13px; color: #888; margin-top: 4px; }

.m-info-card {
  margin: 0 16px 20px; background: rgba(255,255,255,0.04);
  border-radius: 14px; padding: 20px;
}
.m-info-card h3 {
  font-size: 15px; font-weight: 600; color: #ccc;
  margin-bottom: 14px; padding-bottom: 10px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.m-info-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 13px 0; border-bottom: 1px solid rgba(255,255,255,0.03);
}
.m-info-row:last-child { border-bottom: none; }
.m-info-k { font-size: 13px; color: #888; }
.m-info-v { font-size: 14px; color: #ddd; }
.m-age-tag {
  margin-left: 8px; padding: 1px 8px; border-radius: 10px;
  background: rgba(49,194,124,0.15); color: #31c27c; font-size: 11px;
}

.m-footer-link {
  text-align: center; padding: 10px 20px;
}
.m-footer-link a { color: #31c27c; text-decoration: none; font-size: 14px; }
</style>
