<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { getPlaylists, getPlaylistSongs, removeFromPlaylist } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const route = useRoute()
const favStore = useFavoriteStore()
const player = usePlayerStore()
const playlistId = ref(Number(route.params.id) || 0)
const songs = ref([])
const playlistName = ref('歌单详情')
const currentPlayId = ref(null)
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)
const loading = ref(true)
const loadError = ref(false)

function formatDuration(s) {
  if (!s) return ''
  const m = Math.floor(s / 60)
  return m + ':' + String(s % 60).padStart(2, '0')
}

async function loadSongs() {
  if (!playlistId.value) return
  loading.value = true; loadError.value = false
  try {
    const [plRes, songsRes] = await Promise.all([
      getPlaylists(), getPlaylistSongs(playlistId.value)
    ])
    const pl = (plRes.data || []).find(p => p.id === playlistId.value)
    if (pl) playlistName.value = pl.name
    songs.value = (songsRes.data || []).map(s => ({
      sourceId: s.sourceId,
      name: s.songName,
      artist: s.artist || '',
      coverUrl: s.coverUrl || '',
      duration: s.duration || 0,
    }))
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function play(song) {
  currentPlayId.value = song.sourceId
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '')
}

function playAll() {
  if (songs.value.length === 0) return
  player.playPlaylist(songs.value)
  currentPlayId.value = songs.value[0].sourceId
}

function toggleFav(song) {
  favStore.toggleFav(song)
}

function openPlaylistPopup(song) {
  playlistTargetSong.value = song
  showPlaylistPopup.value = true
}

async function removeSong(song) {
  try {
    await removeFromPlaylist(playlistId.value, song.sourceId)
    songs.value = songs.value.filter(s => s.sourceId !== song.sourceId)
  } catch (e) {
    console.error('移除失败:', e)
  }
}

favStore.fetchFavIds()

onMounted(() => loadSongs())
</script>

<template>
  <TopBar />
  <div class="detail-page">
    <div class="detail-header">
      <h2 class="detail-title">{{ playlistName }}</h2>
      <p class="subtitle">{{ songs.length }} 首歌曲</p>
      <button v-if="songs.length > 0" class="btn-play-all" @click="playAll">▶ 播放全部</button>
    </div>

    <div v-if="loading" class="empty">加载中...</div>
    <div v-else-if="loadError" class="empty">加载失败，请检查是否登录或重试</div>
    <div v-else-if="songs.length === 0" class="empty">歌单里还没有歌曲，去搜索页添加吧~</div>

    <div v-else class="song-table">
      <div class="table-header">
        <span class="th-index">#</span>
        <span class="th-cover"></span>
        <span class="th-title">歌名</span>
        <span class="th-time">时长</span>
        <span class="th-actions"></span>
      </div>
      <div
        v-for="(song, idx) in songs" :key="song.sourceId"
        class="table-row"
        :class="{ playing: currentPlayId === song.sourceId }"
      >
        <span class="td-index">
          <span v-if="currentPlayId === song.sourceId" class="playing-eq">▮▮</span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="td-cover">
          <div
            class="cover-img"
            :style="song.coverUrl ? { backgroundImage: 'url(' + song.coverUrl + '?param=100y100)' } : {}"
            @click="play(song)"
          >
            <span v-if="!song.coverUrl">♪</span>
            <div class="cover-hover">▶</div>
          </div>
        </div>
        <div class="td-info" @click="play(song)">
          <span class="td-name" :class="{ active: currentPlayId === song.sourceId }">{{ song.name }}</span>
          <span class="td-artist">{{ song.artist || '-' }}</span>
        </div>
        <span class="td-time">{{ formatDuration(song.duration) }}</span>
        <div class="td-actions">
          <button
            class="action-btn fav-btn"
            :class="{ faved: favStore.isFav(song.sourceId) }"
            @click.stop="toggleFav(song)"
            :title="favStore.isFav(song.sourceId) ? '取消收藏' : '收藏'"
          ><svg viewBox="0 0 24 24" width="16" height="16" :fill="favStore.isFav(song.sourceId) ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg></button>
          <button
            class="action-btn add-btn"
            @click.stop="openPlaylistPopup(song)"
            title="加入其他歌单"
          ><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg></button>
          <button
            class="action-btn del-btn"
            @click.stop="removeSong(song)"
            title="从歌单移除"
          ><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>
        </div>
      </div>
    </div>

    <PlaylistPopup
      v-if="showPlaylistPopup"
      :song="playlistTargetSong"
      :exclude-playlist-id="playlistId"
      @close="showPlaylistPopup = false"
      @done="showPlaylistPopup = false"
    />
  </div>
</template>

<style scoped>
.detail-page { padding: 24px 32px; }
.detail-header { margin-bottom: 20px; }
.detail-title { font-size: 22px; font-weight: 700; color: #1a1a1a; margin-bottom: 4px; }
.subtitle { font-size: 13px; color: #999; }

.song-table { display: flex; flex-direction: column; }
.table-header {
  display: grid;
  grid-template-columns: 36px 56px 2fr 70px 80px;
  padding: 8px 0 12px; border-bottom: 1px solid #ddd;
  color: #999; font-size: 12px;
}
.th-index { text-align: center; }
.th-actions { text-align: center; }

.table-row {
  display: grid;
  grid-template-columns: 36px 56px 2fr 70px 80px;
  align-items: center; padding: 8px 0; border-radius: 8px; transition: .12s;
}
.table-row:hover { background: #f0f0f0; }
.table-row:nth-child(odd) { background: #f9f9f9; }
.table-row.playing { background: rgba(49,194,124,.08); }

.td-index { text-align: center; font-size: 14px; color: #999; }
.playing-eq { color: #31c27c; font-size: 12px; letter-spacing: -2px; }

.td-cover { display: flex; align-items: center; justify-content: center; }
.cover-img {
  width: 44px; height: 44px; border-radius: 6px; cursor: pointer; position: relative;
  background: #e0e0e0; display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: #999;
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
.td-artist { font-size: 12px; color: #777; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.td-time { font-size: 13px; color: #888; }

.td-actions { display: flex; justify-content: center; gap: 2px; }
.action-btn {
  background: none; border: none; color: #555; font-size: 15px;
  cursor: pointer; padding: 4px 6px; border-radius: 4px; opacity: 0; transition: .15s;
}
.table-row:hover .action-btn { opacity: 1; }
.fav-btn.faved { color: #f0c040; opacity: 1; }
.fav-btn:hover { color: #f0c040; background: rgba(240,192,64,.08); }
.add-btn:hover { color: #31c27c; background: rgba(49,194,124,.08); }
.del-btn:hover { color: #e84c3d; background: rgba(232,76,61,.08); }

.empty { text-align: center; padding: 80px 0; color: #999; }
.hint { font-size: 13px; margin-top: 8px; }
</style>