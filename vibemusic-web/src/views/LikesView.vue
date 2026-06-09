<script setup>
import { ref, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { getFavorites, playSong as apiPlaySong, toggleFavorite, getFavoriteIds } from '@/api/song'

const favorites = ref([])
const currentPlayId = ref(null)
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)
const favIds = ref(new Set())

const audio = window.vibeAudio || new Audio()
window.vibeAudio = audio

getFavoriteIds().then(res => { if (res.data) favIds.value = new Set(res.data) }).catch(() => {})

function toggleFav(fav) {
  const isFav = favIds.value.has(fav.sourceId)
  if (isFav) favIds.value.delete(fav.sourceId); else favIds.value.add(fav.sourceId)
  toggleFavorite(fav.sourceId, fav.songName, fav.artist, fav.coverUrl || '').then(res => {
    if (res.data === true) favIds.value.add(fav.sourceId)
    else favIds.value.delete(fav.sourceId)
  }).catch(err => {
    console.error('收藏失败:', err)
    if (isFav) favIds.value.add(fav.sourceId); else favIds.value.delete(fav.sourceId)
  })
}

function openPlaylistPopup(fav) { playlistTargetSong.value = fav; showPlaylistPopup.value = true }

function play(fav) {
  currentPlayId.value = fav.sourceId
  apiPlaySong(fav.sourceId, fav.songName, fav.artist, fav.coverUrl || '').then(res => {
    const url = res.data?.url
    if (!url) return
    if (window.vibeAudioSetSrc) window.vibeAudioSetSrc(url, fav.sourceId, fav.songName, fav.artist, fav.coverUrl || '')
    else { audio.src = url; audio.play().catch(() => {}) }
    window.dispatchEvent(new CustomEvent('song-change', {
      detail: { title: fav.songName, artist: fav.artist, sourceId: fav.sourceId, coverUrl: fav.coverUrl || '', duration: 0 }
    }))
  }).catch(() => {})
}

onMounted(() => {
  getFavorites().then(res => { favorites.value = res.data || [] }).catch(() => {})
})
</script>

<template>
  <TopBar />
  <div class="likes-page">
    <h2 class="page-title">⭐ 我的收藏</h2>
    <p class="subtitle">{{ favorites.length }} 首歌曲</p>

    <div v-if="favorites.length > 0" class="song-table">
      <div class="table-header">
        <span class="th-index">#</span>
        <span class="th-cover"></span>
        <span class="th-title">歌名</span>
        <span class="th-time">收藏时间</span>
        <span class="th-actions"></span>
      </div>
      <div
        v-for="(fav, idx) in favorites" :key="fav.sourceId"
        class="table-row"
        :class="{ playing: currentPlayId === fav.sourceId }"
      >
        <span class="td-index">
          <span v-if="currentPlayId === fav.sourceId" class="playing-eq">▮▮</span>
          <span v-else>{{ idx + 1 }}</span>
        </span>
        <div class="td-cover">
          <div class="cover-img" :style="fav.coverUrl ? { backgroundImage: 'url(' + fav.coverUrl + '?param=100y100)' } : {}" @click="play(fav)"><span v-if="!fav.coverUrl">♪</span><div class="cover-hover">▶</div></div>
        </div>
        <div class="td-info" @click="play(fav)">
          <span class="td-name" :class="{ active: currentPlayId === fav.sourceId }">{{ fav.songName }}</span>
          <span class="td-artist">{{ fav.artist || '-' }}</span>
        </div>
        <span class="td-time">{{ fav.createdAt ? new Date(fav.createdAt).toLocaleDateString() : '' }}</span>
        <div class="td-actions">
          <button class="action-btn fav-btn" :class="{ faved: favIds.has(fav.sourceId) }" @click.stop="toggleFav(fav)" :title="favIds.has(fav.sourceId) ? '取消收藏' : '收藏'">{{ favIds.has(fav.sourceId) ? '⭐' : '☆' }}</button>
          <button class="action-btn add-btn" @click.stop="openPlaylistPopup(fav)" title="加入歌单">➕</button>
        </div>
      </div>
    </div>

    <div v-else class="empty">
      <p>还没有收藏歌曲</p>
      <p class="hint">去主页搜索喜欢的音乐吧</p>
    </div>
  </div>

  <PlaylistPopup v-if="showPlaylistPopup" :song="playlistTargetSong" @close="showPlaylistPopup = false" @done="showPlaylistPopup = false" />
</template>

<style scoped>
.likes-page { padding: 24px 32px; }
.page-title { font-size: 22px; font-weight: 700; color: #333; margin-bottom: 4px; }
.subtitle { font-size: 13px; color: #999; margin-bottom: 20px; }

.song-table { display: flex; flex-direction: column; }
.table-header {
  display: grid; grid-template-columns: 36px 56px 2fr 100px 60px;
  padding: 8px 0 12px; border-bottom: 1px solid #ddd;
  color: #999; font-size: 12px;
}
.th-index { text-align: center; }
.th-actions { text-align: center; }

.table-row {
  display: grid; grid-template-columns: 36px 56px 2fr 100px 60px;
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
.fav-btn:hover { color: #f0c040; background: rgba(240,192,64,.08); }
.add-btn:hover { color: #31c27c; background: rgba(49,194,124,.08); }

.empty { text-align: center; padding: 80px 0; color: #999; }
.hint { font-size: 13px; margin-top: 8px; }
</style>