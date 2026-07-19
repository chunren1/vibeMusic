<script setup>
defineOptions({ name: "MXxxView" })
import { ref, onMounted } from "vue"
import { usePlayerStore } from "@/stores/player"
import request from "@/api/request"

const player = usePlayerStore()
const loading = ref(false)
const items = ref([])

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get("/xxx")
    items.value = res.data || []
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

function playItem(item) {
  player.playSongFromApi(item.sourceId, item.name, item.artist, item.coverUrl || "", item.platform)
}

onMounted(() => fetchData())
</script>

<template>
  <div class="m-page">
    <h1 class="m-title">Page Title</h1>
    <div v-if="loading" class="m-empty">Loading...</div>
    <div v-else-if="!items.length" class="m-empty">No content</div>
    <div v-else class="m-list">
      <div v-for="item in items" :key="item.id" class="m-card tap-scale" @click="playItem(item)">
        <div class="m-card-cover">J</div>
        <div class="m-card-info">
          <div class="m-card-name">{{ item.name }}</div>
          <div class="m-card-artist">{{ item.artist }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.m-page { padding: 16px; padding-bottom: calc(80px + var(--m-safe-bottom, 0px)); background: var(--m-bg-base, #0d0d0d); min-height: 100vh; }
.m-title { font-size: 20px; font-weight: 700; color: var(--m-text-primary, #fff); margin-bottom: 16px; }
.m-empty { color: var(--m-text-secondary, #888); text-align: center; padding: 80px 0; font-size: 14px; }
.m-list { display: flex; flex-direction: column; gap: 8px; }
.m-card { display: flex; align-items: center; gap: 12px; padding: 12px; border-radius: 10px; background: var(--m-bg-card, #1a1a1a); }
.m-card-cover { width: 48px; height: 48px; border-radius: 8px; background: #333; display: flex; align-items: center; justify-content: center; font-size: 20px; color: #666; flex-shrink: 0; }
.m-card-info { flex: 1; min-width: 0; }
.m-card-name { font-size: 15px; font-weight: 500; color: var(--m-text-primary, #fff); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-card-artist { font-size: 13px; color: var(--m-text-secondary, #888); margin-top: 2px; }
.tap-scale:active { transform: scale(0.97); }
</style>