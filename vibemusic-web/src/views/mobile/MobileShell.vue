<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import MBottomPlayer from '@/components/mobile/MBottomPlayer.vue'
import MTabBar from '@/components/mobile/MTabBar.vue'
import MQueuePopup from '@/components/mobile/MQueuePopup.vue'

const route = useRoute()
const isPlayerPage = computed(() => route.path.startsWith('/m/player'))
const showTabBar = computed(() => !route.path.startsWith('/m/search') && !isPlayerPage.value)
const showBottomPlayer = computed(() => !isPlayerPage.value)

// 全局播放列表弹窗
const showQueue = ref(false)
onMounted(() => {
  window._openQueuePopup = () => { showQueue.value = true }
})
</script>

<template>
  <div class="mobile-shell" :class="{ 'no-padding': isPlayerPage }">
    <RouterView :key="$route.fullPath" />
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
