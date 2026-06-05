<script setup>
import { ref, computed, onMounted } from 'vue'
import { getPlaylists, createPlaylist, addToPlaylist } from '@/api/song'

const props = defineProps({
  song: { type: Object, required: true },
  excludePlaylistId: { type: Number, default: 0 },
})
const emit = defineEmits(['close', 'done'])

const allPlaylists = ref([])
const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const creating = ref(false)
const addingId = ref(null)
const message = ref('')

// 过滤当前歌单，避免重复添加
const playlists = computed(() => {
  if (props.excludePlaylistId) {
    return allPlaylists.value.filter(pl => pl.id !== props.excludePlaylistId)
  }
  return allPlaylists.value
})

onMounted(() => loadPlaylists())

async function loadPlaylists() {
  try {
    const res = await getPlaylists()
    allPlaylists.value = res.data || []
  } catch (e) {
    console.error('加载歌单失败:', e)
  }
}

async function handleCreate() {
  const name = newName.value.trim()
  if (!name) return
  creating.value = true
  try {
    const res = await createPlaylist(name, newDesc.value.trim())
    const pl = res.data
    // 创建后自动添加当前歌曲
    await addToPlaylist(pl.id, props.song)
    message.value = '已创建并添加'
    setTimeout(() => emit('done'), 600)
  } catch (e) {
    console.error('创建歌单失败:', e)
    message.value = '操作失败，请重试'
  } finally {
    creating.value = false
  }
}

async function handleAdd(pl) {
  addingId.value = pl.id
  try {
    await addToPlaylist(pl.id, props.song)
    message.value = '已添加到 ' + pl.name
    setTimeout(() => emit('done'), 600)
  } catch (e) {
    console.error('添加失败:', e)
    message.value = '操作失败，请重试'
  } finally {
    addingId.value = null
  }
}
</script>

<template>
  <div class="popup-overlay" @click.self="emit('close')">
    <div class="popup-card">
      <div class="popup-header">
        <span>添加到歌单</span>
        <button class="popup-close" @click="emit('close')">✕</button>
      </div>

      <div class="popup-body">
        <!-- 提示消息 -->
        <p v-if="message" class="popup-msg">{{ message }}</p>

        <!-- 无歌单状态 -->
        <div v-if="!showCreate && playlists.length === 0 && !message" class="empty-state">
          <p>还没有歌单</p>
          <button class="create-btn" @click="showCreate = true">+ 创建第一个歌单</button>
        </div>

        <!-- 歌单列表 -->
        <div v-if="!showCreate && playlists.length > 0" class="pl-list">
          <div
            v-for="pl in playlists" :key="pl.id"
            class="pl-item"
            :class="{ adding: addingId === pl.id }"
            @click="handleAdd(pl)"
          >
            <div class="pl-icon">📁</div>
            <div class="pl-info">
              <span class="pl-name">{{ pl.name }}</span>
              <span class="pl-count">{{ pl.songCount }} 首</span>
            </div>
            <span class="pl-add">+</span>
          </div>
        </div>

        <!-- 分割线 + 新建歌单入口 -->
        <div v-if="!showCreate" class="create-entry" @click="showCreate = true">
          + 新建歌单
        </div>

        <!-- 创建歌单表单 -->
        <div v-if="showCreate" class="create-form">
          <label>歌单名称</label>
          <input v-model="newName" placeholder="给歌单取个名字" class="form-input" />
          <label>描述（可选）</label>
          <input v-model="newDesc" placeholder="简单描述一下" class="form-input" />
          <div class="form-actions">
            <button class="btn-cancel" @click="showCreate = false">取消</button>
            <button class="btn-create" :disabled="!newName.trim() || creating" @click="handleCreate">
              {{ creating ? '创建中...' : '创建并添加' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.popup-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 200;
}
.popup-card {
  background: #fff; border-radius: 14px; width: 420px; max-height: 70vh;
  overflow: hidden; box-shadow: 0 12px 48px rgba(0,0,0,.15);
}
.popup-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 24px; border-bottom: 1px solid #eee;
  font-size: 16px; font-weight: 600; color: #333;
}
.popup-close { background: none; border: none; color: #999; font-size: 16px; cursor: pointer; }
.popup-close:hover { color: #333; }
.popup-body { padding: 16px 24px 24px; overflow-y: auto; max-height: 50vh; }
.popup-msg { text-align: center; color: #31c27c; font-size: 14px; padding: 16px 0; }

.empty-state { text-align: center; padding: 32px 0; }
.empty-state p { color: #999; font-size: 14px; margin-bottom: 16px; }
.create-btn {
  padding: 10px 24px; background: #31c27c; color: #fff; border: none;
  border-radius: 20px; font-size: 14px; cursor: pointer;
}
.create-btn:hover { background: #28a86b; }

.pl-item {
  display: flex; align-items: center; gap: 12px;
  padding: 12px; border-radius: 8px; cursor: pointer; transition: .12s;
}
.pl-item:hover { background: #f5f5f5; }
.pl-item.adding { opacity: .6; pointer-events: none; }
.pl-icon { font-size: 24px; }
.pl-info { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.pl-name { font-size: 14px; color: #333; }
.pl-count { font-size: 12px; color: #999; }
.pl-add { font-size: 18px; color: #31c27c; opacity: 0; transition: .15s; }
.pl-item:hover .pl-add { opacity: 1; }

.create-entry {
  text-align: center; padding: 14px 0; color: #31c27c;
  font-size: 14px; cursor: pointer; border-top: 1px solid #eee; margin-top: 8px;
}
.create-entry:hover { color: #28a86b; }

.create-form { display: flex; flex-direction: column; gap: 10px; }
.create-form label { font-size: 13px; color: #666; }
.form-input {
  padding: 10px 14px; border: 1px solid #e0e0e0; border-radius: 8px;
  font-size: 14px; outline: none;
}
.form-input:focus { border-color: #31c27c; }
.form-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 8px; }
.btn-cancel {
  padding: 8px 20px; border: 1px solid #ccc; border-radius: 16px;
  background: none; color: #666; font-size: 13px; cursor: pointer;
}
.btn-create {
  padding: 8px 20px; border: none; border-radius: 16px;
  background: #31c27c; color: #fff; font-size: 13px; cursor: pointer;
}
.btn-create:disabled { opacity: .5; cursor: not-allowed; }
.btn-create:not(:disabled):hover { background: #28a86b; }
</style>
