<script setup>
import { ref, watch, onMounted } from 'vue'
import { API_HOST } from '@/api/request'
import { useRoute, useRouter } from 'vue-router'
import { searchSongs, downloadSong as apiDownload } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const favStore = useFavoriteStore()

const keyword = ref(route.query.q || '')
const results = ref([])
const loading = ref(false)
const sourceFilter = ref('all')
const page = ref(1)
const hasMore = ref(false)
const hasSearched = ref(false)
const PAGE_SIZE = 20

const HISTORY_KEY = 'vibe_search_history'
const MAX_HISTORY = 10
const searchHistory = ref(loadHistory())
const showHistory = ref(false)

function loadHistory() {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') } catch { return [] }
}
function saveHistory(kw) {
  const list = searchHistory.value.filter(k => k !== kw)
  list.unshift(kw)
  searchHistory.value = list.slice(0, MAX_HISTORY)
  localStorage.setItem(HISTORY_KEY, JSON.stringify(searchHistory.value))
}
function removeHistory(idx) {
  searchHistory.value.splice(idx, 1)
  localStorage.setItem(HISTORY_KEY, JSON.stringify(searchHistory.value))
}
function clearHistory() {
  searchHistory.value = []
  localStorage.removeItem(HISTORY_KEY)
}
function useHistory(kw) {
  keyword.value = kw
  showHistory.value = false
  doSearch(true, true)
}

const downloadingIds = ref(new Set())

favStore.fetchFavIds()

async function doSearch(reset = true, saveHist = false) {
  const kw = keyword.value.trim()
  if (!kw) { results.value = []; hasSearched.value = false; showHistory.value = true; return }
  showHistory.value = false
  if (saveHist) saveHistory(kw)
  loading.value = true
  hasSearched.value = true
  if (reset) { page.value = 1; results.value = [] }
  try {
    const platform = sourceFilter.value === 'all' ? null : sourceFilter.value
    const res = await searchSongs(kw, page.value, PAGE_SIZE, platform)
    const data = res.data || []
    results.value = reset ? data : [...results.value, ...data]
    hasMore.value = data.length >= PAGE_SIZE
  } catch { if (reset) results.value = [] }
  finally { loading.value = false }
}

// 输入自动搜索（400ms 防抖）
let debounceTimer = null
watch(keyword, () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    if (!keyword.value.trim()) { results.value = []; hasSearched.value = false; showHistory.value = true; return }
    showHistory.value = false
    doSearch(true)
  }, 400)
})

function loadMore() { page.value++; doSearch(false) }

function onSourceChange() { page.value = 1; doSearch(true) }

function playSong(song) {
  if (!song.sourceId) return
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '')
  router.push('/m/player')
}

function addToQueueFn(song) {
  player.addToQueue({ sourceId: song.sourceId, name: song.name, artist: song.artist, coverUrl: song.coverUrl, duration: song.duration })
}

function handleDownload(song) {
  if (downloadingIds.value.has(song.sourceId)) return
  downloadingIds.value.add(song.sourceId)
  downloadViaBackend(song)
}

function downloadViaBackend(song) {
  apiDownload(song.sourceId, song).then(res => {
    const u = res.data?.fileUrl || `${API_HOST}/api/download/file/${song.sourceId}`
    const a = document.createElement('a'); a.href = u; a.download = `${song.name}.mp3`; a.click()
  }).catch(() => {
    const a = document.createElement('a'); a.href = `${API_HOST}/api/download/file/${song.sourceId}`; a.download = `${song.name}.mp3`; a.click()
  }).finally(() => downloadingIds.value.delete(song.sourceId))
}

function fmtSec(s) {
  if (!s || !isFinite(s)) return '--'
  const m = Math.floor(s / 60), sec = Math.floor(s % 60)
  return m + ':' + String(sec).padStart(2, '0')
}

function goBack() { router.push('/m') }

onMounted(() => {
  if (keyword.value) doSearch(true)
})
</script>

<template>
  <div class="m-search">
    <!-- 搜索栏 -->
    <div class="m-search-top">
      <button class="m-back" @click="goBack">
        <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <div class="m-search-bar">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#888" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input v-model="keyword" @keyup.enter="doSearch(true, true)" @focus="!keyword.trim() && (showHistory = true)" placeholder="搜索歌曲、歌手..." class="m-search-input" autofocus />
      </div>
      <span class="m-search-btn" @click="doSearch(true, true)">搜索</span>
    </div>

    <!-- 搜索历史 -->
    <div v-if="showHistory && searchHistory.length && !hasSearched" class="m-history">
      <div class="m-history-header">
        <span class="m-history-title">搜索历史</span>
        <button class="m-history-clear" @click="clearHistory">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#888" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
        </button>
      </div>
      <div class="m-history-tags">
        <span v-for="(kw, idx) in searchHistory" :key="idx" class="m-history-tag" @click="useHistory(kw)">
          {{ kw }}
          <button class="m-history-del" @click.stop="removeHistory(idx)">&times;</button>
        </span>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div v-if="results.length" class="m-filter-bar">
      <button :class="{ active: sourceFilter === 'all' }" @click="sourceFilter = 'all'; onSourceChange()">全部</button>
      <button :class="{ active: sourceFilter === 'netease' }" @click="sourceFilter = 'netease'; onSourceChange()">网易云</button>
      <button :class="{ active: sourceFilter === 'qq' }" @click="sourceFilter = 'qq'; onSourceChange()">QQ</button>
    </div>

    <!-- 结果列表 -->
    <div v-if="loading && !results.length" class="m-loading">搜索中...</div>

    <div v-if="results.length" class="m-result-list">
      <div v-for="(song, idx) in results" :key="song.sourceId"
        class="m-song-item"         :class="{ playing: player.currentSong.id === song.sourceId && player.isPlaying }"
        @click="playSong(song)"
        v-memo="[song.sourceId, player.currentSong.id === song.sourceId, player.isPlaying, favStore.isFav(song.sourceId)]">
        <div class="m-song-cover" v-lazy-img:bg="song.coverUrl ? `${song.coverUrl}?param=80y80` : null">
          <span v-if="player.currentSong.id === song.sourceId && player.isPlaying" class="m-eq"><span></span><span></span><span></span></span>
        </div>
        <div class="m-song-info">
          <div class="m-song-name">
            {{ song.name }}
            <span v-if="song.platform" class="tag" :class="song.platform">{{ song.platform === 'qq' ? 'QQ' : '网易云' }}</span>
          </div>
          <div class="m-song-artist">{{ song.artist }} · {{ fmtSec(song.duration) }}</div>
        </div>
        <div class="m-song-acts">
          <button :class="{ faved: favStore.isFav(song.sourceId) }" @click.stop="favStore.toggleFav(song)">
            <svg viewBox="0 0 24 24" width="17" height="17" :fill="favStore.isFav(song.sourceId) ? '#ffc107' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12,2 15.09,8.26 22,9.27 17,14.14 18.18,21.02 12,17.77 5.82,21.02 7,14.14 2,9.27 8.91,8.26"/></svg>
          </button>
          <button @click.stop="addToQueueFn(song)" title="加入队列">
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          </button>
          <button @click.stop="handleDownload(song)">
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </button>
        </div>
      </div>

      <div v-if="hasMore" class="m-load-more">
        <button @click="loadMore" :disabled="loading">{{ loading ? '加载中...' : '加载更多' }}</button>
      </div>
    </div>

    <div v-else-if="!loading && hasSearched" class="m-empty">未找到相关歌曲</div>
  </div>
</template>

<style scoped>
.m-search { padding: 0 16px 12px; min-height: 100vh; background: #0a0a0a; }

/* 搜索栏 */
.m-search-top {
  display: flex; align-items: center; gap: 10px; padding: 10px 0 8px;
  position: sticky; top: 0; z-index: 10; background: #0a0a0a;
}
.m-back { border: none; background: none; color: #ccc; padding: 4px; cursor: pointer; }
.m-search-bar {
  flex: 1; display: flex; align-items: center; gap: 8px;
  background: rgba(255,255,255,0.08); border-radius: 20px; padding: 8px 14px;
}
.m-search-input {
  flex: 1; border: none; background: none; color: #e0e0e0; font-size: 14px; outline: none;
}
.m-search-btn {
  font-size: 14px; color: #31c27c; cursor: pointer; white-space: nowrap;
}

/* 筛选栏 */
.m-filter-bar {
  display: flex; gap: 8px; padding: 8px 0 12px; overflow-x: auto;
}
.m-filter-bar button {
  padding: 5px 14px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.12);
  background: transparent; color: #888; font-size: 12px; cursor: pointer; white-space: nowrap;
}
.m-filter-bar button.active { background: #31c27c; border-color: #31c27c; color: #fff; }

/* 歌曲列表 */
.m-result-list { display: flex; flex-direction: column; gap: 2px; }
.m-song-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 8px;
  border-radius: 10px;
}
.m-song-item:active { background: rgba(255,255,255,.03); }
.m-song-item.playing { background: rgba(49,194,124,.06); }
.m-song-cover {
  width: 42px; height: 42px; border-radius: 8px; flex-shrink: 0;
  background: rgba(255,255,255,0.06) center/cover no-repeat;
  display: flex; align-items: center; justify-content: center; color: #aaa; font-size: 12px;
}
.m-eq { display: flex; align-items: flex-end; gap: 2px; height: 14px; }
.m-eq span { width: 2px; background: #31c27c; border-radius: 1px; animation: eq .7s ease-in-out infinite alternate; }
.m-eq span:nth-child(1) { height: 7px; }
.m-eq span:nth-child(2) { height: 12px; animation-delay: .15s; }
.m-eq span:nth-child(3) { height: 5px; animation-delay: .3s; }
@keyframes eq { to { height: 3px; } }
.m-song-info { flex: 1; min-width: 0; }
.m-song-name { font-size: 14px; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-song-artist { font-size: 12px; color: #888; margin-top: 2px; }
.tag {
  display: inline-block; font-size: 9px; padding: 0px 4px; border-radius: 3px; margin-left: 4px;
  vertical-align: middle; line-height: 16px;
}
.tag.qq { background: rgba(24,144,255,.15); color: #1890ff; }
.tag.netease { background: rgba(250,84,28,.15); color: #fa541c; }
.m-song-acts { display: flex; gap: 8px; flex-shrink: 0; }
.m-song-acts button {
  border: none; background: none; font-size: 16px; cursor: pointer; padding: 4px; color: #666;
}
.m-song-acts button.faved { color: #ffc107; }

.m-load-more { text-align: center; padding: 20px 0; }
.m-load-more button {
  padding: 8px 32px; border: 1px solid #31c27c; border-radius: 20px;
  background: transparent; color: #31c27c; font-size: 13px; cursor: pointer;
}
.m-load-more button:disabled { opacity: .4; }
.m-loading, .m-empty { text-align: center; padding: 60px 0; color: #666; font-size: 14px; }

/* 搜索历史 */
.m-history { padding: 16px 0; }
.m-history-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.m-history-title { font-size: 13px; color: #999; }
.m-history-clear { border: none; background: none; color: #666; cursor: pointer; padding: 2px; display: flex; align-items: center; }
.m-history-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.m-history-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 6px 12px; border-radius: 16px;
  background: rgba(255,255,255,0.06); color: #ccc; font-size: 13px;
  cursor: pointer; transition: .15s;
}
.m-history-tag:active { background: rgba(49,194,124,0.15); color: #31c27c; }
.m-history-del {
  border: none; background: none; color: #666; font-size: 14px;
  cursor: pointer; padding: 0; line-height: 1; margin-left: 2px;
}
.m-history-del:hover { color: #cf1322; }
</style>
