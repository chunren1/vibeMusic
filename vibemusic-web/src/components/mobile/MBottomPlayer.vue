<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const route = useRoute()
const router = useRouter()
const store = usePlayerStore()
const favStore = useFavoriteStore()
const audio = store.audio

// 收藏
const isFaved = ref(false)
favStore.fetchFavIds()

function toggleFav(e) {
  e.stopPropagation()
  const id = store.currentSong.id
  if (!id) return
  const was = favStore.isFav(id)
  isFaved.value = !was
  favStore.toggleFav({ sourceId: id, name: store.currentSong.title, artist: store.currentSong.artist, coverUrl: store.currentSong.coverUrl })
}

// 同步收藏状态
const updateFaved = () => { isFaved.value = favStore.isFav(store.currentSong.id) }

// 搜索页贴底，播放页隐藏（由 MobileShell 控制），其他页在 TabBar 上方
const isSearch = computed(() => route.path.startsWith('/m/search'))
const bottomOffset = computed(() => isSearch.value ? 0 : 56)

// 只有有歌在播放时才显示
const visible = computed(() => !!store.currentSong.id)

onMounted(() => {
  // 监听切歌同步收藏状态
  window.addEventListener('song-change', updateFaved)
  // 恢复收藏状态
  isFaved.value = favStore.isFav(store.currentSong.id)
  // 恢复音频源（刷新后 audio 是新的，需要重新设 src）
  if (!audio.src || audio.src === window.location.href) {
    store.restorePlayback()
  }
})
onUnmounted(() => {
  window.removeEventListener('song-change', updateFaved)
})

function goToPlayer() {
  router.push('/m/player')
}

function togglePlayPause(e) {
  e.stopPropagation()
  store.togglePlay()
}

function clickPrev(e) { e.stopPropagation(); store.prev() }
function clickNext(e) { e.stopPropagation(); store.next() }
function openQueue(e) { e.stopPropagation(); window._openQueuePopup?.() }
</script>

<template>
  <div v-if="visible" class="mbp" :style="{ bottom: bottomOffset + 'px' }" @click="goToPlayer">
    <div class="mbp-progress" :style="{ width: store.progress + '%' }"></div>
    <div class="mbp-cover" :style="store.currentSong.coverUrl ? { backgroundImage: `url(${store.currentSong.coverUrl}?param=80y80)` } : {}"></div>
    <div class="mbp-info" @click="goToPlayer">
      <div class="mbp-title">{{ store.currentSong.title }}</div>
      <div class="mbp-artist">{{ store.currentSong.artist }}</div>
    </div>
    <!-- 收藏按钮 -->
    <button class="mbp-btn sm" :class="{ faved: isFaved }" @click.stop="toggleFav" title="收藏">
      <svg viewBox="0 0 24 24" width="18" height="18" :fill="isFaved ? '#ffc107' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
    </button>
    <!-- 右侧图标区（均匀分布） -->
    <div class="mbp-icons">
      <button class="mbp-btn sm" @click.stop="clickPrev" title="上一首">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg>
      </button>
      <button class="mbp-btn" @click.stop="togglePlayPause">
        <svg v-if="store.isPlaying" viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
        <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><polygon points="6,4 20,12 6,20"/></svg>
      </button>
      <button class="mbp-btn sm" @click.stop="clickNext" title="下一首">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
      </button>
      <button class="mbp-btn sm" @click.stop="openQueue" title="播放列表">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.mbp { 
  position: fixed; left: 0; right: 0; z-index: 100;
  height: 60px; display: flex; align-items: center; gap: 8px;
  background: rgba(20, 20, 20, 0.95); backdrop-filter: blur(10px);
  border-top: 1px solid rgba(255,255,255,0.06);
  padding: 0 8px; cursor: pointer;
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.mbp-icons { display: flex; align-items: center; justify-content: space-evenly; flex: 0 0 auto; min-width: 140px; }
.mbp-progress {
  position: absolute; top: 0; left: 0; height: 2px;
  background: #31c27c; border-radius: 0 2px 2px 0;
  transition: width .3s linear;
}
.mbp-cover {
  width: 42px; height: 42px; border-radius: 8px; flex-shrink: 0;
  background: rgba(255,255,255,0.06) center/cover no-repeat;
}
.mbp-info { flex: 1; min-width: 0; }
.mbp-title { font-size: 14px; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mbp-artist { font-size: 12px; color: #888; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mbp-btn { flex-shrink: 0; width: 40px; height: 40px; border: none; background: none; color: #e0e0e0; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.mbp-btn.sm { width: 32px; height: 32px; color: #888; }
.mbp-btn.faved { color: #ffc107; }
</style>
