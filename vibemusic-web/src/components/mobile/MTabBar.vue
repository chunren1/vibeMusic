<script setup>
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const tabs = [
  { path: '/m', label: '首页', auth: false },
  { path: '/m/recent', label: '最近', auth: true },
  { path: '/m/playlists', label: '歌单', auth: true },
  { path: '/m/chat', label: 'AI', auth: false },
  { path: '/m/likes', label: '收藏', auth: true },
  { path: '/m/profile', label: '我的', auth: false },
]

function goTab(tab) {
  if (tab.auth && !auth.isLoggedIn) {
    auth.openLogin()
    return
  }
  router.push(tab.path)
}

function isActive(path) {
  if (path === '/m' && (route.path === '/m' || route.path === '/m/')) return true
  if (path !== '/m' && route.path.startsWith(path)) return true
  return false
}
</script>

<template>
  <nav class="mtab m-glass" v-show="!route.path.startsWith('/m/search') && !route.path.startsWith('/m/player')">
    <div class="mtab-item" :class="{ active: isActive('/m') }" @click="goTab(tabs[0])">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
      <span class="mtab-label">首页</span>
    </div>
    <div class="mtab-item" :class="{ active: isActive('/m/recent') }" @click="goTab(tabs[1])">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
      <span class="mtab-label">最近</span>
    </div>
    <div class="mtab-item" :class="{ active: isActive('/m/playlists') }" @click="goTab(tabs[2])">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
      <span class="mtab-label">歌单</span>
    </div>
    <div class="mtab-item" :class="{ active: isActive('/m/chat') }" @click="goTab(tabs[3])">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
      <span class="mtab-label">AI</span>
    </div>
    <div class="mtab-item" :class="{ active: isActive('/m/likes') }" @click="goTab(tabs[4])">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
      <span class="mtab-label">收藏</span>
    </div>
    <div class="mtab-item" :class="{ active: isActive('/m/profile') }" @click="goTab(tabs[5])">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
      <span class="mtab-label">我的</span>
    </div>
  </nav>
</template>

<style scoped>
.mtab {
  position: fixed; bottom: 0; left: 0; right: 0; z-index: 99;
  display: flex; justify-content: space-around; align-items: center;
  height: 56px;
  border-top: 1px solid rgba(255,255,255,0.06);
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.mtab-item {
  display: flex; flex-direction: column; align-items: center; gap: 3px;
  text-decoration: none; color: #666; font-size: 10px; flex: 1; padding: 6px 0; cursor: pointer;
}
.mtab-item.active { color: #31c27c; }
.mtab-label { font-size: 10px; }
</style>
