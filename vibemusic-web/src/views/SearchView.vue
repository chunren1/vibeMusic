<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import SearchFilterBar from '@/components/SearchFilterBar.vue'
import SearchResultTable from '@/components/SearchResultTable.vue'
import { searchSongs } from '@/api/song'
import { useFavoriteStore } from '@/stores/favorite'
import { countHiddenByDuration, filterByDuration } from '@/utils/songFilter'

const route = useRoute()
const favStore = useFavoriteStore()

const keyword = ref(/** @type {string} */ (route.query.keyword || ''))
/** @type {import('vue').Ref<Song[]>} */
const results = ref([])
const loading = ref(false)
const page = ref(1)
const totalPageSize = 40

/** 平台筛选：走后端 platform 参数（'all' = 全平台，不传参） */
const platformFilter = ref('all')
/** 时长筛选：客户端过滤 */
const durationFilter = ref('all')

const visibleResults = computed(() => filterByDuration(results.value, durationFilter.value))
const hiddenCount = computed(() => countHiddenByDuration(results.value, durationFilter.value))
const hasActiveFilter = computed(() => platformFilter.value !== 'all' || durationFilter.value !== 'all')

// 请求序号：abort 掉的旧请求 reject 时不得覆盖新结果
let searchSeq = 0

// 输入自动搜索（300ms 防抖）——唯一的搜索触发入口，重复触发由 clearTimeout 去重
let debounceTimer = null
function scheduleSearch() {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    if (!keyword.value.trim()) { results.value = []; return }
    onSearch()
  }, 300)
}
watch(keyword, () => {
  // 换关键词必须重置筛选：旧平台/时长条件不能静默作用在新关键词上
  platformFilter.value = 'all'
  durationFilter.value = 'all'
  scheduleSearch()
})

async function onSearch() {
  const kw = keyword.value.trim()
  if (!kw) return
  const platform = platformFilter.value === 'all' ? null : platformFilter.value
  const seq = ++searchSeq
  loading.value = true
  page.value = 1
  try {
    // 「全部平台」不传第 4 参，回到后端不带 platform 的聚合搜索
    const res = platform
      ? await searchSongs(kw, 1, totalPageSize, platform)
      : await searchSongs(kw, 1, totalPageSize)
    if (seq !== searchSeq) return
    results.value = res.data?.list ?? []
  } catch (e) {
    if (seq !== searchSeq) return
    console.error('搜索失败:', e)
    results.value = []
  } finally {
    if (seq === searchSeq) loading.value = false
  }
}

/** 平台是单选：切换后重新请求，绝不在前端假装过滤 */
function onPlatformChange(value) {
  if (platformFilter.value === value) return
  platformFilter.value = value
  if (keyword.value.trim()) onSearch()
}

function onDurationChange(value) {
  durationFilter.value = value
}

/** 清除筛选：平台原本有限制时要回后端取全量 */
function clearFilters() {
  const needRefetch = platformFilter.value !== 'all'
  platformFilter.value = 'all'
  durationFilter.value = 'all'
  if (needRefetch && keyword.value.trim()) onSearch()
}

favStore.fetchFavIds()

watch(() => route.query.keyword, (val) => {
  // 只同步输入框 + 触发同一条防抖搜索：
  // 首屏 keyword 已在 setup 里用 route 初始化（值相同不会触发 keyword watcher），
  // 所以这里必须显式 scheduleSearch，否则带 ?keyword= 进入页面不会搜索。
  // 不再直接 onSearch：旧实现直搜 + 400ms 后防抖再搜一次，先发的请求被 abort 后
  // 把结果清空、闪一下"未找到结果"（round6 W5）。
  keyword.value = /** @type {string} */ (val || '')
  scheduleSearch()
}, { immediate: true })
</script>

<template>
  <TopBar />
  <div class="search-page">
    <div class="search-box">
      <input
        v-model="keyword"
        @keyup.enter="onSearch"
        placeholder="搜索歌曲..."
        class="search-input"
      />
      <button class="search-btn" @click="onSearch">搜索</button>
    </div>

    <!-- 筛选条：轻量 pill，视觉权重低于搜索框 -->
    <SearchFilterBar
      v-if="keyword.trim()"
      :platform="platformFilter"
      :duration="durationFilter"
      @update:platform="onPlatformChange"
      @update:duration="onDurationChange"
      @clear="clearFilters"
    />

    <p v-if="keyword.trim() && results.length > 0 && !loading" class="result-meta">
      共 {{ visibleResults.length }} 首
      <span v-if="hiddenCount > 0" class="result-hidden" role="status">· 已按条件隐藏 {{ hiddenCount }} 首</span>
    </p>

    <SearchResultTable :loading="loading" :songs="visibleResults" />

    <div v-if="!loading && visibleResults.length === 0" class="empty">
      <template v-if="!keyword.trim()">
        <p>输入关键词搜索歌曲</p>
      </template>
      <template v-else-if="results.length > 0">
        <p>当前筛选下没有歌曲</p>
        <p class="empty-hint">时长条件把 {{ results.length }} 首结果都筛掉了</p>
        <button type="button" class="empty-action" @click="clearFilters">清除筛选</button>
      </template>
      <template v-else>
        <p>未找到结果</p>
        <template v-if="hasActiveFilter">
          <p class="empty-hint">当前平台筛选下没有匹配，可以切回「全部平台」</p>
          <button type="button" class="empty-action" @click="clearFilters">清除筛选</button>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.search-page { padding: 24px 32px; max-width: 960px; margin: 0 auto; }

.search-box {
  display: flex; gap: 12px; margin-bottom: 12px;
}

.search-input {
  flex: 1; padding: 10px 16px;
  border: 1px solid var(--bg-hover); border-radius: 8px;
  background: var(--bg-card); color: var(--text-primary); font-size: 15px; outline: none;
}
.search-input:focus { border-color: var(--primary); }

.search-btn {
  padding: 10px 24px;
  background: var(--primary); color: var(--text-primary); border: none; border-radius: 8px;
  cursor: pointer; font-size: 14px; font-weight: 600;
}
.search-btn:hover { background: #28a86b; }

/* 结果计数 / 被筛掉条数（让用户知道筛选没把结果弄丢） */
.result-meta {
  margin-bottom: 8px;
  color: var(--text-tertiary);
  font-size: 12px;
}
.result-hidden { color: var(--primary); }

.empty {
  text-align: center; padding: 60px 0; color: var(--text-tertiary);
}
.empty-hint { margin-top: 8px; font-size: 13px; color: var(--text-tertiary); }
.empty-action {
  margin-top: 16px; padding: 8px 20px;
  border: 1px solid var(--primary); border-radius: var(--radius-full);
  background: transparent; color: var(--primary);
  font-size: 13px; font-weight: 500; cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out);
}
.empty-action:hover { background: rgba(49, 194, 124, 0.12); }
</style>
