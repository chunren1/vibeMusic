<script setup>
import { ref, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { getPlayHistory } from '@/api/song'
import { useFavoriteStore } from '@/stores/favorite'
import { usePlayerStore } from '@/stores/player'

const favStore = useFavoriteStore()
const player = usePlayerStore()
const recentSongs = ref([])
const currentPlayId = ref(null)
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)

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

function doBatchClear() {
  const keys = new Set(selectedIds.value)
  recentSongs.value = recentSongs.value.filter((item, idx) => !keys.has(item.sourceId + '_' + idx))
  selectedIds.value = new Set()
  manageMode.value = false
}

onMounted(() => {
  getPlayHistory().then(res => {
    recentSongs.value = res.data || []
  }).catch(() => {})
})
</script>

<template>
  <TopBar />
  <div class="recent-page">
    <div class="page-header">
      <h2 class="page-title">🕐 最近播放</h2>
      <button class="btn-manage" @click="toggleManage">{{ manageMode ? '完成' : '管理' }}</button>
    </div>
    <p class="subtitle">{{ recentSongs.length }} 首歌曲</p>

    <div v-if="recentSongs.length > 0" class="song-table">
      <div class="table-header">
        <span v-if="manageMode" class="th-check"></span>
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
          <span v-if="!manageMode && currentPlayId === item.sourceId" class="playing-eq">▮▮</span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="td-cover">
          <div class="cover-img" :style="item.coverUrl ? { backgroundImage: 'url(' + item.coverUrl + '?param=100y100)' } : {}" @click.stop="play(item)"><span v-if="!item.coverUrl">♪</span><div class="cover-hover">▶</div></div>
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
          ><svg viewBox="0 0 24 24" width="16" height="16" :fill="favStore.isFav(item.sourceId) ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg></button>
          <button class="action-btn add-btn" @click.stop="openPlaylistPopup(item)" title="加入歌单"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>
        </div>
      </div>
    </div>

    <div v-if="manageMode && selectedIds.size" class="batch-bar">
      <button class="batch-btn" @click="doBatchClear">清除 ({{ selectedIds.size }})</button>
    </div>

    <div v-else class="empty">
      <p>暂无播放记录</p>
      <p class="hint">去主页听听音乐吧</p>
    </div>
  </div>

  <PlaylistPopup v-if="showPlaylistPopup" :song="playlistTargetSong" @close="showPlaylistPopup = false" @done="showPlaylistPopup = false" />
</template>

<style scoped>
.recent-page { padding: 24px 32px; padding-bottom: 80px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.page-title { font-size: 22px; font-weight: 700; color: #333; }
.subtitle { font-size: 13px; color: #999; margin-bottom: 20px; }
.btn-manage {
  padding: 8px 18px; border: 1px solid #ccc; border-radius: 18px;
  background: transparent; color: #666; font-size: 13px; cursor: pointer;
}
.btn-manage:hover { border-color: #31c27c; color: #31c27c; }

.song-table { display: flex; flex-direction: column; }
.table-header {
  display: grid; grid-template-columns: 36px 56px 2fr 120px 80px;
  padding: 8px 0 12px; border-bottom: 1px solid #ddd;
  color: #999; font-size: 12px;
}
.th-check { text-align: center; }
.th-index { text-align: center; }
.th-actions { text-align: center; }

.table-row {
  display: grid; grid-template-columns: 36px 56px 2fr 120px 80px;
  align-items: center; padding: 8px 0; border-radius: 8px; transition: .12s;
}
.table-row:hover { background: #f0f0f0; }
.table-row:nth-child(odd) { background: #f9f9f9; }
.table-row.playing { background: rgba(49,194,124,.08); }
.table-row.selected { background: rgba(49,194,124,.06); }
.td-check { text-align: center; }

.td-index { text-align: center; font-size: 14px; color: #999; }
.playing-eq { color: #31c27c; font-size: 12px; letter-spacing: -2px; }
.td-cover { display: flex; align-items: center; justify-content: center; }
.cover-img {
  width: 44px; height: 44px; border-radius: 6px; cursor: pointer; position: relative;
  background: #e0e0e0; display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: #999; flex-shrink: 0;
  background-size: cover; background-position: center;
}
.cover-hover {
  position: absolute; inset: 0; border-radius: 6px;
  background: rgba(0,0,0,.55); display: flex; align-items: center; justify-content: center;
  font-size: 18px; color: #31c27c; opacity: 0; transition: .15s;
}
.cover-img:hover .cover-hover { opacity: 1; }
.td-info { display: flex; flex-direction: column; gap: 3px; min-width: 0; cursor: pointer; }
.td-name { font-size: 14px; color: #1a1a1a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.td-name.active { color: #31c27c; }
.td-artist { font-size: 12px; color: #777; }
.td-time { font-size: 13px; color: #888; }
.td-actions { display: flex; justify-content: center; gap: 2px; }
.action-btn {
  background: none; border: none; color: #555; font-size: 15px;
  cursor: pointer; padding: 4px 6px; border-radius: 4px; opacity: 0; transition: .15s;
}
.table-row:hover .action-btn { opacity: 1; }
.fav-btn.faved { color: #f0c040; opacity: 1; }
.add-btn:hover, .fav-btn:hover { color: #31c27c; background: rgba(49,194,124,.08); }

.empty { text-align: center; padding: 80px 0; color: #999; }
.hint { font-size: 13px; margin-top: 8px; }

.batch-bar {
  position: fixed; bottom: 80px; left: 0; right: 0; z-index: 50;
  display: flex; justify-content: center; padding: 12px;
  background: #fff; border-top: 1px solid #eee;
}
.batch-btn {
  padding: 10px 36px; border-radius: 22px;
  border: 1px solid #e0e0e0; background: transparent;
  color: #e04040; font-size: 14px; cursor: pointer;
}
.batch-btn:hover { border-color: #e04040; }
.batch-btn:disabled { opacity: .4; }
</style>