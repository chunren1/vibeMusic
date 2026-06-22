<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import { importPlaylist } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const favStore = useFavoriteStore()
const authStore = useAuthStore()

const source = computed(() => route.params.source || 'netease')
const playlistId = computed(() => route.params.id)
const info = ref(null)
const songs = ref([])
const loading = ref(true)
const loadError = ref(false)

async function load() {
  loading.value = true; loadError.value = false
  try {
    const res = await request.get('/playlists/detail', {
      params: { source: source.value, id: playlistId.value }
    })
    info.value = res.data
    songs.value = res.data.songs || []
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}

function playAll() {
  if (!songs.value.length) return
  player.playPlaylist(songs.value.map(s => ({
    sourceId: s.id, name: s.name, artist: s.artist || '',
    coverUrl: s.coverUrl || '', duration: s.duration || 0, platform: source.value,
  })))
}

function playSong(song, idx) {
  const full = songs.value.map(s => ({
    sourceId: s.id, name: s.name, artist: s.artist || '',
    coverUrl: s.coverUrl || '', duration: s.duration || 0, platform: source.value,
  }))
  player.clearQueue()
  full.forEach(s => player.addToQueue(s))
  player.playIndex(idx)
}

function fmtSec(s) { if (!s) return ''; const m = Math.floor(s / 60); return m + ':' + String(s % 60).padStart(2, '0') }
function fmtCount(n) { if (!n) return ''; if (n > 100000000) return (n / 100000000).toFixed(1) + '亿'; if (n > 10000) return Math.floor(n / 10000) + '万'; return n }

onMounted(() => { load(); favStore.fetchFavIds() })

// ===== 收藏歌单 =====
const importing = ref(false)
async function handleImport() {
  if (!info.value) return
  if (!authStore.isLoggedIn) { authStore.openLogin(); return }
  if (importing.value) return
  importing.value = true
  try {
    const res = await importPlaylist(source.value, String(playlistId.value))
    const data = res.data || {}
    window.toast?.('success', `歌单「${data.name || info.value.name}」已收藏 (${data.imported || 0}/${data.total || 0}首)`)
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '导入失败，请重试'
    window.toast?.('error', msg)
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="detail-page">
    <!-- 顶部导航 -->
    <div class="nav-bar">
      <button class="nav-back" @click="goBack" title="返回">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <span class="nav-title">{{ loading ? '加载中...' : (info?.name || '歌单') }}</span>
      <span class="nav-source" v-if="info">{{ info.source === 'qq' ? 'QQ音乐' : '网易云' }}</span>
    </div>

    <!-- 骨架屏 -->
    <template v-if="loading">
      <div class="sk-hero">
        <div class="sk-cover"></div>
        <div class="sk-info"><span></span><span></span><span></span></div>
      </div>
      <div v-for="i in 8" :key="i" class="sk-row"><span></span><span></span><span></span><span></span></div>
    </template>

    <!-- 错误 -->
    <div v-else-if="loadError" class="empty-state">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" opacity="0.3"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
      <p>加载失败，歌单不存在或网络错误</p>
    </div>

    <!-- 详情 -->
    <template v-else-if="info">
      <!-- 歌单头部：封面 + 信息 -->
      <div class="hero">
        <div class="hero-cover">
          <img v-if="info.coverUrl" :src="info.coverUrl + '?param=300y300'" alt="" />
          <span v-else class="cover-fallback">♪</span>
          <div class="cover-play-count" v-if="info.playCount">▶ {{ fmtCount(info.playCount) }}</div>
        </div>
        <div class="hero-info">
          <h1 class="hero-name">{{ info.name }}</h1>
          <div class="hero-creator" v-if="info.creator">
            <img v-if="info.creator.avatarUrl || info.creator.avatar" :src="(info.creator.avatarUrl || info.creator.avatar) + '?param=40y40'" class="creator-avatar" />
            <span>{{ info.creator.nickname || info.creator.name }}</span>
            <span class="creator-label">创建</span>
          </div>
          <div class="hero-stats">
            <span>{{ songs.length }} 首歌曲</span>
            <span v-if="info.playCount"> · {{ fmtCount(info.playCount) }} 次播放</span>
          </div>
          <p class="hero-desc" v-if="info.description">{{ info.description }}</p>
          <div class="hero-actions">
            <button class="btn-play" @click="playAll" :disabled="!songs.length">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
              播放全部
            </button>
            <button class="btn-import" @click="handleImport" :disabled="importing">
              {{ importing ? '收藏中...' : '收藏歌单' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 歌曲列表 -->
      <div class="song-list">
        <!-- 表头 -->
        <div class="list-header">
          <span class="h-idx">#</span>
          <span class="h-cover"></span>
          <span class="h-title">歌曲</span>
          <span class="h-album">专辑</span>
          <span class="h-time">时长</span>
          <span class="h-actions"></span>
        </div>

        <!-- 歌曲行 -->
        <div
          v-for="(song, idx) in songs" :key="song.id"
          class="song-row"
          :class="{ playing: player.currentSong?.id === song.id }"
          @dblclick="playSong(song, idx)"
        >
          <span class="c-idx">
            <span v-if="player.currentSong?.id === song.id && player.isPlaying" class="eq">▮▮</span>
            <span v-else>{{ idx + 1 }}</span>
          </span>
          <div class="c-cover" @click="playSong(song, idx)">
            <img v-if="song.coverUrl" :src="song.coverUrl + '?param=60y60'" class="cover-img" />
            <span v-else class="cover-icon">♪</span>
            <span class="play-hover">▶</span>
          </div>
          <div class="c-info" @click="playSong(song, idx)">
            <span class="c-name">{{ song.name }}</span>
            <span class="c-artist">{{ song.artist || '未知歌手' }}</span>
          </div>
          <span class="c-album" :title="song.album">{{ song.album || '-' }}</span>
          <span class="c-time">{{ fmtSec(song.duration) }}</span>
          <div class="c-actions">
            <button
              :class="{ faved: favStore.isFav(song.id) }"
              @click.stop="favStore.toggleFav(song)"
              title="收藏"
            >★</button>
            <button @click.stop="player.addToQueue({
              sourceId: song.id, name: song.name, artist: song.artist || '',
              coverUrl: song.coverUrl || '', duration: song.duration || 0, platform: source.value,
            })" title="加入队列">+</button>
          </div>
        </div>

        <div v-if="!songs.length" class="empty-list">暂无歌曲</div>
      </div>
    </template>

    <div v-else class="empty-state">
      <p>暂无数据</p>
    </div>
  </div>
</template>

<style scoped>
.detail-page {
  width: 100%;
  min-height: 100%;
  padding-bottom: 80px;
}

/* ======== 导航栏（浅色） ======== */
.nav-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 32px;
  background: rgba(255,255,255,0.92);
  backdrop-filter: blur(12px);
  position: sticky;
  top: 0;
  z-index: 20;
  border-bottom: 1px solid #eee;
}
.nav-back {
  width: 32px; height: 32px;
  border-radius: 8px; border: 1px solid #ddd;
  background: transparent; color: #777;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; flex-shrink: 0;
  transition: all .15s;
}
.nav-back:hover { background: #f0f0f0; color: #333; }
.nav-title {
  font-size: 16px; font-weight: 600;
  color: #1a1a1a;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  flex: 1;
}
.nav-source {
  font-size: 11px; padding: 3px 10px; border-radius: 10px;
  background: rgba(49,194,124,0.12); color: #31c27c;
  font-weight: 500; flex-shrink: 0;
}

/* ======== 骨架 ======== */
.sk-hero {
  display: flex; gap: 32px; padding: 40px 32px;
}
.sk-cover {
  width: 220px; height: 220px; border-radius: 12px;
  background: #e0e0e0; flex-shrink: 0;
  animation: shim 1s infinite alternate;
}
.sk-info {
  flex: 1; display: flex; flex-direction: column; gap: 12px; padding-top: 8px;
}
.sk-info span {
  height: 16px; background: #e0e0e0; border-radius: 6px;
  animation: shim 1s infinite alternate;
}
.sk-info span:nth-child(1) { width: 60%; }
.sk-info span:nth-child(2) { width: 40%; }
.sk-info span:nth-child(3) { width: 80%; }
.sk-row {
  display: flex; gap: 12px; padding: 10px 40px 10px 48px;
}
.sk-row span {
  height: 14px; background: #eee; border-radius: 4px;
  animation: shim 1s infinite alternate;
}
.sk-row span:nth-child(1) { width: 28px; }
.sk-row span:nth-child(2) { flex: 1; }
.sk-row span:nth-child(3) { flex: 1; }
.sk-row span:nth-child(4) { width: 50px; }
@keyframes shim { to { opacity: .4; } }

/* ======== Hero 区域（浅色） ======== */
.hero {
  display: flex;
  gap: 40px;
  padding: 48px 40px 36px;
  background: linear-gradient(180deg, rgba(49,194,124,0.04) 0%, transparent 100%);
  border-bottom: 1px solid #eee;
}
.hero-cover {
  width: 220px; height: 220px;
  border-radius: 12px;
  overflow: hidden;
  flex-shrink: 0;
  position: relative;
  background: #e8e8e8;
  box-shadow: 0 4px 20px rgba(0,0,0,0.08);
}
.hero-cover img {
  width: 100%; height: 100%; object-fit: cover;
}
.cover-fallback {
  display: flex; align-items: center; justify-content: center;
  width: 100%; height: 100%; font-size: 48px; color: #bbb;
}
.cover-play-count {
  position: absolute; top: 8px; right: 8px;
  padding: 3px 10px; border-radius: 10px;
  background: rgba(0,0,0,0.5); backdrop-filter: blur(4px);
  font-size: 12px; color: #ddd;
  display: flex; align-items: center; gap: 4px;
}

.hero-info {
  flex: 1;
  display: flex; flex-direction: column;
  justify-content: center;
  gap: 12px;
  min-width: 0;
}
.hero-name {
  font-size: 28px; font-weight: 700;
  color: #1a1a1a;
  line-height: 1.3;
  word-break: break-word;
}
.hero-creator {
  display: flex; align-items: center; gap: 8px;
  font-size: 14px; color: #666;
}
.creator-avatar {
  width: 28px; height: 28px; border-radius: 50%; object-fit: cover;
}
.creator-label {
  font-size: 11px; color: #999;
}
.hero-stats {
  font-size: 13px; color: #888;
}
.hero-desc {
  font-size: 13px; color: #777;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.hero-actions {
  display: flex; gap: 12px;
  margin-top: 4px;
}
.btn-play {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 28px;
  background: #31c27c; color: #fff;
  border: none; border-radius: 24px;
  font-size: 15px; font-weight: 600;
  cursor: pointer;
  transition: all .15s;
}
.btn-play:hover { background: #28a86b; transform: scale(1.02); }
.btn-play:disabled { opacity: .4; cursor: not-allowed; transform: none; }
.btn-import {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 28px;
  background: transparent; color: #31c27c;
  border: 1px solid #31c27c; border-radius: 24px;
  font-size: 15px; font-weight: 600;
  cursor: pointer;
  transition: all .15s;
}
.btn-import:hover { background: #31c27c; color: #fff; }
.btn-import:disabled { opacity: .4; cursor: not-allowed; }

/* ======== 歌曲列表（浅色） ======== */
.song-list {
  padding: 0 40px;
}
.list-header {
  display: grid;
  grid-template-columns: 36px 44px 1fr 1fr 60px 64px;
  gap: 12px; align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #eee;
  color: #999; font-size: 12px;
  position: sticky; top: 62px; z-index: 10;
  background: #f5f5f5;
}
.h-idx { text-align: center; }
.h-time { text-align: right; }
.h-actions { text-align: center; }

.song-row {
  display: grid;
  grid-template-columns: 36px 44px 1fr 1fr 60px 64px;
  gap: 12px; align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
  transition: background .12s;
}
.song-row:hover { background: #fafafa; }
.song-row.playing {
  background: rgba(49,194,124,0.06);
}
.song-row.playing .c-name { color: #31c27c; }

.c-idx {
  text-align: center;
  color: #bbb; font-size: 13px; font-variant-numeric: tabular-nums;
}
.eq {
  color: #31c27c; font-weight: bold;
  animation: pulse .5s infinite alternate;
}
@keyframes pulse { to { opacity: .3; } }

.c-cover {
  position: relative; width: 36px; height: 36px;
  cursor: pointer; border-radius: 4px; overflow: hidden;
  background: #e8e8e8;
}
.cover-img { width: 100%; height: 100%; object-fit: cover; }
.cover-icon {
  display: flex; align-items: center; justify-content: center;
  width: 100%; height: 100%; color: #ccc; font-size: 14px;
}
.play-hover {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: rgba(0,0,0,0.4); color: #31c27c; font-size: 14px;
  opacity: 0; transition: .12s;
}
.c-cover:hover .play-hover { opacity: 1; }

.c-info {
  cursor: pointer; min-width: 0;
  display: flex; flex-direction: column; gap: 2px;
}
.c-name {
  font-size: 14px; color: #1a1a1a;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.c-artist {
  font-size: 12px; color: #999;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.c-album {
  font-size: 12px; color: #aaa;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.c-time {
  font-size: 12px; color: #bbb; text-align: right;
  font-variant-numeric: tabular-nums;
}

.c-actions {
  display: flex; gap: 6px; justify-content: center;
}
.c-actions button {
  width: 28px; height: 28px; border-radius: 6px;
  border: 1px solid transparent;
  background: transparent; color: #bbb;
  cursor: pointer; font-size: 14px;
  display: flex; align-items: center; justify-content: center;
  transition: all .12s;
}
.c-actions button:hover {
  border-color: #ddd;
  color: #666;
}
.c-actions button.faved {
  color: #ec4141; border-color: rgba(236,65,65,0.15);
}

/* ======== 空状态 ======== */
.empty-state {
  text-align: center; padding: 120px 0; color: #aaa;
  display: flex; flex-direction: column; align-items: center; gap: 12px;
  font-size: 15px;
}
.empty-list {
  text-align: center; padding: 40px 0; color: #aaa; font-size: 14px;
}

/* ======== 移动端：深色主题 ======== */
@media (max-width: 768px) {
  .detail-page { background: #0a0a0a; color: #e0e0e0; }

  .nav-bar {
    padding: 12px 16px;
    background: rgba(10,10,10,0.92);
    border-bottom-color: rgba(255,255,255,0.04);
  }
  .nav-back { border-color: rgba(255,255,255,0.1); color: #aaa; }
  .nav-back:hover { background: rgba(255,255,255,0.08); color: #fff; }
  .nav-title { font-size: 15px; color: #eee; }
  .nav-source { background: rgba(49,194,124,0.15); }

  .hero {
    flex-direction: column; align-items: center;
    gap: 24px; padding: 28px 16px 24px;
    background: linear-gradient(180deg, rgba(49,194,124,0.06) 0%, transparent 100%);
    border-bottom: none;
  }
  .hero-cover { width: 160px; height: 160px; background: #1a1a2e; box-shadow: 0 4px 20px rgba(0,0,0,0.4); }
  .hero-name { font-size: 22px; text-align: center; color: #fff; }
  .hero-creator { justify-content: center; color: #888; }
  .creator-label { color: #555; }
  .hero-stats { text-align: center; color: #777; }
  .hero-desc { text-align: center; color: #666; }
  .hero-actions { justify-content: center; }
  .btn-import { border-color: rgba(49,194,124,0.4); color: #31c27c; background: rgba(49,194,124,0.1); }

  .song-list { padding: 0 16px; }
  .list-header {
    grid-template-columns: 28px 36px 1fr 60px 48px;
    background: #0a0a0a;
    border-bottom-color: rgba(255,255,255,0.04);
    color: #555;
  }
  .song-row {
    grid-template-columns: 28px 36px 1fr 60px 48px;
    border-bottom-color: rgba(255,255,255,0.03);
  }
  .song-row:hover { background: rgba(255,255,255,0.03); }
  .song-row.playing { background: rgba(49,194,124,0.06); }
  .c-idx { color: #555; }
  .c-cover { background: #1a1a2e; }
  .cover-icon { color: #444; }
  .c-name { color: #d0d0d0; }
  .c-artist { color: #666; }
  .c-album { color: #555; }
  .c-time { color: #555; }
  .c-actions button { color: #555; }
  .c-actions button:hover { border-color: rgba(255,255,255,0.1); color: #aaa; }
  .c-actions button.faved { color: #ec4141; border-color: rgba(236,65,65,0.2); }

  .h-album, .c-album { display: none; }
  .h-actions, .c-actions { display: none; }
  .empty-state { color: #555; }
}
</style>
