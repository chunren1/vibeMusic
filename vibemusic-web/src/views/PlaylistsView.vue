<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
import {
  getPlaylists, createPlaylist, deletePlaylistsBatch,
  updatePlaylist, reorderPlaylists, exportPlaylist
} from '@/api/song'

const router = useRouter()
const playlists = ref([])
const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const creating = ref(false)

// 编辑
const editing = ref(false)
const editId = ref(null)
const editName = ref('')
const editDesc = ref('')

// 批量管理
const manageMode = ref(false)
const selectedIds = ref(new Set())
const removing = ref(false)

async function loadPlaylists() {
  try {
    const res = await getPlaylists()
    playlists.value = (res.data || []).map((p, i) => ({ ...p, _sortIndex: i }))
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

// 编辑歌单
function openEdit(pl, e) {
  e.stopPropagation()
  editId.value = pl.id
  editName.value = pl.name
  editDesc.value = pl.description || ''
  editing.value = true
}

async function handleEdit() {
  if (!editName.value.trim()) return
  try {
    await updatePlaylist(editId.value, editName.value.trim(), editDesc.value.trim(), null)
    editing.value = false
    window.toast?.('已更新', 'success')
    loadPlaylists()
  } catch { window.toast?.('更新失败', 'error') }
}

// 排序
async function moveToTop(pl, e) {
  e.stopPropagation()
  try {
    const order = playlists.value.map((p, i) => ({
      playlistId: p.id,
      sortOrder: p.id === pl.id ? 0 : (i + 1)
    }))
    await reorderPlaylists(order)
    window.toast?.('已置顶', 'success')
    loadPlaylists()
  } catch { window.toast?.('操作失败', 'error') }
}

async function moveUp(pl, e) {
  e.stopPropagation()
  const idx = playlists.value.findIndex(p => p.id === pl.id)
  if (idx <= 0) return
  const order = playlists.value.map((p, i) => ({
    playlistId: p.id,
    sortOrder: i === idx ? idx - 1 : i === idx - 1 ? idx : i
  }))
  try {
    await reorderPlaylists(order)
    loadPlaylists()
  } catch { window.toast?.('操作失败', 'error') }
}

async function moveDown(pl, e) {
  e.stopPropagation()
  const idx = playlists.value.findIndex(p => p.id === pl.id)
  if (idx >= playlists.value.length - 1) return
  const order = playlists.value.map((p, i) => ({
    playlistId: p.id,
    sortOrder: i === idx ? idx + 1 : i === idx + 1 ? idx : i
  }))
  try {
    await reorderPlaylists(order)
    loadPlaylists()
  } catch { window.toast?.('操作失败', 'error') }
}

// 导出
async function handleExport(pl, e) {
  e.stopPropagation()
  try {
    const res = await exportPlaylist(pl.id)
    const blob = new Blob([JSON.stringify(res.data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${pl.name}.json`
    a.click()
    URL.revokeObjectURL(url)
    window.toast?.('已导出', 'success')
  } catch { window.toast?.('导出失败', 'error') }
}

function toggleManage() {
  manageMode.value = !manageMode.value
  selectedIds.value = new Set()
}

function toggleSelect(id) {
  const s = new Set(selectedIds.value)
  s.has(id) ? s.delete(id) : s.add(id)
  selectedIds.value = s
}

function goPlaylist(pl) {
  if (manageMode.value) return
  router.push({ name: 'playlist', params: { id: pl.id } })
}

async function doBatchDelete() {
  if (removing.value || !selectedIds.value.size) return
  removing.value = true
  try {
    await deletePlaylistsBatch([...selectedIds.value])
    playlists.value = playlists.value.filter(pl => !selectedIds.value.has(pl.id))
    selectedIds.value = new Set()
    manageMode.value = false
    window.toast?.('已删除', 'success')
  } catch { window.toast?.('操作失败', 'error') }
  finally { removing.value = false }
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
      <div class="header-actions">
        <button class="btn-manage" @click="toggleManage">{{ manageMode ? '完成' : '管理' }}</button>
        <button v-if="!manageMode" class="btn-create" @click="showCreate = true">+ 创建歌单</button>
      </div>
    </div>

    <div v-if="playlists.length > 0" class="playlist-grid">
      <div
        v-for="pl in playlists" :key="pl.id"
        class="playlist-card"
        :class="{ selected: manageMode && selectedIds.has(pl.id) }"
        @click="manageMode ? toggleSelect(pl.id) : goPlaylist(pl)"
      >
        <div v-if="manageMode" class="card-check">
          <svg v-if="selectedIds.has(pl.id)" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#31c27c" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>
          <svg v-else viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#999" stroke-width="1.5"><circle cx="12" cy="12" r="10"/></svg>
        </div>
        <div v-if="!manageMode" class="card-actions">
          <button class="ca-btn" title="编辑" @click="openEdit(pl, $event)">✏️</button>
          <button class="ca-btn" title="置顶" @click="moveToTop(pl, $event)">⬆</button>
          <button class="ca-btn" title="导出" @click="handleExport(pl, $event)">📥</button>
        </div>
        <div class="pl-cover">
          <img v-if="pl.coverUrl" :src="pl.coverUrl + '?param=200y200'" class="pl-cover-img" alt="" loading="lazy" />
          <div v-else class="cover-inner" :style="{ background: '#31c27c' }">♪</div>
          <span class="pl-count">{{ pl.songCount }}首</span>
        </div>
        <p class="pl-name">{{ pl.name }}</p>
        <p v-if="pl.description" class="pl-desc">{{ pl.description }}</p>
      </div>
    </div>

    <!-- 批量删除栏 -->
    <div v-if="manageMode && selectedIds.size" class="batch-bar">
      <button class="batch-btn" :disabled="removing" @click="doBatchDelete">
        {{ removing ? '删除中...' : `删除 (${selectedIds.size})` }}
      </button>
    </div>

    <div v-else-if="!playlists.length" class="empty">
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

    <!-- 编辑歌单弹窗 -->
    <div v-if="editing" class="overlay" @click.self="editing = false">
      <div class="dialog">
        <h3>编辑歌单</h3>
        <label>歌单名称</label>
        <input v-model="editName" placeholder="歌单名称" class="inp" @keyup.enter="handleEdit" />
        <label>描述</label>
        <input v-model="editDesc" placeholder="歌单描述" class="inp" />
        <div class="dialog-acts">
          <button class="btn-cancel" @click="editing = false">取消</button>
          <button class="btn-ok" :disabled="!editName.trim()" @click="handleEdit">保存</button>
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
.header-actions { display: flex; gap: 10px; align-items: center; }
.btn-manage {
  padding: 10px 20px; border: 1px solid #ccc; border-radius: 20px;
  background: transparent; color: #666; font-size: 14px; cursor: pointer;
}
.btn-manage:hover { border-color: #31c27c; color: #31c27c; }
.btn-create {
  padding: 10px 24px; background: #31c27c; color: #fff; border: none;
  border-radius: 20px; font-size: 14px; cursor: pointer; font-weight: 600;
}
.btn-create:hover { background: #28a86b; }

.playlist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 20px;
  padding-bottom: 80px;
}
.playlist-card { cursor: pointer; position: relative; }
.playlist-card.selected { opacity: .7; }
.playlist-card.selected::after {
  content: ''; position: absolute; inset: -4px; border-radius: 14px;
  border: 2px solid #31c27c; pointer-events: none;
}
.card-check {
  position: absolute; top: 8px; right: 8px; z-index: 2;
  background: #fff; border-radius: 50%; width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 2px 6px rgba(0,0,0,.1);
}
.card-actions {
  position: absolute; top: 6px; right: 6px; z-index: 2;
  display: flex; gap: 4px; opacity: 0; transition: opacity .2s;
}
.playlist-card:hover .card-actions { opacity: 1; }
.ca-btn {
  width: 28px; height: 28px; border: none; border-radius: 50%;
  background: rgba(0,0,0,.55); color: #fff; font-size: 13px;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: background .15s;
}
.ca-btn:hover { background: rgba(0,0,0,.8); }
.pl-cover {
  position: relative; padding-bottom: 100%;
  border-radius: 10px; overflow: hidden; margin-bottom: 10px;
}
.pl-cover-img {
  position: absolute; inset: 0;
  width: 100%; height: 100%; object-fit: cover;
  border-radius: 10px;
  transition: transform .3s;
}
.playlist-card:hover .pl-cover-img { transform: scale(1.05); }
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

.batch-bar {
  position: fixed; bottom: 80px; left: 0; right: 0; z-index: 50;
  display: flex; justify-content: center; padding: 12px;
  background: #fff; border-top: 1px solid #eee;
}
.batch-btn {
  padding: 10px 36px; border-radius: 22px;
  border: 1px solid #e0e0e0; background: transparent;
  color: #e04040; font-size: 14px; cursor: pointer;
}
.batch-btn:hover { border-color: #e04040; }
.batch-btn:disabled { opacity: .4; }
</style>
