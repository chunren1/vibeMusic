<script setup>
import { API_HOST } from '@/api/request'
import { ref, watch, nextTick } from 'vue'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['close'])

const queue = ref([])
const currentIdx = ref(-1)

function loadQueue() {
  try {
    queue.value = JSON.parse(localStorage.getItem('vibe_queue') || '[]')
    currentIdx.value = parseInt(localStorage.getItem('vibe_queue_idx') || '-1')
  } catch { queue.value = []; currentIdx.value = -1 }
}

watch(() => props.visible, val => {
  if (val) loadQueue()
})

function playSong(song) {
  const idx = queue.value.indexOf(song)
  if (idx === -1) return
  localStorage.setItem('vibe_queue_idx', String(idx))
  const a = window.vibeAudio
  a.src = `${API_HOST}/api/songs/stream?sourceId=${encodeURIComponent(song.sourceId)}`
  a.play().catch(() => {})
  const info = { sourceId: song.sourceId, title: song.name, artist: song.artist, coverUrl: song.coverUrl || '' }
  localStorage.setItem('vibe_current_song', JSON.stringify({ id: song.sourceId, title: song.name, artist: song.artist, coverUrl: song.coverUrl || '' }))
  window.dispatchEvent(new CustomEvent('song-change', { detail: info }))
  emit('close')
}

function removeSong(idx, e) {
  e.stopPropagation()
  queue.value.splice(idx, 1)
  if (currentIdx.value >= queue.value.length) currentIdx.value = queue.value.length - 1
  if (idx === currentIdx.value && queue.value.length > 0) playSong(queue.value[currentIdx.value])
  localStorage.setItem('vibe_queue', JSON.stringify(queue.value))
  localStorage.setItem('vibe_queue_idx', String(currentIdx.value))
}

function clearAll() {
  queue.value = []
  currentIdx.value = -1
  localStorage.setItem('vibe_queue', '[]')
  localStorage.setItem('vibe_queue_idx', '-1')
  window.vibeAudio.pause()
  window.vibeAudio.src = ''
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="qslide">
      <div v-if="visible" class="q-overlay" @click="emit('close')">
        <div class="q-panel" @click.stop>
          <!-- 把手 -->
          <div class="q-handle"></div>

          <!-- 标题栏 -->
          <div class="q-header">
            <span class="q-title">播放列表 ({{ queue.length }})</span>
            <button class="q-clear" @click="clearAll">清空</button>
          </div>

          <!-- 列表 -->
          <div class="q-list">
            <div v-if="!queue.length" class="q-empty">列表为空</div>
            <div
              v-for="(s, i) in queue" :key="s.sourceId + '-' + i"
              class="q-item"
              :class="{ current: i === currentIdx }"
              @click="playSong(s)"
            >
              <span class="q-idx">
                <span v-if="i === currentIdx" class="q-eq"><i></i><i></i><i></i></span>
                <span v-else>{{ i + 1 }}</span>
              </span>
              <div class="q-cover" :style="s.coverUrl ? { backgroundImage: `url(${s.coverUrl}?param=60y60)` } : {}"></div>
              <div class="q-info">
                <div class="q-name">{{ s.name }}</div>
                <div class="q-artist">{{ s.artist }}</div>
              </div>
              <button class="q-remove" @click="removeSong(i, $event)">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.q-overlay {
  position: fixed; inset: 0; z-index: 200;
  background: rgba(0,0,0,0.5);
  display: flex; align-items: flex-end;
}
.q-panel {
  width: 100%; max-height: 60vh;
  background: #1a1a1a; border-radius: 16px 16px 0 0;
  display: flex; flex-direction: column;
}
.q-handle {
  width: 36px; height: 4px; border-radius: 2px;
  background: rgba(255,255,255,0.2); margin: 10px auto 0;
}
.q-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px 8px;
}
.q-title { font-size: 16px; font-weight: 600; color: #e0e0e0; }
.q-clear {
  border: none; background: none; color: #888; font-size: 13px; cursor: pointer;
}
.q-list { flex: 1; overflow-y: auto; padding: 0 4px 16px; }
.q-empty { text-align: center; padding: 40px 0; color: #666; font-size: 14px; }
.q-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border-radius: 10px; cursor: pointer;
}
.q-item:active { background: rgba(255,255,255,0.03); }
.q-item.current { background: rgba(49,194,124,0.06); }
.q-idx {
  width: 24px; text-align: center; font-size: 12px; color: #666; flex-shrink: 0;
}
.q-eq { display: flex; align-items: flex-end; gap: 1px; height: 12px; justify-content: center; }
.q-eq i { width: 2px; background: #31c27c; border-radius: 1px; animation: eq .6s ease-in-out infinite alternate; }
.q-eq i:nth-child(1) { height: 6px; }
.q-eq i:nth-child(2) { height: 10px; animation-delay: .15s; }
.q-eq i:nth-child(3) { height: 4px; animation-delay: .3s; }
@keyframes eq { to { height: 2px; } }
.q-cover {
  width: 36px; height: 36px; border-radius: 6px; flex-shrink: 0;
  background: rgba(255,255,255,0.06) center/cover no-repeat;
}
.q-info { flex: 1; min-width: 0; }
.q-name { font-size: 14px; color: #e0e0e0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.q-artist { font-size: 11px; color: #888; margin-top: 2px; }
.q-remove {
  border: none; background: none; color: #555; font-size: 16px; cursor: pointer;
  padding: 4px; flex-shrink: 0;
}
.q-remove:active { color: #ec4141; }

/* 动画 */  
.qslide-enter-active, .qslide-leave-active { transition: opacity .25s; }
.qslide-enter-from, .qslide-leave-to { opacity: 0; }
.qslide-enter-active .q-panel, .qslide-leave-active .q-panel { transition: transform .25s ease; }
.qslide-enter-from .q-panel, .qslide-leave-to .q-panel { transform: translateY(100%); }
</style>
