<script setup>
import { ref, watch, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PlaylistPopup from '@/components/PlaylistPopup.vue'
import MSearchRow from '@/components/mobile/MSearchRow.vue'
import SearchFilterBar from '@/components/SearchFilterBar.vue'
import { searchSongs } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useFavoriteStore } from '@/stores/favorite'
import { countHiddenByDuration, filterByDuration } from '@/utils/songFilter'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const favStore = useFavoriteStore()

const keyword = ref(/** @type {string} */ (route.query.q || ''))
/** @type {import('vue').Ref<Song[]>} */
const results = ref([])
const loading = ref(false)
/** 平台筛选：走后端 platform 参数（'all' = 全平台，不传参） */
const sourceFilter = ref('all')
/** 时长筛选：客户端过滤 */
const durationFilter = ref('all')
const page = ref(1)
const hasMore = ref(false)
const hasSearched = ref(false)
const PAGE_SIZE = 20

const visibleResults = computed(() => filterByDuration(results.value, durationFilter.value))
const hiddenCount = computed(() => countHiddenByDuration(results.value, durationFilter.value))
const hasActiveFilter = computed(() => sourceFilter.value !== 'all' || durationFilter.value !== 'all')

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

const showPlaylistPopup = ref(false)
const playlistTargetSong = ref(null)

function openPlaylistPopup(song) {
  playlistTargetSong.value = { ...song, sourceId: song.sourceId, name: song.name, artist: song.artist, coverUrl: song.coverUrl, duration: song.duration || 0 }
  showPlaylistPopup.value = true
}

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
    // 「全部平台」传 null，由 searchSongs 省略 platform 参数
    const res = await searchSongs(kw, page.value, PAGE_SIZE, platform)
    const result = res.data || {}
    const data = result.list || []
    results.value = reset ? data : [...results.value, ...data]
    hasMore.value = result.hasMore ?? (data.length >= PAGE_SIZE)
  } catch { if (reset) results.value = [] }
  finally { loading.value = false }
}

// 输入自动搜索（400ms 防抖）
let debounceTimer = null
watch(keyword, () => {
  // 换关键词必须重置筛选：旧平台/时长条件不能静默作用在新关键词上
  sourceFilter.value = 'all'
  durationFilter.value = 'all'
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    if (!keyword.value.trim()) { results.value = []; hasSearched.value = false; showHistory.value = true; return }
    showHistory.value = false
    doSearch(true)
  }, 400)
})

function loadMore() { page.value++; doSearch(false) }

function onSourceChange() { page.value = 1; doSearch(true) }

/** 平台单选：切换即回后端重新取（不在前端假装过滤） */
function onPlatformChange(value) {
  if (sourceFilter.value === value) return
  sourceFilter.value = value
  onSourceChange()
}

function onDurationChange(value) { durationFilter.value = value }

/** 清除筛选：平台原本有限制时要回后端取全量 */
function clearFilters() {
  const needRefetch = sourceFilter.value !== 'all'
  sourceFilter.value = 'all'
  durationFilter.value = 'all'
  if (needRefetch && keyword.value.trim()) onSourceChange()
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

    <!-- 筛选栏（单行横向滚动，不挤压搜索框） -->
    <SearchFilterBar
      v-if="hasSearched"
      :platform="sourceFilter"
      :duration="durationFilter"
      variant="mobile"
      @update:platform="onPlatformChange"
      @update:duration="onDurationChange"
      @clear="clearFilters"
    />

    <p v-if="hiddenCount > 0" class="m-filter-note" role="status">已按条件隐藏 {{ hiddenCount }} 首</p>

    <div v-if="loading && !results.length" class="m-loading">搜索中...</div>

    <!-- 结果列表 -->
    <div v-if="results.length" class="m-result-list">
      <MSearchRow
        v-for="song in visibleResults" :key="song.sourceId"
        :song="song"
        :playing="player.currentSong.id === song.sourceId && player.isPlaying"
        v-memo="[song.sourceId, player.currentSong.id === song.sourceId, player.isPlaying, favStore.isFav(song.sourceId)]"
        @open-playlist="openPlaylistPopup(song)"
      />

      <div v-if="!visibleResults.length" class="m-empty m-empty-inline">
        <p>当前筛选下没有歌曲</p>
        <p class="m-empty-hint">时长条件把 {{ results.length }} 首结果都筛掉了</p>
        <button type="button" class="m-empty-action" @click="clearFilters">清除筛选</button>
      </div>

      <div v-if="hasMore" class="m-load-more">
        <button @click="loadMore" :disabled="loading">{{ loading ? '加载中...' : '加载更多' }}</button>
      </div>
    </div>

    <div v-else-if="!loading && hasSearched" class="m-empty">
      <p>未找到相关歌曲</p>
      <template v-if="hasActiveFilter">
        <p class="m-empty-hint">当前平台筛选下没有匹配，可以切回「全部平台」</p>
        <button type="button" class="m-empty-action" @click="clearFilters">清除筛选</button>
      </template>
    </div>
  </div>

  <PlaylistPopup
    v-if="showPlaylistPopup && playlistTargetSong"
    :song="playlistTargetSong"
    @close="showPlaylistPopup = false"
  />
</template>

<style scoped>
.m-search { padding: 0 16px 12px; min-height: 100vh; background: var(--m-bg-base); }

/* 搜索栏 */
.m-search-top {
  display: flex; align-items: center; gap: 10px; padding: 12px 0 8px;
  position: sticky; top: 0; z-index: 10;
  background: linear-gradient(180deg, var(--m-bg-base) 80%, transparent);
}
.m-back { border: none; background: none; color: var(--m-text-secondary); padding: 4px; cursor: pointer; }
.m-search-bar {
  flex: 1; display: flex; align-items: center; gap: 8px;
  background: var(--m-bg-card); border-radius: var(--m-radius-full);
  padding: 10px 16px; border: 1px solid rgba(255,255,255,0.04);
  transition: border-color 0.25s;
}
.m-search-bar:focus-within { border-color: rgba(49,194,124,0.35); }
.m-search-input {
  flex: 1; border: none; background: none; color: var(--m-text-primary); font-size: 14px; outline: none;
}
.m-search-btn {
  font-size: 14px; font-weight: 500; color: var(--m-primary); cursor: pointer; white-space: nowrap;
}

/* 被时长条件隐藏的条数（避免用户以为结果丢了） */
.m-filter-note { padding: 0 0 8px; font-size: 12px; color: var(--primary); }

/* 歌曲列表 */
.m-result-list { display: flex; flex-direction: column; }

.m-load-more { text-align: center; padding: 24px 0; }
.m-load-more button {
  padding: 8px 36px; border: 1px solid rgba(49,194,124,0.3); border-radius: var(--m-radius-full);
  background: transparent; color: var(--m-primary); font-size: 13px; cursor: pointer;
  transition: background 0.2s;
}
.m-load-more button:active { background: rgba(49,194,124,0.08); }
.m-load-more button:disabled { opacity: .4; }
.m-loading, .m-empty { text-align: center; padding: 60px 0; color: var(--m-text-secondary); font-size: 14px; }
.m-empty-inline { padding: 32px 0; }
.m-empty-hint { margin-top: 8px; font-size: 12px; color: var(--m-text-tertiary); }
.m-empty-action {
  margin-top: 16px; padding: 8px 20px;
  border: 1px solid var(--primary); border-radius: var(--m-radius-full);
  background: transparent; color: var(--primary); font-size: 13px; cursor: pointer;
}

/* 搜索历史 */
.m-history { padding: 20px 0; }
.m-history-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.m-history-title { font-size: 13px; font-weight: 500; color: var(--m-text-secondary); }
.m-history-clear { border: none; background: none; color: var(--m-text-tertiary); cursor: pointer; padding: 2px; display: flex; align-items: center; transition: color 0.2s; }
.m-history-clear:active { color: #e04040; }
.m-history-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.m-history-tag {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 7px 14px; border-radius: var(--m-radius-full);
  background: var(--m-bg-card); color: var(--m-text-secondary); font-size: 13px;
  cursor: pointer; transition: background 0.2s, color 0.2s, border-color 0.2s;
  border: 1px solid rgba(255,255,255,0.03);
}
.m-history-tag:active { background: rgba(49,194,124,0.1); color: var(--primary); border-color: rgba(49,194,124,0.2); }
.m-history-del {
  border: none; background: none; color: var(--m-text-tertiary); font-size: 14px;
  cursor: pointer; padding: 0; line-height: 1; margin-left: 2px;
}
.m-history-del:active { color: #e04040; }
</style>
