<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { usePlayerStore } from '@/stores/player'
import PlayerBar from '@/components/PlayerBar.vue'
import LoginModal from '@/components/LoginModal.vue'
import ToastMessage from '@/components/ToastMessage.vue'
import { useToast } from '@/composables/useToast'

const { message, type, show, toast } = useToast()
window.toast = toast // 全局可用

const authStore = useAuthStore()
// 初始化播放器 store（确保移动端也有 vibeAudioSetSrc / onEnded 等功能）
usePlayerStore()

const isMobile = ref(checkDevice())

function checkDevice() {
  const ua = navigator.userAgent || ''
  return window.innerWidth < 768 ||
    /Android|iPhone|iPad|iPod|webOS|BlackBerry|Windows Phone/i.test(ua)
}

// body 底色统一由 CSS token 控制（var(--bg-base)），无需 JS 同步赋值

let resizeDebounceTimer = null
function onResize() {
  // 窗口最小化时 innerWidth 为 0，不切换布局，避免销毁重建全部组件
  if (window.innerWidth === 0) return

  // 去抖 300ms，防止快速拖拽窗口边缘时反复切换
  clearTimeout(resizeDebounceTimer)
  resizeDebounceTimer = setTimeout(() => {
    isMobile.value = checkDevice()
  }, 300)
}

onMounted(() => {
  window.addEventListener('resize', onResize)
  authStore.tryRestoreSession()
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  clearTimeout(resizeDebounceTimer)
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
      <div class="logo">
        <img src="/logo.png" alt="vibeMusic" class="logo-img" />
      </div>
      <nav class="nav">
        <router-link to="/" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
          <span class="nav-label">主页</span>
        </router-link>
        <router-link to="/playlists" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
          <span class="nav-label">歌单</span>
        </router-link>
        <router-link to="/chat" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          <span class="nav-label">AI助手</span>
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
      <RouterView v-slot="{ Component }">
        <keep-alive include="ChatView">
          <component :is="Component" />
        </keep-alive>
      </RouterView>
    </main>
    <PlayerBar />
  </div>

  <!-- 全局登录弹窗（Teleport 到 body） -->
  <LoginModal
    v-model:visible="authStore.showLoginModal"
    @success="authStore.closeLogin()"
  />

  <!-- 全局 Toast -->
  <ToastMessage :message="message" :type="type" :show="show" @close="show = false" />
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { height: 100%; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: var(--bg-base); color: var(--text-primary);
  touch-action: manipulation;  /* 消除移动端 300ms 点击延迟 */
  -webkit-tap-highlight-color: transparent;  /* 去掉点击高亮 */
}
</style>

<style scoped>
.mobile-root { height: 100%; overflow-y: auto; }
.app-layout { display: flex; height: 100%; }

.sidebar {
  width: 260px; background: var(--bg-elevated);
  border-right: 1px solid var(--bg-hover);
  display: flex; flex-direction: column; flex-shrink: 0;
}
.logo {
  display: flex; align-items: center; justify-content: center;
  padding: 16px 28px; border-bottom: 1px solid var(--bg-hover);
}
.logo-img {
  width: 100%; height: auto; max-height: 180px; object-fit: contain;
  /* F4：logo.png 为白底资源（无透明版），暗色圆角卡底框住白块，视觉可接受 */
  background: var(--bg-card); border-radius: var(--radius-md); padding: 8px 12px;
}
.logo-text { display: none; }
.nav { flex: 1; padding: 20px 0; }

.nav-item {
  display: flex; align-items: center; gap: 14px;
  padding: 18px 28px; color: var(--text-secondary);
  text-decoration: none; font-size: 18px; transition: color .2s, background .2s, border-left-color .2s;
  border-left: 4px solid transparent;
}
.nav-item:hover { color: var(--text-primary); background: var(--bg-hover); }
.nav-item.active {
  color: var(--primary); background: rgba(49, 194, 124, .1);
  border-left-color: var(--primary);
}
.nav-label { font-size: 18px; margin-left: 4px; }

.main {
  flex: 1; overflow-y: auto; padding-bottom: 88px;
  background: var(--bg-base);
}
</style>
