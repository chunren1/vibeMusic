<script setup>
import { ref, onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePlayerStore } from '@/stores/player'
import PlayerBar from '@/components/PlayerBar.vue'
import LoginModal from '@/components/LoginModal.vue'
import GlobalFullscreenBtn from '@/components/GlobalFullscreenBtn.vue'

const authStore = useAuthStore()
// 初始化播放器 store（确保移动端也有 vibeAudioSetSrc / onEnded 等功能）
usePlayerStore()

const isMobile = ref(checkDevice())

function checkDevice() {
  const ua = navigator.userAgent || ''
  return window.innerWidth < 768 ||
    /Android|iPhone|iPad|iPod|webOS|BlackBerry|Windows Phone/i.test(ua)
}

// 立即设置 body 样式（同步，避免刷新闪白）
if (isMobile.value) {
  document.body.style.background = '#0a0a0a'
  document.body.style.color = '#e0e0e0'
}

function onResize() {
  isMobile.value = checkDevice()
  if (isMobile.value) {
    document.body.style.background = '#0a0a0a'
    document.body.style.color = '#e0e0e0'
  } else {
    document.body.style.background = '#ffffff'
    document.body.style.color = '#1a1a1a'
  }
}

onMounted(() => {
  window.addEventListener('resize', onResize)
})
</script>

<template>
  <!-- 移动端：无侧栏，无桌面播放条，全屏 -->
  <div v-if="isMobile" class="mobile-root">
    <RouterView />
  </div>

  <!-- 桌面端：固定侧栏布局 -->
  <div v-else class="app-layout">
    <aside class="sidebar">
      <div class="logo">♪ vibeMusic</div>
      <nav class="nav">
        <router-link to="/" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
          <span class="nav-label">主页</span>
        </router-link>
        <router-link to="/playlists" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
          <span class="nav-label">歌单</span>
        </router-link>
        <router-link to="/likes" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
          <span class="nav-label">我的收藏</span>
        </router-link>
        <router-link to="/recent" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
          <span class="nav-label">最近播放</span>
        </router-link>
        <router-link to="/profile" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          <span class="nav-label">我的</span>
        </router-link>
      </nav>
    </aside>
    <main class="main">
      <RouterView />
    </main>
    <PlayerBar />
  </div>

  <!-- 全局登录弹窗（Teleport 到 body） -->
  <LoginModal
    v-model:visible="authStore.showLoginModal"
    @success="authStore.closeLogin()"
  />

  <!-- 全局全屏切换按钮（移动端 + 桌面端通用） -->
  <GlobalFullscreenBtn />
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { height: 100%; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #0a0a0a; color: #e0e0e0;
  touch-action: manipulation;  /* 消除移动端 300ms 点击延迟 */
  -webkit-tap-highlight-color: transparent;  /* 去掉点击高亮 */
}
::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-thumb { background: rgba(0,0,0,.15); border-radius: 3px; }
</style>

<style scoped>
.mobile-root { height: 100%; overflow-y: auto; }
.app-layout { display: flex; height: 100%; }

.sidebar {
  width: 260px; background: #e8e8e8;
  border-right: 1px solid #ddd;
  display: flex; flex-direction: column; flex-shrink: 0;
}
.logo {
  height: 80px; display: flex; align-items: center;
  padding: 0 28px; font-size: 26px; font-weight: 700;
  color: #31c27c; border-bottom: 1px solid #ddd;
}
.nav { flex: 1; padding: 20px 0; }

.nav-item {
  display: flex; align-items: center; gap: 14px;
  padding: 18px 28px; color: #555;
  text-decoration: none; font-size: 18px; transition: all .2s;
  border-left: 4px solid transparent;
}
.nav-item:hover { color: #1a1a1a; background: rgba(0,0,0,.04); }
.nav-item.active {
  color: #31c27c; background: rgba(49, 194, 124, .1);
  border-left-color: #31c27c;
}
.nav-label { font-size: 18px; margin-left: 4px; }

.main {
  flex: 1; overflow-y: auto; padding-bottom: 88px;
  background: #f5f5f5;
}
</style>
