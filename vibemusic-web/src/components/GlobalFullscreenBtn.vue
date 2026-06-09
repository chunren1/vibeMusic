<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const isFullscreen = ref(!!document.fullscreenElement)

function onFsChange() {
  isFullscreen.value = !!document.fullscreenElement
}

function toggleFullscreen() {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen().catch(() => {})
  } else {
    document.exitFullscreen().catch(() => {})
  }
}

onMounted(() => {
  document.addEventListener('fullscreenchange', onFsChange)
})
onUnmounted(() => {
  document.removeEventListener('fullscreenchange', onFsChange)
})
</script>

<template>
  <button
    class="gfs-btn"
    :title="isFullscreen ? '退出全屏' : '全屏'"
    @click="toggleFullscreen"
  >
    <!-- 进入全屏：四角扩张图标 -->
    <svg v-if="!isFullscreen" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <polyline points="15 3 21 3 21 9"/>
      <polyline points="9 21 3 21 3 15"/>
      <line x1="21" y1="3" x2="14" y2="10"/>
      <line x1="3" y1="21" x2="10" y2="14"/>
    </svg>
    <!-- 退出全屏：四角收缩图标 -->
    <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <polyline points="4 8 4 3 9 3"/>
      <polyline points="20 16 20 21 15 21"/>
      <line x1="4" y1="3" x2="11" y2="10"/>
      <line x1="20" y1="21" x2="13" y2="14"/>
    </svg>
  </button>
</template>

<style scoped>
.gfs-btn {
  position: fixed;
  z-index: 9999;
  top: 12px;
  right: 12px;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.25);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  color: rgba(255, 255, 255, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}
.gfs-btn:hover {
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
}
.gfs-btn:active {
  transform: scale(0.9);
}
</style>
