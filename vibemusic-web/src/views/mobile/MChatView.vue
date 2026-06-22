<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { usePlayerStore } from '@/stores/player'

const router = useRouter()
const player = usePlayerStore()

const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const chatBox = ref(null)

const greeting = '嗨！我是 vibe 音乐精灵 🎵 想听什么歌？告诉我吧～'

function scrollBottom() {
  nextTick(() => { if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight })
}

const hints = ['推荐几首周杰伦的歌', '治愈系音乐', '最近热门', '适合跑步的歌']

function sendHint(h) { inputText.value = h; doSend() }

async function doSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  inputText.value = ''

  messages.value.push({ role: 'user', content: text })
  const aiMsg = { role: 'ai', content: '', thinking: true, songs: [] }
  messages.value.push(aiMsg)
  scrollBottom()
  loading.value = true

  try {
    const res = await request.post('/assistant/chat', { message: text, context: '' })
    aiMsg.content = res.data.reply || '让我想想...'
    aiMsg.songs = res.data.songs || []
  } catch {
    aiMsg.content = '网络不太稳，再试一次～'
  } finally {
    aiMsg.thinking = false
    loading.value = false
    scrollBottom()
  }
}

function playSong(song) {
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '', song.platform)
}

function fmtSec(s) { if (!s) return ''; const m = Math.floor(s / 60); return m + ':' + String(s % 60).padStart(2, '0') }

onMounted(() => {
  messages.value.push({ role: 'ai', content: greeting })
  scrollBottom()
})
</script>

<template>
  <div class="m-chat">
    <!-- 导航 -->
    <div class="m-nav">
      <button class="m-back" @click="router.back()">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <span class="m-title">vibe 音乐精灵</span>
    </div>

    <!-- 消息列表 -->
    <div class="m-msgs" ref="chatBox">
      <div v-for="(msg, idx) in messages" :key="idx" class="m-msg" :class="msg.role">
        <div v-if="msg.role === 'ai'" class="m-ai">
          <div class="m-avatar">🎵</div>
          <div class="m-bubble ai">
            <!-- 思考过程 -->
            <div v-if="msg.thinking" class="thinking-line">
              🎵 正在思考...
              <span class="think-dot" v-for="i in 3" :key="i" :style="{ animationDelay: (i-1)*0.2+'s' }">●</span>
            </div>
            <p v-if="msg.content">{{ msg.content }}</p>
            <!-- 歌曲卡片 -->
            <div v-if="msg.songs && msg.songs.length" class="m-cards">
              <div class="m-cards-label">🎶 为你找到：</div>
              <div
                v-for="song in msg.songs" :key="song.sourceId"
                class="m-card" @click="playSong(song)"
                :class="{ playing: player.currentSong?.id === song.sourceId && player.isPlaying }"
              >
                <div class="mc-cover">
                  <img v-if="song.coverUrl" :src="song.coverUrl + '?param=80y80'" loading="lazy" />
                  <span v-else>♪</span>
                </div>
                <div class="mc-info">
                  <div class="mc-name">{{ song.name }}</div>
                  <div class="mc-artist">{{ song.artist }}</div>
                </div>
                <span class="mc-time">{{ fmtSec(song.duration) }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="m-bubble user"><p>{{ msg.content }}</p></div>
      </div>
    </div>

    <!-- 快捷提示 -->
    <div v-if="messages.length <= 1" class="m-hints">
      <button v-for="h in hints" :key="h" @click="sendHint(h)">{{ h }}</button>
    </div>

    <!-- 输入栏 -->
    <div class="m-input">
      <input v-model="inputText" @keyup.enter="doSend" placeholder="说点什么..." :disabled="loading" />
      <button @click="doSend" :disabled="loading || !inputText.trim()">↑</button>
    </div>
  </div>
</template>

<style scoped>
.m-chat { display: flex; flex-direction: column; height: 100vh; background: #0a0a0a; padding-bottom: 70px; box-sizing: border-box; }

.m-nav {
  display: flex; align-items: center; gap: 10px; padding: 12px 16px;
  background: rgba(10,10,10,0.92); backdrop-filter: blur(8px);
  border-bottom: 1px solid rgba(255,255,255,0.04);
}
.m-back { background: none; border: none; color: #ccc; font-size: 18px; cursor: pointer; padding: 4px; }
.m-title { font-size: 16px; font-weight: 600; color: #eee; }

.m-msgs { flex: 1; overflow-y: auto; padding: 16px; }
.m-msg { margin-bottom: 18px; }
.m-msg.ai { display: flex; }
.m-msg.user { display: flex; justify-content: flex-end; }

.m-ai { display: flex; gap: 8px; max-width: 88%; }
.m-avatar { width: 30px; height: 30px; border-radius: 50%; background: linear-gradient(135deg, #31c27c, #1abc9c); display: flex; align-items: center; justify-content: center; font-size: 14px; flex-shrink: 0; }

.m-bubble { padding: 10px 14px; border-radius: 14px; font-size: 14px; line-height: 1.6; }
.m-bubble.ai { background: #1a1a2e; color: #d0d0d0; border-bottom-left-radius: 4px; }
.m-bubble.user { background: #31c27c; color: #fff; border-bottom-right-radius: 4px; }
.m-bubble p { margin: 0; }

/* 思考过程 */
.thinking-line { font-size: 12px; color: #666; padding: 4px 0; display: flex; align-items: center; gap: 4px; }
.think-dot { color: #31c27c; font-size: 8px; animation: bounce 1s ease-in-out infinite; }
@keyframes bounce { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-3px); } }

.m-cards-label { font-size: 11px; color: #555; margin-bottom: 4px; }
.m-cards { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; border-top: 1px solid rgba(255,255,255,0.05); padding-top: 8px; }
.m-card {
  display: flex; align-items: center; gap: 8px; padding: 6px 8px;
  border-radius: 8px; background: rgba(255,255,255,0.03);
}
.m-card.playing { background: rgba(49,194,124,0.08); }
.m-card.playing .mc-name { color: #31c27c; }
.mc-cover { width: 32px; height: 32px; border-radius: 4px; overflow: hidden; flex-shrink: 0; background: #111; display: flex; align-items: center; justify-content: center; font-size: 12px; color: #444; }
.mc-cover img { width: 100%; height: 100%; object-fit: cover; }
.mc-info { flex: 1; min-width: 0; }
.mc-name { font-size: 12px; color: #d0d0d0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mc-artist { font-size: 10px; color: #666; }
.mc-time { font-size: 10px; color: #555; flex-shrink: 0; }

.m-hints { display: flex; flex-wrap: wrap; gap: 6px; padding: 0 16px 12px; justify-content: center; }
.m-hints button { padding: 6px 14px; border-radius: 14px; border: 1px solid rgba(255,255,255,0.08); background: rgba(255,255,255,0.04); color: #888; font-size: 12px; cursor: pointer; }
.m-hints button:active { border-color: #31c27c; color: #31c27c; }

.m-input { display: flex; gap: 8px; padding: 12px 16px; background: #0a0a0a; border-top: 1px solid rgba(255,255,255,0.04); }
.m-input input { flex: 1; padding: 10px 16px; border-radius: 20px; border: 1px solid rgba(255,255,255,0.08); background: rgba(255,255,255,0.04); color: #e0e0e0; font-size: 14px; outline: none; }
.m-input input::placeholder { color: #555; }
.m-input button { width: 36px; height: 36px; border-radius: 50%; border: none; background: #31c27c; color: #fff; font-size: 16px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.m-input button:disabled { background: #333; }
</style>
