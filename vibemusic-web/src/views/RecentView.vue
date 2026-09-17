<script setup>
import { ref, computed, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { getPlayHistory, removePlayHistoryBatch } from '@/api/song'
import { useFavoriteStore } from '@/stores/favorite'
import { usePlayerStore } from '@/stores/player'

const favStore = useFavoriteStore()
const player = usePlayerStore()
const recentSongs = ref([])
const currentPlayId = ref(null)
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)
const loadError = ref(false)

// 批量管理
const manageMode = ref(false)
const selectedIds = ref(new Set())

favStore.fetchFavIds()

function formatTime(dt) {
  if (!dt) return ''
  const d = new Date(dt)
  const now = new Date()
  const diff = now - d
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString().substring(0, 5)
}

function toggleFav(song) {
  favStore.toggleFav({ sourceId: song.sourceId, name: song.songName, artist: song.artist, coverUrl: song.coverUrl })
}

function openPlaylistPopup(item) { playlistTargetSong.value = item; showPlaylistPopup.value = true }

function play(item) {
  if (manageMode.value) return
  currentPlayId.value = item.sourceId
  player.playSongFromApi(item.sourceId, item.songName, item.artist, item.coverUrl || '')
}

function toggleManage() {
  manageMode.value = !manageMode.value
  selectedIds.value = new Set()
}

function toggleSelect(sourceId, idx) {
  const key = sourceId + '_' + idx
  const s = new Set(selectedIds.value)
  s.has(key) ? s.delete(key) : s.add(key)
  selectedIds.value = s
}

const allSelected = computed(() => {
  return recentSongs.value.length > 0 && selectedIds.value.size === recentSongs.value.length
})

function toggleSelectAll() {
  if (allSelected.value) {
    selectedIds.value = new Set()
  } else {
    const s = new Set()
    recentSongs.value.forEach((item, idx) => s.add(item.sourceId + '_' + idx))
    selectedIds.value = s
  }
}

async function doBatchClear() {
  if (!selectedIds.value.size) return
  // 从 key 中提取 sourceId（去重）
  const ids = [...new Set([...selectedIds.value].map(k => k.split('_')[0]))]
  try {
    await removePlayHistoryBatch(ids)
    recentSongs.value = recentSongs.value.filter((item, idx) => !selectedIds.value.has(item.sourceId + '_' + idx))
    selectedIds.value = new Set()
    manageMode.value = false
    window.toast?.('已清除', 'success')
  } catch { window.toast?.('操作失败', 'error') }
}

function loadRecent() {
  loadError.value = false
  getPlayHistory().then(res => {
    recentSongs.value = res.data || []
  }).catch(() => { loadError.value = true })
}

onMounted(() => {
  loadRecent()
})
</script>

<template>
  <TopBar />
  <div class="recent-page">
    <div class="page-header">
      <h2 class="page-title">最近播放</h2>
      <button class="btn-manage" @click="toggleManage">{{ manageMode ? '完成' : '管理' }}</button>
    </div>
    <p class="subtitle">{{ recentSongs.length }} 首歌曲 · 最近 500 条</p>

    <div v-if="recentSongs.length > 0" class="song-table">
      <div class="table-header">
        <span v-if="manageMode" class="th-check" @click="toggleSelectAll" style="cursor:pointer">
          <svg v-if="allSelected" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#31c27c" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>
          <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="12" cy="12" r="10"/></svg>
        </span>
        <span class="th-index">#</span>
        <span class="th-cover"></span>
        <span class="th-title">歌名</span>
        <span class="th-time">播放时间</span>
        <span class="th-actions"></span>
      </div>
      <div
        v-for="(item, idx) in recentSongs" :key="item.sourceId + '_' + idx"
        class="table-row"
        :class="{ playing: currentPlayId === item.sourceId, selected: manageMode && selectedIds.has(item.sourceId + '_' + idx) }"
        @click="manageMode ? toggleSelect(item.sourceId, idx) : play(item)"
      >
        <span v-if="manageMode" class="td-check">
          <svg v-if="selectedIds.has(item.sourceId + '_' + idx)" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#31c27c" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>
          <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="12" cy="12" r="10"/></svg>
        </span>
        <span class="td-index">
          <span v-if="!manageMode && currentPlayId === item.sourceId && player.isPlaying" class="playing-eq"><span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span></span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="td-cover">
          <div class="cover-img" :style="item.coverUrl ? { backgroundImage: 'url(' + item.coverUrl + '?param=100y100)' } : {}" @click.stop="play(item)"><SvgIcon v-if="!item.coverUrl" name="music" :size="16" /><SvgIcon name="play" class="cover-hover" :size="18" /></div>
        </div>
        <div class="td-info" @click.stop="play(item)">
          <span class="td-name" :class="{ active: currentPlayId === item.sourceId }">{{ item.songName }}</span>
          <span class="td-artist">{{ item.artist || '-' }}</span>
        </div>
        <span class="td-time">{{ formatTime(item.playedAt) }}</span>
        <div v-if="!manageMode" class="td-actions" @click.stop>
          <button
            class="action-btn fav-btn" :class="{ faved: favStore.isFav(item.sourceId) }"
            @click.stop="toggleFav(item)" :title="favStore.isFav(item.sourceId) ? '取消收藏' : '收藏'"
          ><SvgIcon :name="favStore.isFav(item.sourceId) ? 'star-fill' : 'star'" :size="16" /></button>
          <button class="action-btn add-btn" @click.stop="openPlaylistPopup(item)" title="加入歌单"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>
        </div>
      </div>
    </div>

    <div v-if="manageMode" class="batch-bar">
      <button v-if="!allSelected" class="batch-select-all" @click="toggleSelectAll">全选</button>
      <button v-if="selectedIds.size" class="batch-btn" @click="doBatchClear">清除 ({{ selectedIds.size }})</button>
    </div>

    <div v-else class="empty">
      <template v-if="loadError">
        <p>加载失败，请检查网络后重试</p>
        <p class="hint"><button class="retry-btn" @click="loadRecent">重试</button></p>
      </template>
      <template v-else>
        <p>暂无播放记录</p>
        <p class="hint">去主页听听音乐吧</p>
      </template>
    </div>
  </div>

  <PlaylistPopup v-if="showPlaylistPopup" :song="playlistTargetSong" @close="showPlaylistPopup = false" @done="showPlaylistPopup = false" />
</template>

<style scoped>
.recent-page { padding: 0 32px 80px; }
.page-header { display: flex; align-items: flex-end; justify-content: space-between; padding-top: 32px; margin-bottom: 28px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--text-primary); }
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
.add-btn:hover, .fav-btn:hover { color: var(--primary); background: rgba(49,194,124,.08); }

.empty { text-align: center; padding: 80px 0; color: var(--text-tertiary); }
.hint { font-size: 13px; margin-top: 8px; }
.retry-btn {
  margin-top: 4px; padding: 6px 24px; border-radius: 16px;
  border: 1px solid #31c27c; background: transparent;
  color: #31c27c; font-size: 13px; cursor: pointer;
}
.retry-btn:hover { background: rgba(49,194,124,.1); }

.batch-bar {
  position: fixed; bottom: 80px; left: 0; right: 0; z-index: 50;
  display: flex; justify-content: center; gap: 12px; padding: 12px;
  background: var(--bg-elevated); border-top: 1px solid var(--bg-hover);
}
.batch-select-all {
  padding: 10px 24px; border-radius: 22px;
  border: 1px solid var(--primary); background: transparent;
  color: var(--primary); font-size: 14px; cursor: pointer;
}
.batch-select-all:hover { background: rgba(49,194,124,.06); }
.batch-btn {
  padding: 10px 36px; border-radius: 22px;
  border: 1px solid var(--bg-hover); background: transparent;
  color: #e04040; font-size: 14px; cursor: pointer;
}
.batch-btn:hover { border-color: #e04040; }
.batch-btn:disabled { opacity: .4; }
</style>