<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { getPlaylists, getPlaylistSongs, removeFromPlaylist } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const route = useRoute()
const router = useRouter()
const favStore = useFavoriteStore()
const player = usePlayerStore()
const playlistId = ref(Number(route.params.id) || 0)
const songs = ref([])
const info = ref(null)
const loading = ref(true)
const loadError = ref(false)
const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)

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
    if (pl) info.value = pl
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

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/playlists')
}

function play(song) {
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '')
}

function playAll() {
  if (!songs.value.length) return
  player.playPlaylist(songs.value)
}

function toggleFav(song) { favStore.toggleFav(song) }

function openPlaylistPopup(song) {
  playlistTargetSong.value = song
  showPlaylistPopup.value = true
}

async function removeSong(song) {
  try {
    await removeFromPlaylist(playlistId.value, song.sourceId)
    songs.value = songs.value.filter(s => s.sourceId !== song.sourceId)
    window.toast?.('已从歌单移除', 'success')
  } catch (e) {
    window.toast?.('移除失败', 'error')
  }
}

favStore.fetchFavIds()
onMounted(() => loadSongs())
</script>

<template>
  <TopBar />
  <div class="detail-page">
    <!-- 顶部导航栏 -->
    <div class="nav-bar">
      <button class="nav-back" @click="goBack" title="返回">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <span class="nav-title">{{ loading ? '加载中...' : (info?.name || '我的歌单') }}</span>
    </div>

    <!-- 骨架屏 -->
    <template v-if="loading">
      <div class="sk-hero">
        <div class="sk-cover"></div>
        <div class="sk-info"><span></span><span></span></div>
      </div>
      <div v-for="i in 5" :key="i" class="sk-row"><span></span><span></span><span></span><span></span></div>
    </template>

    <!-- 错误 -->
    <div v-else-if="loadError" class="empty-state">
      <p>加载失败，请检查是否登录或重试</p>
    </div>

    <!-- 内容 -->
    <template v-else-if="info">
      <div class="hero">
        <div class="hero-cover">
          <img v-if="info.coverUrl" :src="info.coverUrl + '?param=300y300'" alt="" />
          <span v-else class="cover-fallback">♪</span>
        </div>
        <div class="hero-info">
          <h1 class="hero-name">{{ info.name }}</h1>
          <p class="hero-desc" v-if="info.description">{{ info.description }}</p>
          <div class="hero-stats">{{ songs.length }} 首歌曲</div>
          <div class="hero-actions">
            <button class="btn-play" @click="playAll" :disabled="!songs.length">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
              播放全部
            </button>
          </div>
        </div>
      </div>

      <!-- 歌曲列表 -->
      <div class="song-list">
        <div class="list-header">
          <span class="h-idx">#</span>
          <span class="h-cover"></span>
          <span class="h-title">歌曲</span>
          <span class="h-time">时长</span>
          <span class="h-actions"></span>
        </div>
        <div
          v-for="(song, idx) in songs" :key="song.sourceId"
          class="song-row"
          :class="{ playing: player.currentSong?.id === song.sourceId }"
          @dblclick="play(song)"
        >
          <span class="c-idx">
            <span v-if="player.currentSong?.id === song.sourceId && player.isPlaying" class="eq">▮▮</span>
            <span v-else>{{ idx + 1 }}</span>
          </span>
          <div class="c-cover" @click="play(song)">
            <img v-if="song.coverUrl" :src="song.coverUrl + '?param=60y60'" class="cover-img" />
            <span v-else class="cover-icon">♪</span>
            <span class="play-hover">▶</span>
          </div>
          <div class="c-info" @click="play(song)">
            <span class="c-name" :class="{ active: player.currentSong?.id === song.sourceId }">{{ song.name }}</span>
            <span class="c-artist">{{ song.artist || '-' }}</span>
          </div>
          <span class="c-time">{{ formatDuration(song.duration) }}</span>
          <div class="c-actions">
            <button :class="{ faved: favStore.isFav(song.sourceId) }" @click.stop="toggleFav(song)" title="收藏">★</button>
            <button @click.stop="openPlaylistPopup(song)" title="加入其他歌单">+</button>
            <button class="del" @click.stop="removeSong(song)" title="移除">✕</button>
          </div>
        </div>
        <div v-if="!songs.length" class="empty-list">歌单里还没有歌曲</div>
      </div>
    </template>

    <div v-else class="empty-state"><p>暂无数据</p></div>
  </div>

  <PlaylistPopup
    v-if="showPlaylistPopup"
    :song="playlistTargetSong"
    :exclude-playlist-id="playlistId"
    @close="showPlaylistPopup = false"
    @done="showPlaylistPopup = false"
  />
</template>

<style scoped>
.detail-page { width: 100%; min-height: 100%; padding-bottom: 80px; }

/* 导航 */
.nav-bar {
  display: flex; align-items: center; gap: 12px;
  padding: 16px 32px; background: rgba(255,255,255,0.92);
  backdrop-filter: blur(12px); position: sticky; top: 0; z-index: 20;
  border-bottom: 1px solid #eee;
}
.nav-back {
  width: 32px; height: 32px; border-radius: 8px; border: 1px solid #ddd;
  background: transparent; color: #777; display: flex; align-items: center; justify-content: center;
  cursor: pointer; flex-shrink: 0; transition: all .15s;
}
.nav-back:hover { background: #f0f0f0; color: #333; }
.nav-title { font-size: 16px; font-weight: 600; color: #1a1a1a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; }

/* 骨架屏 */
.sk-hero { display: flex; gap: 32px; padding: 40px 32px; }
.sk-cover { width: 200px; height: 200px; border-radius: 12px; background: #e0e0e0; flex-shrink: 0; animation: shim 1s infinite alternate; }
.sk-info { flex: 1; display: flex; flex-direction: column; gap: 12px; padding-top: 8px; }
.sk-info span { height: 16px; background: #e0e0e0; border-radius: 6px; animation: shim 1s infinite alternate; }
.sk-info span:nth-child(1) { width: 60%; }
.sk-info span:nth-child(2) { width: 40%; }
.sk-row { display: flex; gap: 12px; padding: 10px 40px 10px 48px; }
.sk-row span { height: 14px; background: #eee; border-radius: 4px; animation: shim 1s infinite alternate; }
.sk-row span:nth-child(1) { width: 28px; } .sk-row span:nth-child(2) { flex: 1; } .sk-row span:nth-child(3) { flex: 1; } .sk-row span:nth-child(4) { width: 50px; }
@keyframes shim { to { opacity: .4; } }

/* Hero */
.hero {
  display: flex; gap: 40px; padding: 40px;
  background: linear-gradient(180deg, rgba(49,194,124,0.04) 0%, transparent 100%);
  border-bottom: 1px solid #eee;
}
.hero-cover {
  width: 200px; height: 200px; border-radius: 12px; overflow: hidden; flex-shrink: 0;
  background: #e8e8e8; box-shadow: 0 4px 20px rgba(0,0,0,0.08);
}
.hero-cover img { width: 100%; height: 100%; object-fit: cover; }
.cover-fallback { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; font-size: 48px; color: #bbb; }
.hero-info { flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 12px; min-width: 0; }
.hero-name { font-size: 26px; font-weight: 700; color: #1a1a1a; line-height: 1.3; word-break: break-word; }
.hero-desc { font-size: 13px; color: #777; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.hero-stats { font-size: 13px; color: #888; }
.hero-actions { display: flex; gap: 12px; margin-top: 4px; }
.btn-play {
  display: flex; align-items: center; gap: 8px; padding: 10px 28px;
  background: #31c27c; color: #fff; border: none; border-radius: 24px;
  font-size: 15px; font-weight: 600; cursor: pointer; transition: all .15s;
}
.btn-play:hover { background: #28a86b; transform: scale(1.02); }
.btn-play:disabled { opacity: .4; cursor: not-allowed; transform: none; }

/* 歌曲列表 */
.song-list { padding: 0 40px; }
.list-header {
  display: grid; grid-template-columns: 36px 44px 1fr 60px 64px; gap: 12px; align-items: center;
  padding: 10px 0; border-bottom: 1px solid #eee; color: #999; font-size: 12px;
  position: sticky; top: 62px; z-index: 10; background: #f5f5f5;
}
.h-idx { text-align: center; } .h-actions { text-align: center; }
.song-row {
  display: grid; grid-template-columns: 36px 44px 1fr 60px 64px; gap: 12px; align-items: center;
  padding: 8px 0; border-bottom: 1px solid #f0f0f0; transition: background .12s;
}
.song-row:hover { background: #fafafa; }
.song-row.playing { background: rgba(49,194,124,0.06); }
.song-row.playing .c-name { color: #31c27c; }
.c-idx { text-align: center; color: #bbb; font-size: 13px; }
.eq { color: #31c27c; font-weight: bold; animation: pulse .5s infinite alternate; }
@keyframes pulse { to { opacity: .3; } }
.c-cover { position: relative; width: 36px; height: 36px; cursor: pointer; border-radius: 4px; overflow: hidden; background: #e8e8e8; }
.cover-img { width: 100%; height: 100%; object-fit: cover; }
.cover-icon { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; color: #ccc; font-size: 14px; }
.play-hover { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.4); color: #31c27c; font-size: 14px; opacity: 0; transition: .12s; }
.c-cover:hover .play-hover { opacity: 1; }
.c-info { cursor: pointer; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.c-name { font-size: 14px; color: #1a1a1a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.c-name.active { color: #31c27c; }
.c-artist { font-size: 12px; color: #999; }
.c-time { font-size: 12px; color: #bbb; }
.c-actions { display: flex; gap: 6px; justify-content: center; }
.c-actions button {
  width: 28px; height: 28px; border-radius: 6px; border: 1px solid transparent;
  background: transparent; color: #bbb; cursor: pointer; font-size: 14px;
  display: flex; align-items: center; justify-content: center; transition: all .12s;
}
.c-actions button:hover { border-color: #ddd; color: #666; }
.c-actions button.faved { color: #ec4141; border-color: rgba(236,65,65,0.15); }
.c-actions button.del:hover { color: #e84c3d; border-color: rgba(232,76,61,0.2); }

.empty-state { text-align: center; padding: 120px 0; color: #aaa; font-size: 15px; }
.empty-list { text-align: center; padding: 40px 0; color: #aaa; font-size: 14px; }
</style>
