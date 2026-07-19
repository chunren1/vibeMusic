<template>
  <div v-if="error" class="error-boundary">
    <div class="error-content">
      <svg-icon name="warning" class="error-icon" />
      <h2>页面出现异常</h2>
      <p class="error-msg">{{ error.message || '未知错误' }}</p>
      <button class="retry-btn" @click="retry">重新加载</button>
    </div>
  </div>
  <slot v-else />
</template>

<script setup>
import { ref, onErrorCaptured } from 'vue'

const error = ref(null)

onErrorCaptured((err, instance, info) => {
  console.error('[ErrorBoundary] 捕获到组件错误:', err, 'info:', info)
  error.value = err
  // 阻止错误继续冒泡
  return false
})

function retry() {
  error.value = null
}
</script>

<style scoped>
.error-boundary {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 2rem;
}
.error-content {
  text-align: center;
  color: #999;
}
.error-content h2 {
  font-size: 1.2rem;
  margin: 1rem 0 0.5rem;
  color: #ccc;
}
.error-icon {
  font-size: 3rem;
  color: #e74c3c;
}
.error-msg {
  font-size: 0.85rem;
  margin-bottom: 1.5rem;
  color: #666;
  max-width: 400px;
  word-break: break-all;
}
.retry-btn {
  background: #1db954;
  color: #fff;
  border: none;
  padding: 0.6rem 2rem;
  border-radius: 20px;
  font-size: 0.9rem;
  cursor: pointer;
  transition: background 0.2s;
}
.retry-btn:hover {
  background: #1ed760;
}
</style>
