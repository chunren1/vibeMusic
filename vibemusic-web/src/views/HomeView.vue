<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// ===== 用户信息（模拟） =====
const user = ref({
  name: '音乐爱好者',
  avatar: '',
})

// ===== Banner 轮播 =====
const slides = ref([
  { id: 1, title: '总有一首歌', subtitle: '让你想起最初的自己', bg: 'linear-gradient(135deg, #2b5a3a, #1a3d24)', emoji: '🎵' },
  { id: 2, title: '发现好音乐', subtitle: '从这里开始你的音乐之旅', bg: 'linear-gradient(135deg, #4a2c5a, #2d1a3d)', emoji: '🎸' },
  { id: 3, title: '随机推荐', subtitle: '听听不一样的声音', bg: 'linear-gradient(135deg, #2a4a5a, #1a2d3d)', emoji: '🎹' },
])
const activeSlide = ref(0)

onMounted(() => {
  setInterval(() => {
    activeSlide.value = (activeSlide.value + 1) % slides.value.length
  }, 4000)
})

// ===== 随机推荐歌曲 =====
const randomSongs = ref([])

function shuffleSongs() {
  const pool = [
    { id: 1, title: '晴天', artist: '周杰伦', cover: '' },
    { id: 2, title: '孤勇者', artist: '陈奕迅', cover: '' },
    { id: 3, title: '起风了', artist: '买辣椒也用券', cover: '' },
    { id: 4, title: '错位时空', artist: '艾辰', cover: '' },
    { id: 5, title: '若月亮没来', artist: '黄绮珊', cover: '' },
    { id: 6, title: '罗生门', artist: '张子豪', cover: '' },
    { id: 7, title: '篇章', artist: '张韶涵 / 王赫野', cover: '' },
    { id: 8, title: '我记得', artist: '赵雷', cover: '' },
    { id: 9, title: '兰亭序', artist: '周杰伦', cover: '' },
    { id: 10, title: '青花瓷', artist: '周杰伦', cover: '' },
    { id: 11, title: '夜曲', artist: '周杰伦', cover: '' },
    { id: 12, title: '稻香', artist: '周杰伦', cover: '' },
  ]
  const shuffled = pool.sort(() => Math.random() - 0.5)
  randomSongs.value = shuffled.slice(0, 8).map(s => ({
    ...s,
    coverColor: randomColor(),
  }))
}

function randomColor() {
  const colors = ['#31c27c', '#ec4141', '#5b3cc4', '#d44455', '#3c7cc4', '#c48b3c']
  return colors[Math.floor(Math.random() * colors.length)]
}

onMounted(() => shuffleSongs())

// ===== 推荐歌单 =====
const playlists = ref([
  { id: 1, name: '华语热门精选', count: 56, color: '#e84c3d' },
  { id: 2, name: '治愈系纯音乐', count: 38, color: '#3498db' },
  { id: 3, name: '说唱新世代', count: 29, color: '#2ecc71' },
  { id: 4, name: '怀旧金曲', count: 64, color: '#f39c12' },
  { id: 5, name: '民谣在路上', count: 27, color: '#9b59b6' },
  { id: 6, name: '电竞燃曲BGM', count: 33, color: '#1abc9c' },
])

// ===== 页面内搜索 =====
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const hasSearched = ref(false)

const allSongs = [
  { id: 1, title: '晴天', artist: '周杰伦', album: '叶惠美', duration: '4:29' },
  { id: 2, title: '孤勇者', artist: '陈奕迅', album: '孤勇者', duration: '4:16' },
  { id: 3, title: '起风了', artist: '买辣椒也用券', album: '起风了', duration: '5:08' },
  { id: 4, title: '错位时空', artist: '艾辰', album: '错位时空', duration: '3:42' },
  { id: 5, title: '若月亮没来', artist: '黄绮珊', album: '若月亮没来', duration: '4:11' },
  { id: 6, title: '罗生门', artist: '张子豪', album: '罗生门', duration: '3:33' },
  { id: 7, title: '篇章', artist: '张韶涵 / 王赫野', album: '篇章', duration: '4:05' },
  { id: 8, title: '我记得', artist: '赵雷', album: '署前街少年', duration: '5:22' },
  { id: 9, title: '兰亭序', artist: '周杰伦', album: '魔杰座', duration: '4:13' },
  { id: 10, title: '青花瓷', artist: '周杰伦', album: '我很忙', duration: '3:59' },
  { id: 11, title: '夜曲', artist: '周杰伦', album: '十一月的肖邦', duration: '3:51' },
  { id: 12, title: '稻香', artist: '周杰伦', album: '魔杰座', duration: '3:43' },
  { id: 13, title: '七里香', artist: '周杰伦', album: '七里香', duration: '4:57' },
  { id: 14, title: '一路向北', artist: '周杰伦', album: '十一月的肖邦', duration: '4:46' },
  { id: 15, title: '后来', artist: '刘若英', album: '我等你', duration: '5:33' },
  { id: 16, title: '平凡之路', artist: '朴树', album: '平凡之路', duration: '5:02' },
]

function doSearch() {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) {
    searchResults.value = []
    hasSearched.value = false
    return
  }
  searchLoading.value = true
  hasSearched.value = true

  // 模拟搜索延时
  setTimeout(() => {
    searchResults.value = allSongs.filter(s =>
      s.title.toLowerCase().includes(keyword) ||
      s.artist.toLowerCase().includes(keyword) ||
      s.album.toLowerCase().includes(keyword)
    )
    searchLoading.value = false
  }, 200)
}

function clearSearch() {
  searchKeyword.value = ''
  searchResults.value = []
  hasSearched.value = false
}

function playSong(song) {
  console.log('播放:', song.title)
}
</script>

<template>
  <div class="home">
    <!-- 搜索栏 + 用户信息 -->
    <div class="top-bar">
      <div class="search-area">
        <div class="search-box">
          <span class="search-icon">🔍</span>
          <input
            v-model="searchKeyword"
            @keyup.enter="doSearch"
            @input="searchKeyword.trim() === '' ? clearSearch() : null"
            placeholder="搜索歌曲、歌手、专辑"
            class="search-input"
          />
          <button v-if="searchKeyword" class="search-clear" @click="clearSearch">✕</button>
        </div>

        <!-- 搜索结果下拉 -->
        <Transition name="dropdown">
          <div v-if="hasSearched && searchKeyword.trim()" class="search-dropdown">
            <div v-if="searchLoading" class="search-status">搜索中...</div>
            <div v-else-if="searchResults.length === 0" class="search-status">未找到相关歌曲</div>
            <div v-else class="search-result-list">
              <div class="result-header">
                <span>找到 {{ searchResults.length }} 首歌曲</span>
                <button class="result-clear" @click="clearSearch">关闭</button>
              </div>
              <div
                v-for="(song, idx) in searchResults"
                :key="song.id"
                class="result-item"
                @dblclick="playSong(song)"
              >
                <span class="ri-index">{{ idx + 1 }}</span>
                <div class="ri-cover">♪</div>
                <div class="ri-info">
                  <span class="ri-title">{{ song.title }}</span>
                  <span class="ri-meta">{{ song.artist }} · {{ song.album }}</span>
                </div>
                <span class="ri-time">{{ song.duration }}</span>
              </div>
            </div>
          </div>
        </Transition>
      </div>

      <div class="user-info">
        <div class="user-avatar">👤</div>
        <span class="user-name">{{ user.name }}</span>
      </div>
    </div>

    <!-- Banner 轮播 + 下方内容（搜索时隐藏） -->
    <template v-if="!hasSearched">
    <div class="banner">
      <div
        v-for="(slide, idx) in slides"
        :key="slide.id"
        class="banner-slide"
        :class="{ active: idx === activeSlide }"
        @click="activeSlide = idx"
      >
        <div class="slide-bg" :style="{ background: slide.bg }"></div>
        <div class="slide-emoji">{{ slide.emoji }}</div>
        <div class="slide-text">
          <h2>{{ slide.title }}</h2>
          <p>{{ slide.subtitle }}</p>
        </div>
      </div>
      <div class="banner-dots">
        <span
          v-for="(_, idx) in slides"
          :key="idx"
          class="dot" :class="{ active: idx === activeSlide }"
          @click="activeSlide = idx"
        ></span>
      </div>
    </div>

    <!-- 推荐歌曲 -->
    <section class="section">
      <div class="section-header">
        <h3>推荐歌曲</h3>
        <span class="refresh-btn" @click="shuffleSongs">🔄 换一批</span>
      </div>
      <div class="song-scroll">
        <div
          v-for="song in randomSongs"
          :key="song.id"
          class="song-card"
          @dblclick="playSong(song)"
        >
          <div class="card-cover">
            <div class="cover-grad" :style="{ background: song.coverColor }">♪</div>
            <div class="play-overlay">▶</div>
          </div>
          <p class="card-title">{{ song.title }}</p>
          <p class="card-artist">{{ song.artist }}</p>
        </div>
      </div>
    </section>

    <!-- 推荐歌单 -->
    <section class="section">
      <div class="section-header">
        <h3>推荐歌单</h3>
        <span class="more">更多 ›</span>
      </div>
      <div class="playlist-grid">
        <div
          v-for="pl in playlists"
          :key="pl.id"
          class="playlist-card"
          @click="router.push({ name: 'playlist', params: { id: pl.id } })"
        >
          <div class="pl-cover">
            <div class="cover-inner" :style="{ background: pl.color }">♪</div>
            <span class="pl-count">{{ pl.count }}首</span>
          </div>
          <p class="pl-name">{{ pl.name }}</p>
        </div>
      </div>
    </section>
    </template>
  </div>
</template>

<style scoped>
.home { padding-bottom: 20px; }

/* ===== 顶部搜索栏 + 用户信息 ===== */
.top-bar {
  padding: 32px 32px 36px; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px;
}
.search-area { position: relative; flex: 1; max-width: 560px; }

.search-box {
  display: flex; align-items: center;
  width: 100%; padding: 14px 20px;
  background: #1e2024; border-radius: 24px; border: 1px solid transparent;
  transition: .2s;
}
.search-box:focus-within { border-color: #31c27c; background: #222; }
.search-icon { font-size: 16px; margin-right: 10px; opacity: .5; flex-shrink: 0; }
.search-input {
  flex: 1; border: none; background: none; color: #ddd;
  font-size: 15px; outline: none;
}
.search-input::placeholder { color: #555; }
.search-clear {
  background: none; border: none; color: #666; font-size: 14px; cursor: pointer;
  padding: 2px 6px; border-radius: 50%; flex-shrink: 0;
}
.search-clear:hover { color: #fff; background: rgba(255,255,255,.08); }

/* 搜索结果下拉 */
.search-dropdown {
  position: absolute; top: 56px; left: 0; right: 0;
  background: #1a1d22; border: 1px solid #2a2a2a;
  border-radius: 12px; overflow: hidden; z-index: 50;
  max-height: 400px; display: flex; flex-direction: column;
  box-shadow: 0 8px 32px rgba(0,0,0,.4);
}
.search-status { padding: 32px; text-align: center; color: #666; font-size: 14px; }
.search-result-list { overflow-y: auto; }
.result-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 18px; border-bottom: 1px solid #2a2a2a;
  font-size: 12px; color: #666;
}
.result-clear {
  background: none; border: 1px solid #444; color: #888; font-size: 11px;
  padding: 4px 12px; border-radius: 12px; cursor: pointer;
}
.result-clear:hover { border-color: #666; color: #ccc; }

.result-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 18px; cursor: pointer; transition: .12s;
}
.result-item:nth-child(even) { background: rgba(255,255,255,.015); }
.result-item:hover { background: rgba(255,255,255,.05); }

.ri-index { width: 24px; text-align: center; font-size: 13px; color: #555; }
.ri-cover {
  width: 36px; height: 36px; border-radius: 6px;
  background: #2a2a3a; display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: rgba(255,255,255,.3);
}
.ri-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ri-title { font-size: 14px; color: #ddd; }
.ri-meta { font-size: 12px; color: #555; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ri-time { font-size: 12px; color: #555; }

/* 下拉动画 */
.dropdown-enter-active, .dropdown-leave-active { transition: all .2s ease; }
.dropdown-enter-from, .dropdown-leave-to { opacity: 0; transform: translateY(-8px); }

.user-info {
  display: flex; align-items: center; gap: 10px; cursor: pointer; flex-shrink: 0;
}
.user-avatar {
  width: 40px; height: 40px; border-radius: 50%;
  background: #2a2a2a;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
}
.user-avatar:hover { background: #333; }
.user-name { font-size: 15px; color: #ccc; }

/* ===== Banner ===== */
.banner {
  position: relative; height: 300px; overflow: hidden;
  margin: 0 32px 32px; border-radius: 14px;
}
.banner-slide {
  position: absolute; inset: 0; opacity: 0; transform: scale(0.96);
  transition: all .6s ease; cursor: pointer;
}
.banner-slide.active { opacity: 1; transform: scale(1); }
.slide-bg {
  width: 100%; height: 100%; border-radius: 14px;
  display: flex; align-items: center; padding: 0 48px;
}
.slide-emoji {
  position: absolute; right: 56px; top: 50%;
  transform: translateY(-50%); font-size: 90px; opacity: .25;
}
.slide-text { position: absolute; left: 48px; top: 50%; transform: translateY(-50%); }
.slide-text h2 {
  font-size: 36px; font-weight: 800; color: #fff; margin-bottom: 8px;
}
.slide-text p { font-size: 17px; color: rgba(255,255,255,.7); }

.banner-dots {
  position: absolute; bottom: 18px; right: 28px; display: flex; gap: 10px;
}
.dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: rgba(255,255,255,.3); cursor: pointer; transition: .2s;
}
.dot.active { background: #31c27c; width: 24px; border-radius: 5px; }

/* ===== Section 通用 ===== */
.section { padding: 0 32px; margin-bottom: 40px; }
.section-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 20px;
}
.section-header h3 { font-size: 20px; font-weight: 700; color: #eee; }
.refresh-btn, .more {
  font-size: 14px; color: #666; cursor: pointer; transition: .2s;
}
.refresh-btn:hover, .more:hover { color: #31c27c; }

/* ===== 推荐歌曲 ===== */
.song-scroll {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
}
.song-card { cursor: pointer; text-align: center; }
.song-card:hover .play-overlay { opacity: 1; }
.card-cover {
  position: relative; padding-bottom: 100%;
  border-radius: 10px; overflow: hidden; margin-bottom: 10px;
}
.cover-grad {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 42px; color: rgba(255,255,255,.35);
}
.play-overlay {
  position: absolute; bottom: 10px; right: 10px;
  width: 38px; height: 38px; border-radius: 50%;
  background: rgba(49, 194, 124, .85);
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #fff; opacity: 0; transition: .2s;
}
.card-title {
  font-size: 15px; color: #ddd; margin-bottom: 4px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.card-artist {
  font-size: 13px; color: #666;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

/* ===== 推荐歌单 ===== */
.playlist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
}
.playlist-card { cursor: pointer; }
.pl-cover {
  position: relative; padding-bottom: 100%;
  border-radius: 10px; overflow: hidden; margin-bottom: 10px;
}
.cover-inner {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 42px; color: rgba(255,255,255,.3);
  transition: transform .3s;
}
.playlist-card:hover .cover-inner { transform: scale(1.05); }
.pl-count {
  position: absolute; top: 10px; right: 10px;
  padding: 3px 10px; border-radius: 4px;
  background: rgba(0,0,0,.55); font-size: 13px; color: #bbb;
}
.pl-name {
  font-size: 15px; color: #ccc;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden; line-height: 1.4;
}
</style>
