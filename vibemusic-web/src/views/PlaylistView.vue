<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import { getPlaylists, getPlaylistSongs, removeFromPlaylist, exportPlaylist } from '@/api/song'
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

const exporting = ref(false)

async function handleExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const res = await exportPlaylist(playlistId.value)
    // request 拦截器已将 axios response.data 解包，res = { code: 200, data: { name, songs, ... } }
    const blob = new Blob([JSON.stringify(res.data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${info.value?.name || `歌单_${playlistId.value}`}.json`
    a.click()
    URL.revokeObjectURL(url)
    window.toast?.('已导出', 'success')
  } catch (e) {
    console.error('[export]', e)
    window.toast?.('导出失败，请重试', 'error')
  } finally {
    exporting.value = false
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
        <div class="sk-cover skeleton"></div>
        <div class="sk-info"><span class="skeleton"></span><span class="skeleton"></span></div>
      </div>
      <div v-for="i in 5" :key="i" class="sk-row"><span class="skeleton"></span><span class="skeleton"></span><span class="skeleton"></span><span class="skeleton"></span></div>
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
          <SvgIcon v-else name="music" class="cover-fallback" :size="48" />
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
            <button class="btn-export" @click="handleExport" :disabled="!songs.length || exporting">
              {{ exporting ? '导出中...' : '导出 JSON' }}
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
            <span v-if="player.currentSong?.id === song.sourceId && player.isPlaying" class="eq"><span class="eq-bar"></span><span class="eq-bar"></span><span class="eq-bar"></span></span>
            <span v-else>{{ idx + 1 }}</span>
          </span>
          <div class="c-cover" @click="play(song)">
            <img v-if="song.coverUrl" :src="song.coverUrl + '?param=60y60'" class="cover-img" />
            <SvgIcon v-else name="music" class="cover-icon" :size="14" />
            <SvgIcon name="play" class="play-hover" :size="14" />
          </div>
          <div class="c-info" @click="play(song)">
            <span class="c-name" :class="{ active: player.currentSong?.id === song.sourceId }">{{ song.name }}</span>
            <span class="c-artist">{{ song.artist || '-' }}</span>
          </div>
          <span class="c-time">{{ formatDuration(song.duration) }}</span>
          <div class="c-actions">
            <button :class="{ faved: favStore.isFav(song.sourceId) }" @click.stop="toggleFav(song)" title="收藏">
              <SvgIcon :name="favStore.isFav(song.sourceId) ? 'star-fill' : 'star'" :size="14" />
            </button>
            <button @click.stop="openPlaylistPopup(song)" title="加入其他歌单">+</button>
            <button class="del" @click.stop="removeSong(song)" title="移除"><SvgIcon name="close" /></button>
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

/* 导航（暗色） */
.nav-bar {
  display: flex; align-items: center; gap: 12px;
  padding: 16px 32px; background: rgba(24,24,24,0.92);
  backdrop-filter: blur(12px); position: sticky; top: 0; z-index: 20;
  border-bottom: 1px solid var(--bg-hover);
}
.nav-back {
  width: 32px; height: 32px; border-radius: 8px; border: 1px solid var(--bg-hover);
  background: transparent; color: var(--text-secondary); display: flex; align-items: center; justify-content: center;
  cursor: pointer; flex-shrink: 0; transition: background .15s, color .15s;
}
.nav-back:hover { background: var(--bg-hover); color: var(--text-primary); }
.nav-title { font-size: 16px; font-weight: 600; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; }

/* 骨架屏（暗色渐变） */
.sk-hero { display: flex; gap: 32px; padding: 40px 32px; }
.sk-cover {
  width: 200px; height: 200px; border-radius: 12px; flex-shrink: 0;
  background: linear-gradient(90deg, var(--bg-card) 25%, var(--bg-hover) 50%, var(--bg-card) 75%);
  background-size: 200% 100%;
}
.sk-info { flex: 1; display: flex; flex-direction: column; gap: 12px; padding-top: 8px; }
.sk-info span {
  height: 16px; border-radius: 6px;
  background: linear-gradient(90deg, var(--bg-card) 25%, var(--bg-hover) 50%, var(--bg-card) 75%);
  background-size: 200% 100%;
}
.sk-info span:nth-child(1) { width: 60%; }
.sk-info span:nth-child(2) { width: 40%; }
.sk-row { display: flex; gap: 12px; padding: 10px 40px 10px 48px; }
.sk-row span {
  height: 14px; border-radius: 4px;
  background: linear-gradient(90deg, var(--bg-elevated) 25%, var(--bg-hover) 50%, var(--bg-elevated) 75%);
  background-size: 200% 100%;
}
.sk-row span:nth-child(1) { width: 28px; } .sk-row span:nth-child(2) { flex: 1; } .sk-row span:nth-child(3) { flex: 1; } .sk-row span:nth-child(4) { width: 50px; }

/* Hero（暗色） */
.hero {
  display: flex; gap: 40px; padding: 40px;
  background: linear-gradient(180deg, rgba(49,194,124,0.06) 0%, transparent 100%);
  border-bottom: 1px solid var(--bg-hover);
}
.hero-cover {
  width: 200px; height: 200px; border-radius: 12px; overflow: hidden; flex-shrink: 0;
  background: var(--bg-elevated); box-shadow: var(--shadow-2);
}
.hero-cover img { width: 100%; height: 100%; object-fit: cover; }
.cover-fallback { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; font-size: 48px; color: var(--text-tertiary); }
.hero-info { flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 12px; min-width: 0; }
.hero-name { font-size: 26px; font-weight: 700; color: var(--text-primary); line-height: 1.3; word-break: break-word; }
.hero-desc { font-size: 13px; color: var(--text-secondary); line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.hero-stats { font-size: 13px; color: var(--text-tertiary); }
.hero-actions { display: flex; gap: 12px; margin-top: 4px; }
.btn-play {
  display: flex; align-items: center; gap: 8px; padding: 10px 28px;
  background: var(--primary); color: #fff; border: none; border-radius: 24px;
  font-size: 15px; font-weight: 600; cursor: pointer; transition: background .15s, transform .15s, opacity .15s;
}
.btn-play:hover { background: #28a86b; transform: scale(1.02); }
.btn-play:disabled { opacity: .4; cursor: not-allowed; transform: none; }
.btn-export {
  display: flex; align-items: center; gap: 8px; padding: 10px 20px;
  background: transparent; color: var(--text-secondary); border: 1px solid var(--bg-hover); border-radius: 24px;
  font-size: 14px; cursor: pointer; transition: border-color .15s, color .15s;
}
.btn-export:hover { border-color: var(--primary); color: var(--primary); }
.btn-export:disabled { opacity: .4; cursor: not-allowed; }

/* 歌曲列表（暗色） */
.song-list { padding: 0 40px; }
.list-header {
  display: grid; grid-template-columns: 36px 44px 1fr 60px 64px; gap: 12px; align-items: center;
  padding: 10px 0; border-bottom: 1px solid var(--bg-hover); color: var(--text-tertiary); font-size: 12px;
  position: sticky; top: 62px; z-index: 10; background: var(--bg-base);
}
.h-idx { text-align: center; } .h-actions { text-align: center; }
.song-row {
  display: grid; grid-template-columns: 36px 44px 1fr 60px 64px; gap: 12px; align-items: center;
  padding: 8px 0; border-bottom: 1px solid var(--bg-hover); transition: background .12s;
}
.song-row:hover { background: var(--bg-hover); }
.song-row.playing { background: rgba(49,194,124,0.06); }
.song-row.playing .c-name { color: var(--primary); }
.c-idx { text-align: center; color: var(--text-tertiary); font-size: 13px; }
.eq { display: inline-flex; align-items: flex-end; gap: 2px; height: 12px; color: var(--primary); }
.eq .eq-bar { background: var(--primary); border-radius: 1px; }
.eq .eq-bar:nth-child(1) { height: 7px; }
.eq .eq-bar:nth-child(2) { height: 12px; }
.eq .eq-bar:nth-child(3) { height: 5px; }
.c-cover { position: relative; width: 36px; height: 36px; cursor: pointer; border-radius: 4px; overflow: hidden; background: var(--bg-elevated); }
.cover-img { width: 100%; height: 100%; object-fit: cover; }
.cover-icon { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; color: var(--text-tertiary); font-size: 14px; }
.play-hover { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.4); color: var(--primary); font-size: 14px; opacity: 0; transition: .12s; }
.c-cover:hover .play-hover { opacity: 1; }
.c-info { cursor: pointer; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.c-name { font-size: 14px; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.c-name.active { color: var(--primary); }
.c-artist { font-size: 12px; color: var(--text-tertiary); }
.c-time { font-size: 12px; color: var(--text-tertiary); }
.c-actions { display: flex; gap: 6px; justify-content: center; }
.c-actions button {
  width: 28px; height: 28px; border-radius: 6px; border: 1px solid transparent;
  background: transparent; color: var(--text-tertiary); cursor: pointer; font-size: 14px;
  display: flex; align-items: center; justify-content: center; transition: border-color .12s, color .12s;
}
.c-actions button:hover { border-color: var(--bg-hover); color: var(--text-secondary); }
.c-actions button.faved { color: #ec4141; border-color: rgba(236,65,65,0.15); }
.c-actions button.del:hover { color: #e84c3d; border-color: rgba(232,76,61,0.2); }

.empty-state { text-align: center; padding: 120px 0; color: var(--text-tertiary); font-size: 15px; }
.empty-list { text-align: center; padding: 40px 0; color: var(--text-tertiary); font-size: 14px; }
</style>
