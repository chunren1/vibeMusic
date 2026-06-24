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
  // 每5秒保存播放进度
  timeSaver = setInterval(() => store.savePlaybackTime(), 5000)
})
onUnmounted(() => {
  window.removeEventListener('song-change', updateFaved)
  clearInterval(timeSaver)
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
  <div v-if="visible" class="mbp m-glass-gold" :style="{ bottom: bottomOffset + 'px' }" @click="goToPlayer">
    <div class="mbp-progress" :style="{ width: store.progress + '%' }"></div>
    <div class="mbp-cover" :style="store.currentSong.coverUrl ? { backgroundImage: `url(${store.currentSong.coverUrl}?param=80y80)` } : {}"></div>
    <div class="mbp-info" @click="goToPlayer">
      <div class="mbp-title">{{ store.currentSong.title }}</div>
      <div class="mbp-artist">{{ store.currentSong.artist }}</div>
    </div>
    <!-- 收藏按钮 -->
    <button class="mbp-btn sm" :class="{ faved: isFaved }" @click.stop="toggleFav" title="收藏">
      <SvgIcon :name="isFaved ? 'heart-fill' : 'heart'" :color="isFaved ? '#ff4757' : 'currentColor'" size="18" />
    </button>
    <!-- 右侧图标区（均匀分布） -->
    <div class="mbp-icons">
      <button class="mbp-btn sm" @click.stop="clickPrev" title="上一首">
        <SvgIcon name="previous" size="18" />
      </button>
      <button class="mbp-btn" @click.stop="togglePlayPause">
        <SvgIcon :name="store.isPlaying ? 'pause' : 'play'" size="24" />
      </button>
      <button class="mbp-btn sm" @click.stop="clickNext" title="下一首">
        <SvgIcon name="next" size="18" />
      </button>
      <button class="mbp-btn sm" @click.stop="openQueue" title="播放列表">
        <SvgIcon name="playlist" size="18" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.mbp {
  position: fixed; left: 0; right: 0; z-index: 100;
  height: 60px; display: flex; align-items: center; gap: 10px;
  padding: 0 10px; cursor: pointer;
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.mbp-icons { display: flex; align-items: center; justify-content: space-evenly; flex: 0 0 auto; min-width: 140px; }
.mbp-progress {
  position: absolute; top: 0; left: 0; height: 2px;
  background: var(--m-gradient-brand); border-radius: 0 4px 4px 0;
  transition: width 0.3s linear;
  box-shadow: 0 0 6px var(--m-primary-glow);
}
.mbp-cover {
  width: 44px; height: 44px; border-radius: 10px; flex-shrink: 0;
  background: rgba(255,255,255,0.05) center/cover no-repeat;
  box-shadow: 0 2px 8px rgba(0,0,0,0.4);
}
.mbp-info { flex: 1; min-width: 0; }
.mbp-title { font-size: 14px; font-weight: 600; color: var(--m-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mbp-artist { font-size: 12px; color: var(--m-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 1px; }
.mbp-btn { flex-shrink: 0; width: 40px; height: 40px; border: none; background: none; color: var(--m-text-primary); cursor: pointer; display: flex; align-items: center; justify-content: center; transition: color 0.2s, transform 0.2s var(--m-ease-spring); }
.mbp-btn:active { transform: scale(0.85); }
.mbp-btn.sm { width: 32px; height: 32px; color: var(--m-text-secondary); }
.mbp-btn.faved { color: var(--m-gold); filter: drop-shadow(0 0 4px var(--m-gold-glow)); }
</style>
