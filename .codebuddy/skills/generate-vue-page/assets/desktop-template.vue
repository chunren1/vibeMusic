<script setup>
defineOptions({ name: 'XxxView' })
import { ref, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'
import request from '@/api/request'

const player = usePlayerStore()
const auth = useAuthStore()

const loading = ref(false)
const items = ref([])

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/xxx')
    items.value = res.data || []
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>

<template>
  <TopBar />
  <div class="page">
    <h1 class="page-title">页面标题</h1>

    <!-- Loading -->
    <div v-if="loading" class="loading">加载中...</div>

    <!-- Empty -->
    <div v-else-if="!items.length" class="empty">暂无数据</div>

    <!-- List -->
    <div v-else class="list">
      <div v-for="item in items" :key="item.id" class="card">
        {{ item.name }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; padding: 24px 32px; }
.page-title { font-size: 24px; font-weight: 600; color: #1a1a1a; margin-bottom: 20px; }
.loading, .empty { color: #999; text-align: center; padding: 60px 0; font-size: 14px; }

.list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}
.card {
  padding: 16px; border-radius: 12px; background: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04); transition: .15s;
  cursor: pointer;
}
.card:hover { transform: translateY(-2px); box-shadow: 0 4px 16px rgba(0,0,0,0.08); }

@media (max-width: 768px) { .page { padding: 16px; } }
</style>
