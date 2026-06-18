<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import { getPlaylists, createPlaylist } from '@/api/song'

const router = useRouter()
const playlists = ref([])
const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const creating = ref(false)

async function loadPlaylists() {
  try {
    const res = await getPlaylists()
    playlists.value = res.data || []
  } catch (e) { playlists.value = [] }
}

async function handleCreate() {
  const name = newName.value.trim()
  if (!name || creating.value) return
  creating.value = true
  try {
    await createPlaylist(name, newDesc.value.trim())
    showCreate.value = false
    newName.value = ''
    newDesc.value = ''
    loadPlaylists()
  } catch (e) {
    console.error('创建失败:', e)
  } finally {
    creating.value = false
  }
}

onMounted(() => loadPlaylists())
</script>

<template>
  <TopBar />
  <div class="playlists-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">📂 我的歌单</h2>
        <p class="subtitle">{{ playlists.length }} 个歌单</p>
      </div>
      <button class="btn-create" @click="showCreate = true">+ 创建歌单</button>
    </div>

    <div v-if="playlists.length > 0" class="playlist-grid">
      <div
        v-for="pl in playlists" :key="pl.id"
        class="playlist-card"
        @click="router.push({ name: 'playlist', params: { id: pl.id } })"
      >
        <div class="pl-cover">
          <div class="cover-inner" :style="{ background: '#31c27c' }">♪</div>
          <span class="pl-count">{{ pl.songCount }}首</span>
        </div>
        <p class="pl-name">{{ pl.name }}</p>
        <p v-if="pl.description" class="pl-desc">{{ pl.description }}</p>
      </div>
    </div>

    <div v-else class="empty">
      <p>还没有歌单</p>
      <p class="hint">点击上方"创建歌单"按钮，或在搜索歌曲时添加到歌单</p>
    </div>

    <!-- 创建歌单弹窗 -->
    <div v-if="showCreate" class="overlay" @click.self="showCreate = false">
      <div class="dialog">
        <h3>新建歌单</h3>
        <label>歌单名称</label>
        <input v-model="newName" placeholder="给歌单取个名字" class="inp" @keyup.enter="handleCreate" />
        <label>描述（可选）</label>
        <input v-model="newDesc" placeholder="简单描述一下" class="inp" />
        <div class="dialog-acts">
          <button class="btn-cancel" @click="showCreate = false">取消</button>
          <button class="btn-ok" :disabled="!newName.trim() || creating" @click="handleCreate">
            {{ creating ? '创建中...' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playlists-page { padding: 32px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 28px; }
.page-title { font-size: 24px; font-weight: 700; color: #1a1a1a; margin-bottom: 4px; }
.subtitle { font-size: 13px; color: #999; }
.btn-create {
  padding: 10px 24px; background: #31c27c; color: #fff; border: none;
  border-radius: 20px; font-size: 14px; cursor: pointer; font-weight: 600;
}
.btn-create:hover { background: #28a86b; }

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
  font-size: 15px; color: #1a1a1a;
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden;
}
.pl-desc {
  font-size: 12px; color: #999; margin-top: 4px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.empty { text-align: center; padding: 80px 0; color: #999; }
.hint { font-size: 13px; margin-top: 8px; }

.overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 200;
}
.dialog {
  background: #fff; border-radius: 14px; padding: 24px; width: 400px;
  box-shadow: 0 12px 48px rgba(0,0,0,.15);
}
.dialog h3 { font-size: 18px; margin-bottom: 16px; color: #333; }
.dialog label { display: block; font-size: 13px; color: #666; margin: 10px 0 4px; }
.inp {
  width: 100%; padding: 10px 14px; border: 1px solid #e0e0e0; border-radius: 8px;
  font-size: 14px; outline: none; box-sizing: border-box;
}
.inp:focus { border-color: #31c27c; }
.dialog-acts { display: flex; gap: 10px; justify-content: flex-end; margin-top: 20px; }
.btn-cancel {
  padding: 8px 20px; border: 1px solid #ccc; border-radius: 16px;
  background: none; color: #666; font-size: 13px; cursor: pointer;
}
.btn-ok {
  padding: 8px 24px; background: #31c27c; color: #fff; border: none;
  border-radius: 16px; font-size: 13px; cursor: pointer; font-weight: 600;
}
.btn-ok:disabled { opacity: .5; cursor: not-allowed; }
.btn-ok:not(:disabled):hover { background: #28a86b; }
</style>
