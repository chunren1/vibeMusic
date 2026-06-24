<script setup>
import { ref, onMounted } from 'vue'
import { getFavorites, removeFavoritesBatch } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const player = usePlayerStore()
const favStore = useFavoriteStore()
const songs = ref([])
const manageMode = ref(false)
const selectedIds = ref(new Set())

favStore.fetchFavIds()

function play(song) {
  if (manageMode.value) return
  player.playSongFromApi(song.sourceId, song.songName, song.artist, song.coverUrl || '')
}

function toggleSelect(sourceId) {
  const s = new Set(selectedIds.value)
  if (s.has(sourceId)) {
    s.delete(sourceId)
  } else {
    s.add(sourceId)
  }
  selectedIds.value = s
}

function toggleManage() {
  manageMode.value = !manageMode.value
  selectedIds.value = new Set()
}

const removing = ref(false)
async function doBatchRemove() {
  if (removing.value || !selectedIds.value.size) return
  removing.value = true
  try {
    const ids = [...selectedIds.value]
    await removeFavoritesBatch(ids)
    // 更新本地列表 + store
    songs.value = songs.value.filter(s => !selectedIds.value.has(s.sourceId))
    ids.forEach(id => favStore.ids.delete(id))
    selectedIds.value = new Set()
    manageMode.value = false
    window.toast?.('已移除 ' + ids.length + ' 首', 'success')
  } catch {
    window.toast?.('操作失败', 'error')
  } finally { removing.value = false }
}

onMounted(() => {
  getFavorites().then(r => { songs.value = r.data || [] }).catch(() => {})
})
</script>

<template>
  <div class="m-page">
    <div class="m-header">
      <h2 class="m-title">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#ffc107" stroke-width="2" style="vertical-align:middle;margin-right:6px"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
        我的收藏
      </h2>
      <button class="m-manage-btn" @click="toggleManage">{{ manageMode ? '完成' : '管理' }}</button>
    </div>
    <p class="m-sub">{{ songs.length }} 首</p>

    <div class="m-list">
      <div v-for="(s, i) in songs" :key="s.sourceId"
        class="m-item" :class="{
          playing: !manageMode && player.currentSong.id === s.sourceId && player.isPlaying,
          selected: manageMode && selectedIds.has(s.sourceId)
        }" @click="manageMode ? toggleSelect(s.sourceId) : play(s)">
        <div v-if="manageMode" class="m-check">
          <svg v-if="selectedIds.has(s.sourceId)" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#31c27c" stroke-width="2"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
          <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="rgba(255,255,255,.2)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/></svg>
        </div>
        <div class="m-cover" :style="s.coverUrl ? { backgroundImage: `url(${s.coverUrl}?param=80y80)` } : { background: '#1a1a2e' }">
          <span v-if="player.currentSong.id === s.sourceId && player.isPlaying" class="m-eq"><span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span></span>
        </div>
        <div class="m-info">
          <div class="m-name">{{ s.songName }}</div>
          <div class="m-artist">{{ s.artist }}</div>
        </div>
        <button v-if="!manageMode" :class="{ faved: favStore.isFav(s.sourceId) }" @click.stop="favStore.toggleFav(s)">
          <svg viewBox="0 0 24 24" width="18" height="18" :fill="favStore.isFav(s.sourceId) ? '#ffc107' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
        </button>
      </div>
    </div>

    <!-- 批量删除栏 -->
    <div v-if="manageMode && selectedIds.size" class="m-batch-bar">
      <button class="m-batch-btn" :disabled="removing" @click="doBatchRemove">
        {{ removing ? '删除中...' : `删除 (${selectedIds.size})` }}
      </button>
    </div>

    <div v-if="!songs.length" class="m-empty">还没有收藏歌曲</div>
  </div>
</template>

<style scoped>
.m-page { padding: 12px 16px; padding-bottom: 140px; }
.m-header { display: flex; align-items: center; justify-content: space-between; }
.m-title { font-size: 20px; font-weight: 700; color: #e0e0e0; }
.m-manage-btn {
  padding: 5px 14px; border: 1px solid rgba(255,255,255,.15); border-radius: 16px;
  background: transparent; color: #777; font-size: 13px; cursor: pointer;
}
.m-sub { font-size: 12px; color: #888; margin: 4px 0 16px; }
.m-list { display: flex; flex-direction: column; gap: 2px; }
.m-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 8px; border-radius: 10px;
  border-bottom: 1px solid rgba(255,255,255,0.03);
}
.m-item:active { background: rgba(255,255,255,.03); }
.m-item.playing { background: rgba(49,194,124,.06); }
.m-item.selected { background: rgba(49,194,124,.06); }
.m-check { flex-shrink: 0; display: flex; align-items: center; opacity: .6; }
.m-cover {
  width: 40px; height: 40px; border-radius: 6px; flex-shrink: 0;
  background-color: rgba(255,255,255,0.06);
  background-position: center; background-size: cover; background-repeat: no-repeat;
  display: flex; align-items: center; justify-content: center;
}
.m-eq { display: flex; align-items: flex-end; gap: 2px; height: 14px; }
.m-eq .eq-bar { width: 2px; background: #31c27c; border-radius: 1px; }
.m-eq .eq-bar:nth-child(1) { height: 7px; }
.m-eq .eq-bar:nth-child(2) { height: 12px; }
.m-eq .eq-bar:nth-child(3) { height: 5px; }
.m-info { flex: 1; min-width: 0; }
.m-name { font-size: 14px; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-artist { font-size: 12px; color: #888; margin-top: 2px; }
.m-item button {
  border: none; background: none; font-size: 16px; padding: 4px; cursor: pointer; color: #555; flex-shrink: 0;
}
.m-item button.faved { color: #ffc107; }
.m-empty { text-align: center; padding: 60px 0; color: #666; font-size: 14px; }

/* 批量操作底栏 */
.m-batch-bar {
  position: fixed; bottom: 116px; left: 0; right: 0; z-index: 50;
  display: flex; justify-content: center; gap: 12px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px));
  background: rgba(10,10,10,.95);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid rgba(255,255,255,.06);
}
.m-batch-btn {
  padding: 8px 28px; border-radius: 20px;
  border: 1px solid rgba(255,255,255,.12);
  background: transparent; color: #aaa; font-size: 13px; cursor: pointer;
  transition: .15s;
}
.m-batch-btn:active { border-color: #e04040; color: #e04040; }
.m-batch-btn:disabled { opacity: .4; }
</style>
