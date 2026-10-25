<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { API_HOST } from '@/api/request'
import { downloadSong as apiDownload } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'
import { platformLabel } from '@/utils/songFilter'

/** 移动端搜索结果行（播放 / 收藏 / 入队 / 下载 / 加歌单） */
const props = defineProps({
  song: { type: Object, required: true },
  playing: { type: Boolean, default: false },
})

const emit = defineEmits(['open-playlist'])

const router = useRouter()
const player = usePlayerStore()
const favStore = useFavoriteStore()

const downloading = ref(false)

function fmtSec(s) {
  if (!s || !isFinite(s)) return '--'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m + ':' + String(sec).padStart(2, '0')
}

function playSong() {
  if (!props.song.sourceId) return
  player.playSongFromApi(props.song.sourceId, props.song.name, props.song.artist, props.song.coverUrl || '')
  router.push('/m/player')
}

function addToQueueFn() {
  const s = props.song
  player.addToQueue({ sourceId: s.sourceId, name: s.name, artist: s.artist, coverUrl: s.coverUrl, duration: s.duration })
}

function handleDownload() {
  if (downloading.value) return
  downloading.value = true
  const song = props.song
  apiDownload(song.sourceId, song).then(res => {
    const u = res.data?.fileUrl || `${API_HOST}/api/download/file/${song.sourceId}`
    const a = document.createElement('a'); a.href = u; a.download = `${song.name}.mp3`; a.click()
  }).catch(() => {
    const a = document.createElement('a'); a.href = `${API_HOST}/api/download/file/${song.sourceId}`; a.download = `${song.name}.mp3`; a.click()
  }).finally(() => { downloading.value = false })
}
</script>

<template>
  <div class="m-song-item" :class="{ playing }" @click="playSong">
    <div class="m-song-cover" :style="song.coverUrl ? { backgroundImage: `url(${song.coverUrl}?param=80y80)` } : { background: '#1a1a2e' }">
      <span v-if="playing" class="m-eq"><span></span><span></span><span></span></span>
    </div>
    <div class="m-song-info">
      <div class="m-song-name">
        <span class="m-song-title">{{ song.name }}</span>
        <span v-if="song.platform" class="tag" :class="song.platform">{{ platformLabel(song.platform) }}</span>
      </div>
      <div class="m-song-artist">{{ song.artist }} · {{ fmtSec(song.duration) }}</div>
    </div>
    <div class="m-song-acts">
      <button type="button" :class="{ faved: favStore.isFav(song.sourceId) }" @click.stop="favStore.toggleFav(song)">
        <svg viewBox="0 0 24 24" width="17" height="17" :fill="favStore.isFav(song.sourceId) ? '#ffc107' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
      </button>
      <button type="button" @click.stop="addToQueueFn" title="加入队列">
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
      </button>
      <button type="button" @click.stop="handleDownload">
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
      </button>
      <button type="button" @click.stop="emit('open-playlist')" title="加入歌单">
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5"/><path d="M18 2l4 4-8 8-4-1 1-4z"/></svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.m-song-item {
  display: flex; align-items: center; gap: 12px; padding: 10px 10px;
  border-radius: var(--m-radius-md); transition: background 0.2s;
}
.m-song-item:active { background: var(--m-bg-card-hover); }
.m-song-item.playing { background: rgba(49,194,124,0.06); }
.m-song-cover {
  width: 44px; height: 44px; border-radius: var(--m-radius-sm); flex-shrink: 0;
  background-color: var(--m-bg-card);
  background-position: center; background-size: cover; background-repeat: no-repeat;
  display: flex; align-items: center; justify-content: center; color: var(--m-text-secondary); font-size: 12px;
  box-shadow: 0 2px 6px rgba(0,0,0,0.3);
}
.m-eq { display: flex; align-items: flex-end; gap: 2px; height: 14px; }
.m-eq span { width: 2px; background: var(--primary); border-radius: 2px; animation: eq .6s ease-in-out infinite alternate; }
.m-eq span:nth-child(1) { height: 7px; }
.m-eq span:nth-child(2) { height: 12px; animation-delay: .12s; }
.m-eq span:nth-child(3) { height: 5px; animation-delay: .25s; }
@keyframes eq { to { height: 3px; } }
.m-song-info { flex: 1; min-width: 0; }
.m-song-name { display: flex; align-items: center; gap: 5px; font-size: 14px; font-weight: 500; color: var(--m-text-primary); }
/* 歌名先截断，平台角标始终可见（角标是筛选结果的来源凭据） */
.m-song-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-song-artist { font-size: 12px; color: var(--m-text-secondary); margin-top: 2px; }
.tag {
  flex-shrink: 0;
  display: inline-block; font-size: 9px; font-weight: 600; padding: 1px 5px; border-radius: 3px;
  vertical-align: middle; line-height: 16px;
}
.tag.qq { background: rgba(24,144,255,.12); color: #1890ff; }
.tag.netease { background: rgba(250,84,28,.12); color: #fa541c; }
.m-song-acts { display: flex; gap: 4px; flex-shrink: 0; }
/* WCAG 2.5.8：触控目标 ≥44px（图标视觉尺寸不变，flex 居中） */
.m-song-acts button {
  border: none; background: none; font-size: 16px; cursor: pointer; padding: 5px;
  min-width: 44px; min-height: 44px;
  display: flex; align-items: center; justify-content: center;
  color: var(--m-text-secondary); transition: color 0.2s;
}
.m-song-acts button:active { color: var(--m-text-primary); }
.m-song-acts button.faved { color: var(--m-gold); }
</style>
