<script setup>
import { computed, ref, onMounted } from 'vue'
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
onMounted(() => {
  window._openQueuePopup = () => { showQueue.value = true }

  // 页面关闭/隐藏时强制保存（beforeunload 在移动端不可靠，pagehide 兜底）
  const flush = () => store.flushSave()
  window.addEventListener('beforeunload', flush)
  window.addEventListener('pagehide', flush)
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) store.flushSave()
  })

  // 防止后台切回时浏览器自动刷新页面
  let wasHidden = false
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      wasHidden = true
    } else if (wasHidden) {
      wasHidden = false
      // 恢复前台时不重新挂载，保留当前页面状态
    }
  })
})
</script>

<template>
  <div class="mobile-shell" :class="{ 'no-padding': isPlayerPage }">
    <RouterView />
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
  background: #0a0a0a;
  color: #e0e0e0;
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
