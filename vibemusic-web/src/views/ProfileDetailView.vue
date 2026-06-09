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
    const y = d.getFullYear()
    const m = d.getMonth() + 1
    const day = d.getDate()
    const weekDays = ['日', '一', '二', '三', '四', '五', '六']
    return `${y}年${m}月${day}日 星期${weekDays[d.getDay()]}`
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

function genderBadge(g) {
  const map = { '男': '♂', '女': '♀', '保密': '?' }
  return map[g] || '?'
}
</script>

<template>
  <div class="detail-page">
    <!-- 返回按钮 -->
    <div class="detail-header">
      <button class="back-btn" @click="router.back()">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <span class="detail-header-title">个人详情</span>
    </div>

    <!-- 未登录 -->
    <div v-if="!auth.isLoggedIn" class="empty-state">请先登录</div>

    <!-- 加载中 -->
    <div v-else-if="loading" class="loading-state">加载中...</div>

    <!-- 主体 -->
    <template v-else>
      <!-- 头像区域 -->
      <div class="detail-avatar-section">
        <div class="detail-avatar">
          <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="detail-avatar-img" />
          <span v-else class="detail-avatar-text">{{ (auth.user?.nickname || auth.user?.username)[0]?.toUpperCase() }}</span>
        </div>
        <h2 class="detail-name">{{ auth.user?.nickname || auth.user?.username }}</h2>
        <p class="detail-username">@{{ auth.user?.username }}</p>
      </div>

      <!-- 信息卡片 -->
      <div class="info-card">
        <h3 class="info-title">基本信息</h3>
        <div class="info-grid">
          <div class="info-item">
            <span class="info-key">用户ID</span>
            <span class="info-value">{{ auth.user?.userId }}</span>
          </div>
          <div class="info-item">
            <span class="info-key">用户名</span>
            <span class="info-value">{{ auth.user?.username }}</span>
          </div>
          <div class="info-item">
            <span class="info-key">昵称</span>
            <span class="info-value">{{ auth.user?.nickname || '—' }}</span>
          </div>
          <div class="info-item">
            <span class="info-key">性别</span>
            <span class="info-value">
              <span class="gender-badge" :class="auth.user?.gender || '未知'">{{ genderBadge(auth.user?.gender) }} {{ auth.user?.gender || '保密' }}</span>
            </span>
          </div>
          <div class="info-item">
            <span class="info-key">生日</span>
            <span class="info-value">
              {{ formatDate(auth.user?.birthday) }}
              <span v-if="calcAge(auth.user?.birthday)" class="age-tag">{{ calcAge(auth.user?.birthday) }}</span>
            </span>
          </div>
          <div class="info-item">
            <span class="info-key">头像</span>
            <span class="info-value">{{ auth.user?.avatar ? '已设置' : '未设置' }}</span>
          </div>
        </div>
      </div>

      <!-- 底部链接 -->
      <div class="detail-footer">
        <router-link to="/profile" class="footer-link">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          编辑个人资料
        </router-link>
      </div>
    </template>
  </div>
</template>

<style scoped>
.detail-page { min-height: 100%; background: #f5f5f5; }

.detail-header {
  display: flex; align-items: center; gap: 12px; padding: 16px 24px;
  background: #fff; border-bottom: 1px solid #eee; position: sticky; top: 0;
}
.back-btn {
  width: 36px; height: 36px; border: none; border-radius: 50%;
  background: #f0f0f0; cursor: pointer; display: flex; align-items: center;
  justify-content: center; color: #555; transition: background .2s;
}
.back-btn:hover { background: #e0e0e0; }
.detail-header-title { font-size: 16px; font-weight: 600; color: #333; }

.empty-state, .loading-state {
  text-align: center; padding: 80px 32px; color: #999; font-size: 15px;
}

/* 头像区域 */
.detail-avatar-section {
  display: flex; flex-direction: column; align-items: center;
  padding: 48px 32px 32px; background: #fff;
}
.detail-avatar {
  width: 120px; height: 120px; border-radius: 50%; overflow: hidden;
  background: linear-gradient(135deg, #31c27c, #1a8a4a);
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 6px 24px rgba(49,194,124,0.25); margin-bottom: 20px;
}
.detail-avatar-img { width: 100%; height: 100%; object-fit: cover; }
.detail-avatar-text { font-size: 48px; font-weight: 700; color: #fff; }
.detail-name { font-size: 24px; font-weight: 700; color: #1a1a1a; margin-bottom: 4px; }
.detail-username { font-size: 14px; color: #888; }

/* 信息卡片 */
.info-card {
  margin: 20px 24px; background: #fff; border-radius: 14px;
  padding: 24px; box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.info-title { font-size: 16px; font-weight: 600; color: #333; margin-bottom: 20px; padding-bottom: 12px; border-bottom: 1px solid #eee; }
.info-grid { display: flex; flex-direction: column; gap: 0; }
.info-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 0; border-bottom: 1px solid #f5f5f5;
}
.info-item:last-child { border-bottom: none; }
.info-key { font-size: 14px; color: #888; }
.info-value { font-size: 14px; color: #333; font-weight: 500; }
.gender-badge {
  display: inline-flex; align-items: center; gap: 4px;
}
.gender-badge.男 { color: #4a90d9; }
.gender-badge.女 { color: #e86a9e; }
.age-tag {
  margin-left: 8px; padding: 2px 8px; border-radius: 10px;
  background: #e8f5e9; color: #31c27c; font-size: 12px;
}

.detail-footer {
  padding: 0 24px 40px; display: flex; justify-content: center;
}
.footer-link {
  display: flex; align-items: center; gap: 6px; color: #31c27c;
  text-decoration: none; font-size: 14px; transition: opacity .2s;
}
.footer-link:hover { opacity: 0.8; }
</style>
