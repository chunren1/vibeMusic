<script setup>
import { ref, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { getFavorites, removeFavoritesBatch } from '@/api/song'
import { useFavoriteStore } from '@/stores/favorite'
import { usePlayerStore } from '@/stores/player'

const favStore = useFavoriteStore()
const player = usePlayerStore()
const favorites = ref([])
const currentPlayId = ref(null)
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)

// 批量管理
const manageMode = ref(false)
const selectedIds = ref(new Set())
const removing = ref(false)

favStore.fetchFavIds()

function toggleFav(fav) {
  favStore.toggleFav({ sourceId: fav.sourceId, name: fav.songName, artist: fav.artist, coverUrl: fav.coverUrl })
}

function openPlaylistPopup(fav) { playlistTargetSong.value = fav; showPlaylistPopup.value = true }

function play(fav) {
  if (manageMode.value) return
  currentPlayId.value = fav.sourceId
  player.playSongFromApi(fav.sourceId, fav.songName, fav.artist, fav.coverUrl || '')
}

function toggleManage() {
  manageMode.value = !manageMode.value
  selectedIds.value = new Set()
}

function toggleSelect(sourceId) {
  const s = new Set(selectedIds.value)
  s.has(sourceId) ? s.delete(sourceId) : s.add(sourceId)
  selectedIds.value = s
}

async function doBatchRemove() {
  if (removing.value || !selectedIds.value.size) return
  removing.value = true
  try {
    const ids = [...selectedIds.value]
    await removeFavoritesBatch(ids)
    favorites.value = favorites.value.filter(f => !selectedIds.value.has(f.sourceId))
    ids.forEach(id => favStore.ids.delete(id))
    selectedIds.value = new Set()
    manageMode.value = false
    window.toast?.('已移除', 'success')
  } catch { window.toast?.('操作失败', 'error') }
  finally { removing.value = false }
}

onMounted(() => {
  getFavorites().then(res => { favorites.value = res.data || [] }).catch(() => {})
})
</script>

<template>
  <TopBar />
  <div class="likes-page">
    <div class="page-header">
      <h2 class="page-title">我的收藏</h2>
      <button class="btn-manage" @click="toggleManage">{{ manageMode ? '完成' : '管理' }}</button>
    </div>
    <p class="subtitle">{{ favorites.length }} 首歌曲</p>

    <div v-if="favorites.length > 0" class="song-table">
      <div class="table-header">
        <span v-if="manageMode" class="th-check"></span>
        <span class="th-index">#</span>
        <span class="th-cover"></span>
        <span class="th-title">歌名</span>
        <span class="th-time">收藏时间</span>
        <span class="th-actions"></span>
      </div>
      <div
        v-for="(fav, idx) in favorites" :key="fav.sourceId"
        class="table-row"
        :class="{ playing: currentPlayId === fav.sourceId, selected: manageMode && selectedIds.has(fav.sourceId) }"
        @click="manageMode ? toggleSelect(fav.sourceId) : play(fav)"
      >
        <span v-if="manageMode" class="td-check">
          <svg v-if="selectedIds.has(fav.sourceId)" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#31c27c" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>
          <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="12" cy="12" r="10"/></svg>
        </span>
        <span class="td-index">
          <span v-if="!manageMode && currentPlayId === fav.sourceId && player.isPlaying" class="playing-eq"><span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span></span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="td-cover">
          <div class="cover-img" :style="fav.coverUrl ? { backgroundImage: 'url(' + fav.coverUrl + '?param=100y100)' } : {}" @click.stop="play(fav)"><SvgIcon v-if="!fav.coverUrl" name="music" :size="16" /><SvgIcon name="play" class="cover-hover" :size="18" /></div>
        </div>
        <div class="td-info" @click.stop="play(fav)">
          <span class="td-name" :class="{ active: currentPlayId === fav.sourceId }">{{ fav.songName }}</span>
          <span class="td-artist">{{ fav.artist || '-' }}</span>
        </div>
        <span class="td-time">{{ fav.createdAt ? new Date(fav.createdAt).toLocaleDateString() : '' }}</span>
        <div v-if="!manageMode" class="td-actions" @click.stop>
          <button class="action-btn fav-btn" :class="{ faved: favStore.isFav(fav.sourceId) }" @click.stop="toggleFav(fav)" :title="favStore.isFav(fav.sourceId) ? '取消收藏' : '收藏'"><SvgIcon :name="favStore.isFav(fav.sourceId) ? 'star-fill' : 'star'" :size="16" /></button>
          <button class="action-btn add-btn" @click.stop="openPlaylistPopup(fav)" title="加入歌单"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>
        </div>
      </div>
    </div>

    <div v-if="manageMode && selectedIds.size" class="batch-bar">
      <button class="batch-btn" :disabled="removing" @click="doBatchRemove">
        {{ removing ? '移除中...' : `移除 (${selectedIds.size})` }}
      </button>
    </div>

    <div v-else class="empty">
      <p>还没有收藏歌曲</p>
      <p class="hint">去主页搜索喜欢的音乐吧</p>
    </div>
  </div>

  <PlaylistPopup v-if="showPlaylistPopup" :song="playlistTargetSong" @close="showPlaylistPopup = false" @done="showPlaylistPopup = false" />
</template>

<style scoped>
.likes-page { padding: 0 32px 80px; }
.page-header { display: flex; align-items: flex-end; justify-content: space-between; padding-top: 32px; margin-bottom: 28px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--text-primary); margin-bottom: 4px; }
.subtitle { font-size: 13px; color: var(--text-tertiary); margin-bottom: 20px; }
.btn-manage {
  padding: 10px 20px; border: 1px solid var(--bg-hover); border-radius: 20px;
  background: transparent; color: var(--text-secondary); font-size: 14px; cursor: pointer;
}
.btn-manage:hover { border-color: var(--primary); color: var(--primary); }

.song-table { display: flex; flex-direction: column; }
.table-header {
  display: grid; grid-template-columns: 36px 56px 1fr 200px 90px;
  padding: 8px 0 12px; border-bottom: 1px solid var(--bg-hover);
  color: var(--text-tertiary); font-size: 12px;
}
.th-check { text-align: center; }
.th-index { text-align: center; }
.th-actions { text-align: center; }

.table-row {
  display: grid; grid-template-columns: 36px 56px 1fr 200px 90px;
  align-items: center; padding: 8px 0; border-radius: 8px; transition: .12s;
}
.table-row:hover { background: var(--bg-hover); }
.table-row:nth-child(odd) { background: var(--bg-elevated); }
.table-row.playing { background: rgba(49,194,124,.08); }
.table-row.selected { background: rgba(49,194,124,.06); }
.td-check { text-align: center; }

.td-index { text-align: center; font-size: 14px; color: var(--text-tertiary); }
.playing-eq { display: inline-flex; align-items: flex-end; gap: 2px; height: 12px; color: var(--primary); }
.playing-eq .eq-bar { background: var(--primary); border-radius: 1px; }
.playing-eq .eq-bar:nth-child(1) { height: 7px; }
.playing-eq .eq-bar:nth-child(2) { height: 12px; }
.playing-eq .eq-bar:nth-child(3) { height: 5px; }
.td-cover { display: flex; align-items: center; justify-content: center; }
.cover-img {
  width: 44px; height: 44px; border-radius: 6px; cursor: pointer; position: relative;
  background: var(--bg-elevated); display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: var(--text-tertiary); flex-shrink: 0;
  background-size: cover; background-position: center;
}
.cover-hover {
  position: absolute; inset: 0; border-radius: 6px;
  background: rgba(0,0,0,.55); display: flex; align-items: center; justify-content: center;
  font-size: 18px; color: var(--primary); opacity: 0; transition: .15s;
}
.cover-img:hover .cover-hover { opacity: 1; }
.td-info { display: flex; flex-direction: column; gap: 3px; min-width: 0; cursor: pointer; }
.td-name { font-size: 14px; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.td-name.active { color: var(--primary); }
.td-artist { font-size: 12px; color: var(--text-secondary); }
.td-album { font-size: 13px; color: var(--text-tertiary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer; }
.td-time { font-size: 13px; color: var(--text-tertiary); }
.td-actions { display: flex; justify-content: center; gap: 2px; }
.action-btn {
  background: none; border: none; color: var(--text-secondary); font-size: 15px;
  cursor: pointer; padding: 4px 6px; border-radius: 4px; opacity: 0; transition: .15s;
}
.table-row:hover .action-btn { opacity: 1; }
.fav-btn.faved { color: #f0c040; opacity: 1; }
.fav-btn:hover { color: #f0c040; background: rgba(240,192,64,.08); }
.add-btn:hover { color: var(--primary); background: rgba(49,194,124,.08); }

.empty { text-align: center; padding: 80px 0; color: var(--text-tertiary); }
.hint { font-size: 13px; margin-top: 8px; }

.batch-bar {
  position: fixed; bottom: 80px; left: 0; right: 0; z-index: 50;
  display: flex; justify-content: center; padding: 12px;
  background: var(--bg-elevated); border-top: 1px solid var(--bg-hover);
}
.batch-btn {
  padding: 10px 36px; border-radius: 22px;
  border: 1px solid var(--bg-hover); background: transparent;
  color: #e04040; font-size: 14px; cursor: pointer;
}
.batch-btn:hover { border-color: #e04040; }
.batch-btn:disabled { opacity: .4; }
</style>