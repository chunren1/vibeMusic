<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const keyword = ref(route.query.keyword || '')
const results = ref([])

function onSearch() {
  // TODO: 调用搜索 API
  console.log('搜索:', keyword.value)
}

// 监听 URL query 变化
import { watch } from 'vue'
watch(() => route.query.keyword, (val) => {
  keyword.value = val || ''
  if (val) onSearch()
}, { immediate: true })
</script>

<template>
  <div class="search-page">
    <h1 class="title">搜索</h1>

    <div class="search-box">
      <input
        v-model="keyword"
        @keyup.enter="onSearch"
        placeholder="输入关键字搜索..."
        class="search-input"
      />
    </div>

    <div v-if="results.length === 0" class="empty">
      <p>输入关键词开始搜索</p>
    </div>
  </div>
</template>

<style scoped>
.search-page { padding: 32px; }

.title {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 24px;
  color: #fff;
}

.search-box { max-width: 520px; margin-bottom: 40px; }

.search-input {
  width: 100%;
  padding: 10px 16px;
  border: 1px solid #333;
  border-radius: 8px;
  background: #1e2024;
  color: #fff;
  font-size: 14px;
  outline: none;
}
.search-input:focus { border-color: #ec4141; }
.search-input::placeholder { color: #666; }

.empty {
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #666;
  border: 1px dashed #333;
  border-radius: 8px;
}
</style>
