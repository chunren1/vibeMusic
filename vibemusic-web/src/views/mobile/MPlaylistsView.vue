<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { createPlaylist, getPlaylists, deletePlaylistsBatch } from '@/api/song'

const router = useRouter()
const playlists = ref([])
const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const creating = ref(false)

// 批量管理
const manageMode = ref(false)
const selectedIds = ref(new Set())
const removing = ref(false)

function loadPlaylists() {
  getPlaylists().then(r => { playlists.value = r.data || [] }).catch(() => {})
}

function doCreate() {
  if (!newName.value.trim() || creating.value) return
  creating.value = true
  createPlaylist(newName.value.trim(), newDesc.value.trim()).then(() => {
    showCreate.value = false; newName.value = ''; newDesc.value = ''; loadPlaylists()
  }).catch(() => {}).finally(() => { creating.value = false })
}

function toggleManage() {
  manageMode.value = !manageMode.value
  selectedIds.value = new Set()
}

function toggleSelect(id) {
  const s = new Set(selectedIds.value)
  if (s.has(id)) {
    s.delete(id)
  } else {
    s.add(id)
  }
  selectedIds.value = s
}

function goPlaylist(pl) {
  if (manageMode.value) return
  router.push({ name: 'm-playlist', params: { id: pl.id } })
}

async function doBatchDelete() {
  if (removing.value || !selectedIds.value.size) return
  removing.value = true
  try {
    const ids = [...selectedIds.value]
    await deletePlaylistsBatch(ids)
    playlists.value = playlists.value.filter(pl => !selectedIds.value.has(pl.id))
    selectedIds.value = new Set()
    manageMode.value = false
    window.toast?.('已删除 ' + ids.length + ' 个歌单', 'success')
  } catch {
    window.toast?.('操作失败', 'error')
  } finally { removing.value = false }
}

onMounted(loadPlaylists)
</script>

<template>
  <div class="m-page">
    <div class="m-header">
      <h2 class="m-title">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;margin-right:6px"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
        我的歌单
      </h2>
      <div class="m-header-actions">
        <button v-if="!manageMode" class="m-manage-btn" @click="toggleManage">管理</button>
        <button v-else class="m-manage-btn active" @click="toggleManage">完成</button>
        <button v-if="!manageMode" class="m-add-btn" @click="showCreate = !showCreate">{{ showCreate ? '取消' : '+ 新建' }}</button>
      </div>
    </div>

    <!-- 创建歌单 -->
    <div v-if="showCreate && !manageMode" class="m-create">
      <input v-model="newName" placeholder="歌单名称" class="m-input" @keyup.enter="doCreate" />
      <input v-model="newDesc" placeholder="描述（可选）" class="m-input" />
      <button class="m-submit" @click="doCreate" :disabled="creating">{{ creating ? '创建中...' : '创建' }}</button>
    </div>

    <div class="m-grid">
      <div v-for="pl in playlists" :key="pl.id" class="m-card"
        :class="{ selected: manageMode && selectedIds.has(pl.id) }"
        @click="manageMode ? toggleSelect(pl.id) : goPlaylist(pl)">
        <div v-if="manageMode" class="m-card-check">
          <svg v-if="selectedIds.has(pl.id)" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#31c27c" stroke-width="2"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
          <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="rgba(255,255,255,.2)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/></svg>
        </div>
        <div class="m-card-cover">
          <img v-if="pl.coverUrl" :src="pl.coverUrl + '?param=120y120'" class="m-card-cover-img" alt="" />
          <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
        </div>
        <div class="m-card-name">{{ pl.name }}</div>
        <div class="m-card-count">{{ pl.songCount || 0 }} 首</div>
      </div>
    </div>

    <!-- 批量删除栏 -->
    <div v-if="manageMode && selectedIds.size" class="m-batch-bar">
      <button class="m-batch-btn" :disabled="removing" @click="doBatchDelete">
        {{ removing ? '删除中...' : `删除 (${selectedIds.size})` }}
      </button>
    </div>

    <div v-if="!playlists.length" class="m-empty">
      <p>还没有歌单</p>
      <p class="m-hint">点击右上角「+ 新建」创建</p>
    </div>
  </div>
</template>

<style scoped>
.m-page { padding: 12px 16px; padding-bottom: 140px; }
.m-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.m-title { font-size: 20px; font-weight: 700; color: #e0e0e0; }
.m-header-actions { display: flex; gap: 8px; align-items: center; }
.m-manage-btn {
  padding: 5px 14px; border: 1px solid rgba(255,255,255,.15); border-radius: 16px;
  background: transparent; color: #777; font-size: 13px; cursor: pointer;
  transition: .15s;
}
.m-manage-btn.active { border-color: #31c27c; color: #31c27c; }
.m-add-btn {
  padding: 5px 14px; border: 1px solid #31c27c; border-radius: 16px;
  background: transparent; color: #31c27c; font-size: 13px; cursor: pointer;
}
.m-create {
  background: rgba(255,255,255,.04); border-radius: 10px; padding: 14px; margin-bottom: 16px;
  display: flex; flex-direction: column; gap: 10px;
}
.m-input {
  border: 1px solid rgba(255,255,255,.1); border-radius: 8px; padding: 8px 12px;
  background: rgba(255,255,255,.06); color: #e0e0e0; font-size: 14px; outline: none;
}
.m-submit {
  align-self: flex-start; padding: 6px 20px; border: none; border-radius: 8px;
  background: #31c27c; color: #fff; font-size: 13px; cursor: pointer;
}
.m-submit:disabled { opacity: .5; }
.m-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.m-card {
  background: rgba(255,255,255,.04); border-radius: 10px; padding: 16px; cursor: pointer;
  position: relative;
}
.m-card:active { background: rgba(255,255,255,.06); }
.m-card.selected { background: rgba(49,194,124,.06); }
.m-card-check {
  position: absolute; top: 8px; right: 8px; z-index: 2; opacity: .6;
}
.m-card-cover {
  width: 48px; height: 48px; border-radius: 10px; background: #31c27c;
  display: flex; align-items: center; justify-content: center; font-size: 22px; color: #fff; margin-bottom: 10px;
  overflow: hidden;
}
.m-card-cover-img {
  width: 100%; height: 100%; object-fit: cover; border-radius: 10px;
}
.m-card-name { font-size: 14px; color: #e0e0e0; font-weight: 500; }
.m-card-count { font-size: 11px; color: #888; margin-top: 4px; }
.m-empty { text-align: center; padding: 60px 0; color: #666; font-size: 14px; }
.m-hint { font-size: 12px; color: #444; margin-top: 6px; }

/* 批量操作底栏 */
.m-batch-bar {
  position: fixed; bottom: 116px; left: 0; right: 0; z-index: 50;
  display: flex; justify-content: center; gap: 12px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px));
  background: rgba(10,10,10,.95);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid rgba(255,255,255,.06);
}
.m-batch-btn {
  padding: 8px 28px; border-radius: 20px;
  border: 1px solid rgba(255,255,255,.12);
  background: transparent; color: #aaa; font-size: 13px; cursor: pointer;
  transition: .15s;
}
.m-batch-btn:active { border-color: #e04040; color: #e04040; }
.m-batch-btn:disabled { opacity: .4; }
</style>
