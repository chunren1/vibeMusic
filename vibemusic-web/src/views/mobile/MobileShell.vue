<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { usePlayerStore } from '@/stores/player'
import MBottomPlayer from '@/components/mobile/MBottomPlayer.vue'
import MTabBar from '@/components/mobile/MTabBar.vue'
import MQueuePopup from '@/components/mobile/MQueuePopup.vue'

const route = useRoute()
const store = usePlayerStore()
const isPlayerPage = computed(() => route.path.startsWith('/m/player'))
const showTabBar = computed(() => !route.path.startsWith('/m/search') && !isPlayerPage.value)
const showBottomPlayer = computed(() => !isPlayerPage.value)

// 全局播放列表弹窗
const showQueue = ref(false)
// 监听器引用提到 setup 作用域：App.vue 在 768px 断点用 v-if 整树切换桌面/移动视图，
// 不清理会在反复切换时叠加监听器与 window 钩子（round6 W6）
let flush = null
let visibilityFlush = null
let visibilityReset = null
onMounted(() => {
  window._openQueuePopup = () => { showQueue.value = true }

  // 页面关闭/隐藏时强制保存（beforeunload 在移动端不可靠，pagehide 兜底）
  flush = () => store.flushSave()
  window.addEventListener('beforeunload', flush)
  window.addEventListener('pagehide', flush)
  visibilityFlush = () => {
    if (document.hidden) store.flushSave()
  }
  document.addEventListener('visibilitychange', visibilityFlush)

  // 防止后台切回时浏览器自动刷新页面
  let wasHidden = false
  visibilityReset = () => {
    if (document.hidden) {
      wasHidden = true
    } else if (wasHidden) {
      wasHidden = false
      // 恢复前台时不重新挂载，保留当前页面状态
    }
  }
  document.addEventListener('visibilitychange', visibilityReset)
})

onUnmounted(() => {
  if (window._openQueuePopup) delete window._openQueuePopup
  if (flush) {
    window.removeEventListener('beforeunload', flush)
    window.removeEventListener('pagehide', flush)
  }
  if (visibilityFlush) document.removeEventListener('visibilitychange', visibilityFlush)
  if (visibilityReset) document.removeEventListener('visibilitychange', visibilityReset)
})
</script>

<template>
  <div class="mobile-shell" :class="{ 'no-padding': isPlayerPage }">
    <RouterView v-slot="{ Component }">
      <!-- Q2c：keep-alive 缓存与路由收敛保持一致（MHomeView 保留 + 桌面视图复用） -->
      <keep-alive :include="['MHomeView', 'LikesView', 'RecentView', 'PlaylistsView', 'ProfileView', 'ChatView']" :max="6">
        <component :is="Component" />
      </keep-alive>
    </RouterView>
    <MBottomPlayer v-if="showBottomPlayer" />
    <MTabBar v-if="showTabBar" />
    <MQueuePopup :visible="showQueue" @close="showQueue = false" />
  </div>
</template>

<style>
html.mobile {
  font-size: 14px;
}
html.mobile body {
  background: var(--m-bg-base);
  color: var(--m-text-primary);
  -webkit-tap-highlight-color: transparent;
}
</style>

<style scoped>
.mobile-shell {
  min-height: 100vh; min-height: 100dvh;
  padding-bottom: 60px;
  background: #0a0a0a;
}
.mobile-shell.no-padding { padding-bottom: 0; }
</style>
