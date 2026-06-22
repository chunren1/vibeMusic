<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const player = usePlayerStore()
const auth = useAuthStore()

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

function fmtSec(s) {
  if (!s) return ''
  const m = Math.floor(s / 60)
  return m + ':' + String(s % 60).padStart(2, '0')
}

onMounted(() => {
  messages.value.push({ role: 'ai', content: greeting })
  scrollBottom()
})
</script>

<template>
  <!-- 最外层容器：禁止横向滚动，固定视口 -->
  <div class="mc-shell">
    <!-- 导航栏 -->
    <div class="mc-nav">
      <button class="mc-back" @click="router.back()">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <div class="mc-nav-info">
        <span class="mc-nav-title">vibe 音乐精灵</span>
        <span class="mc-nav-sub">AI 音乐助手</span>
      </div>
    </div>

    <!-- 消息列表 -->
    <div class="mc-msgs" ref="chatBox">
      <div v-for="(msg, idx) in messages" :key="idx" class="mc-row" :class="msg.role">
        <!-- AI 消息 -->
        <template v-if="msg.role === 'ai'">
          <div class="mc-avatar ai-avatar">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 18V5l12-2v13"/>
              <circle cx="6" cy="18" r="3"/>
              <circle cx="18" cy="16" r="3"/>
            </svg>
          </div>
          <div class="mc-bubble bubble-ai">
            <div v-if="msg.thinking" class="mc-thinking">
              <span>思考中</span>
              <span class="dot" v-for="i in 3" :key="i" :style="{ animationDelay: (i-1)*0.2+'s' }" />
            </div>
            <p v-if="msg.content">{{ msg.content }}</p>
            <!-- 歌曲卡片 -->
            <div v-if="msg.songs && msg.songs.length" class="mc-songs">
              <div class="mc-songs-label">🎶 为你找到：</div>
              <div
                v-for="song in msg.songs" :key="song.sourceId"
                class="mc-song" @click="playSong(song)"
                :class="{ active: player.currentSong?.id === song.sourceId && player.isPlaying }"
              >
                <div class="mcs-cover">
                  <img v-if="song.coverUrl" :src="song.coverUrl + '?param=80y80'" loading="lazy" />
                  <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="currentColor" opacity="0.3"><path d="M9 18V5l12-2v13"/></svg>
                </div>
                <div class="mcs-info">
                  <div class="mcs-name">{{ song.name }}</div>
                  <div class="mcs-artist">{{ song.artist }}</div>
                </div>
                <span class="mcs-time">{{ fmtSec(song.duration) }}</span>
              </div>
            </div>
          </div>
        </template>

        <!-- 用户消息 -->
        <template v-else>
          <div class="mc-bubble bubble-user"><p>{{ msg.content }}</p></div>
          <div class="mc-avatar user-avatar">
            <img v-if="auth.avatarSrc" :src="auth.avatarSrc" class="mc-avatar-img" />
            <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
              <circle cx="12" cy="7" r="4"/>
            </svg>
          </div>
        </template>
      </div>
    </div>

    <!-- 快捷提示 -->
    <div v-if="messages.length <= 1" class="mc-hints">
      <button v-for="h in hints" :key="h" @click="sendHint(h)">{{ h }}</button>
    </div>

    <!-- 输入栏 -->
    <div class="mc-input-bar">
      <input
        v-model="inputText"
        @keyup.enter="doSend"
        placeholder="描述你想要的音乐..."
        :disabled="loading"
        enterkeyhint="send"
      />
      <button @click="doSend" :disabled="loading || !inputText.trim()">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
/* ===== 外壳：固定可视区域（减去播放栏60px + 标签栏56px） ===== */
.mc-shell {
  display: flex; flex-direction: column;
  height: calc(100dvh - 116px);
  max-width: 100vw; overflow-x: hidden;
  background: #0a0a0a;
  box-sizing: border-box;
}

/* ===== 导航 ===== */
.mc-nav {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px;
  background: rgba(10,10,10,.94);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(255,255,255,.05);
  flex-shrink: 0; z-index: 5;
}
.mc-back {
  background: none; border: none; color: #ccc;
  padding: 6px; cursor: pointer; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
}
.mc-back:active { background: rgba(255,255,255,.06); }
.mc-nav-info { display: flex; flex-direction: column; }
.mc-nav-title { font-size: 16px; font-weight: 600; color: #eee; }
.mc-nav-sub { font-size: 11px; color: #666; }

/* ===== 消息列表 ===== */
.mc-msgs {
  flex: 1; overflow-y: auto; overflow-x: hidden;
  padding: 16px;
}
.mc-row {
  display: flex; align-items: flex-end; gap: 8px;
  margin-bottom: 20px; max-width: 100%;
}
.mc-row.ai { justify-content: flex-start; }
.mc-row.user { justify-content: flex-end; }

/* ===== 头像 ===== */
.mc-avatar {
  width: 34px; height: 34px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.ai-avatar {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
}
.user-avatar {
  background: linear-gradient(135deg, #31c27c, #1abc9c);
  color: #fff;
  overflow: hidden;
}
.mc-avatar-img {
  width: 100%; height: 100%; object-fit: cover; border-radius: 50%;
}

/* ===== 气泡 ===== */
.mc-bubble {
  padding: 11px 15px; border-radius: 18px;
  font-size: 14px; line-height: 1.55; word-break: break-word;
  max-width: 78%;
}
.bubble-ai {
  background: #1a1a2e; color: #d0d0d0;
  border-bottom-left-radius: 6px;
}
.bubble-user {
  background: #31c27c; color: #fff;
  border-bottom-right-radius: 6px;
}
.mc-bubble p { margin: 0; }

/* ===== 思考动画 ===== */
.mc-thinking {
  display: flex; align-items: center; gap: 5px;
  font-size: 12px; color: #777; padding-bottom: 4px;
}
.dot {
  width: 5px; height: 5px; border-radius: 50%; background: #6366f1;
  animation: mcbounce 1.2s ease-in-out infinite;
}
@keyframes mcbounce {
  0%, 80%, 100% { transform: translateY(0); opacity: .3; }
  40% { transform: translateY(-5px); opacity: 1; }
}

/* ===== 歌曲卡片 ===== */
.mc-songs-label { font-size: 11px; color: #555; margin-bottom: 6px; }
.mc-songs {
  margin-top: 10px; display: flex; flex-direction: column; gap: 5px;
  border-top: 1px solid rgba(255,255,255,.06); padding-top: 10px;
}
.mc-song {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 9px; border-radius: 10px;
  background: rgba(255,255,255,.04);
  cursor: pointer; transition: .15s;
}
.mc-song:active { background: rgba(49,194,124,.08); }
.mc-song.active .mcs-name { color: #31c27c; }
.mcs-cover {
  width: 36px; height: 36px; border-radius: 6px; overflow: hidden;
  flex-shrink: 0; background: #151515;
  display: flex; align-items: center; justify-content: center;
}
.mcs-cover img { width: 100%; height: 100%; object-fit: cover; }
.mcs-info { flex: 1; min-width: 0; }
.mcs-name {
  font-size: 12px; color: #d0d0d0; font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mcs-artist { font-size: 10px; color: #666; margin-top: 1px; }
.mcs-time { font-size: 10px; color: #555; flex-shrink: 0; }

/* ===== 快捷提示 ===== */
.mc-hints {
  display: flex; flex-wrap: wrap; gap: 8px;
  padding: 0 16px 14px; justify-content: center; flex-shrink: 0;
}
.mc-hints button {
  padding: 8px 16px; border-radius: 18px;
  border: 1px solid rgba(255,255,255,.1);
  background: rgba(255,255,255,.04); color: #888;
  font-size: 12px; cursor: pointer;
  transition: .15s;
}
.mc-hints button:active { border-color: #6366f1; color: #a5b4fc; }

/* ===== 输入栏 ===== */
.mc-input-bar {
  display: flex; gap: 8px; padding: 10px 16px;
  background: #0a0a0a;
  border-top: 1px solid rgba(255,255,255,.05);
}
.mc-input-bar input {
  flex: 1; min-width: 0;
  padding: 10px 16px; border-radius: 22px;
  border: 1px solid rgba(255,255,255,.1);
  background: rgba(255,255,255,.05); color: #e0e0e0;
  font-size: 14px; outline: none;
}
.mc-input-bar input::placeholder { color: #555; }
.mc-input-bar input:focus { border-color: #6366f1; }
.mc-input-bar button {
  width: 40px; height: 40px; border-radius: 50%;
  border: none; background: #6366f1; color: #fff;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  flex-shrink: 0; transition: .15s;
}
.mc-input-bar button:active { background: #4f46e5; }
.mc-input-bar button:disabled { background: #2a2a2a; color: #555; }
</style>
