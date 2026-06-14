<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { searchSongs } from '@/api/song'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const player = usePlayerStore()
const authStore = useAuthStore()
const searchKeyword = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const searchFocused = ref(false)
const showDropdown = ref(false)

async function doSearch() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) { searchResults.value = []; showDropdown.value = false; return }
  searchLoading.value = true; showDropdown.value = true
  try { const res = await searchSongs(keyword); searchResults.value = res.data || []; }
  catch { searchResults.value = []; }
  finally { searchLoading.value = false }
}

function onInput() {
  if (searchKeyword.value.trim() === '') { searchResults.value = []; showDropdown.value = false }
}
function onBlur() { searchFocused.value = false; setTimeout(() => { showDropdown.value = false }, 200) }
function onFocus() { searchFocused.value = true; if (searchKeyword.value.trim() && searchResults.value.length > 0) showDropdown.value = true }
function clearSearch() { searchKeyword.value = ''; searchResults.value = []; showDropdown.value = false }

function playSong(song) {
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '')
}

function formatDuration(s) {
  if (!s) return ''; const sec = parseInt(s); return Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0')
}
</script>

<template>
  <div class="top-bar">
    <div class="search-area">
      <div class="search-box">
        <svg class="search-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input
          v-model="searchKeyword"
          @focus="onFocus"
          @blur="onBlur"
          @keyup.enter="doSearch"
          @input="onInput"
          placeholder="搜索歌曲"
          class="search-input"
        />
        <button v-if="searchKeyword" class="search-clear" @click.stop="clearSearch">✕</button>
      </div>

      <Transition name="dropdown">
        <div v-if="showDropdown" class="search-dropdown">
          <div v-if="searchLoading" class="drop-loading">搜索中...</div>
          <div v-else-if="searchResults.length === 0" class="drop-empty">未找到相关歌曲</div>
          <div v-else class="drop-list">
            <div
              v-for="(song, idx) in searchResults.slice(0, 5)"
              :key="song.sourceId"
              class="drop-item"
              @mousedown.prevent="playSong(song); router.push('/')"
            >
              <div class="drop-cover" :style="song.coverUrl ? { backgroundImage: 'url(' + song.coverUrl + '?param=60y60)' } : {}">
                <span v-if="!song.coverUrl">♪</span>
                <div class="drop-play-icon">▶</div>
              </div>
              <div class="drop-info">
                <span class="drop-name">{{ song.name }}</span>
                <span class="drop-meta">{{ song.artist }} | {{ formatDuration(song.duration) }}</span>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </div>

    <div class="user-info">
      <div class="user-avatar">
        <img v-if="authStore.avatarSrc" :src="authStore.avatarSrc" class="avatar-img" />
        <span v-else>👤</span>
      </div>
      <span class="user-name">{{ authStore.user?.nickname || '音乐爱好者' }}</span>
    </div>
  </div>
</template>

<style scoped>
.top-bar {
  padding: 32px 32px 36px; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px;
}
.search-area { position: relative; flex: 1; max-width: 560px; }
.search-box {
  display: flex; align-items: center;
  width: 100%; padding: 14px 20px;
  background: #fff; border-radius: 24px; border: 1px solid #e0e0e0;
  transition: .2s;
}
.search-box:focus-within { border-color: #31c27c; background: #fff; }
.search-icon { font-size: 16px; margin-right: 10px; opacity: .5; flex-shrink: 0; }
.search-input {
  flex: 1; border: none; background: none; color: #333;
  font-size: 15px; outline: none;
}
.search-input::placeholder { color: #bbb; }
.search-clear {
  background: none; border: none; color: #999; font-size: 14px; cursor: pointer;
  padding: 2px 6px; border-radius: 50%; flex-shrink: 0;
}
.search-clear:hover { color: #333; background: rgba(0,0,0,.06); }

.search-dropdown {
  position: absolute; top: 50px; left: 0; right: 0;
  background: #fff; border: 1px solid #e0e0e0;
  border-radius: 12px; overflow: hidden; z-index: 50;
  max-height: 360px; overflow-y: auto;
  box-shadow: 0 8px 32px rgba(0,0,0,.1);
}
.drop-loading, .drop-empty { padding: 28px; text-align: center; color: #999; font-size: 14px; }
.drop-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; cursor: pointer; transition: .12s;
}
.drop-item:hover { background: #f5f5f5; }
.drop-cover {
  width: 40px; height: 40px; border-radius: 6px; flex-shrink: 0; position: relative;
  background: #e0e0e0; display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #999;
  background-size: cover; background-position: center;
}
.drop-play-icon {
  position: absolute; inset: 0; border-radius: 6px;
  background: rgba(0,0,0,.5); display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: #31c27c; opacity: 0; transition: .15s;
}
.drop-item:hover .drop-play-icon { opacity: 1; }
.drop-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.drop-name { font-size: 14px; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.drop-meta { font-size: 12px; color: #999; }

.dropdown-enter-active, .dropdown-leave-active { transition: all .2s ease; }
.dropdown-enter-from, .dropdown-leave-to { opacity: 0; transform: translateY(-6px); }

.user-info { display: flex; align-items: center; gap: 10px; cursor: pointer; flex-shrink: 0; }
.user-avatar {
  width: 40px; height: 40px; border-radius: 50%; overflow: hidden;
  background: #e8e8e8; display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0;
}
.avatar-img { width: 100%; height: 100%; object-fit: cover; }
.user-name { font-size: 15px; color: #444; }
</style>
