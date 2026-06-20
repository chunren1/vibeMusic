<script setup>
import { ref, nextTick, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import request from '@/api/request'
import { usePlayerStore } from '@/stores/player'

const player = usePlayerStore()

const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const chatBox = ref(null)

const greeting = '嗨！我是 vibe 音乐精灵 🎵 想听什么歌？过什么心情？告诉我，我来帮你找～'

function scrollBottom() {
  nextTick(() => { if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight })
}

// 快捷提示
const hints = ['推荐几首周杰伦的歌', '适合下雨天听的歌', '最近有什么热门歌曲', '来点治愈系音乐']

function sendHint(h) { inputText.value = h; doSend() }

async function doSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  inputText.value = ''

  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'ai', content: '', typing: true })
  scrollBottom()

  loading.value = true
  try {
    const res = await request.post('/assistant/chat', { message: text })
    const last = messages.value[messages.value.length - 1]
    last.content = res.data.reply || '让我想想...'
    last.typing = false
    last.songs = res.data.songs || []
    scrollBottom()
  } catch {
    const last = messages.value[messages.value.length - 1]
    last.content = '抱歉，网络不太稳定，再试一次？'
    last.typing = false
  } finally {
    loading.value = false
  }
}

function playSong(song) {
  player.playSongFromApi(song.sourceId, song.name, song.artist, song.coverUrl || '', song.platform)
}

function addToQueue(song) {
  player.addToQueue({
    sourceId: song.sourceId, name: song.name, artist: song.artist,
    coverUrl: song.coverUrl, duration: song.duration, platform: song.platform,
  })
}

function fmtSec(s) { if (!s) return ''; const m = Math.floor(s / 60); return m + ':' + String(s % 60).padStart(2, '0') }

onMounted(() => {
  messages.value.push({ role: 'ai', content: greeting })
  scrollBottom()
})
</script>

<template>
  <TopBar />
  <div class="chat-page">
    <div class="chat-messages" ref="chatBox">
      <div v-for="(msg, idx) in messages" :key="idx" class="msg-row" :class="msg.role">
        <!-- AI 消息 -->
        <div v-if="msg.role === 'ai'" class="msg-ai">
          <div class="ai-avatar">🎵</div>
          <div class="msg-bubble ai">
            <template v-if="msg.typing">
              <span class="typing"><i></i><i></i><i></i></span>
            </template>
            <template v-else>
              <p>{{ msg.content }}</p>
              <!-- 音乐推荐卡片 -->
              <div v-if="msg.songs && msg.songs.length" class="song-cards">
                <div
                  v-for="song in msg.songs" :key="song.sourceId"
                  class="song-card"
                  :class="{ playing: player.currentSong?.id === song.sourceId && player.isPlaying }"
                >
                  <div class="sc-cover" @click="playSong(song)">
                    <img v-if="song.coverUrl" :src="song.coverUrl + '?param=120y120'" />
                    <span v-else>♪</span>
                    <div class="sc-play"><svg width="18" height="18" viewBox="0 0 24 24" fill="white"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>
                  </div>
                  <div class="sc-info" @click="playSong(song)">
                    <div class="sc-name">{{ song.name }}</div>
                    <div class="sc-artist">{{ song.artist }}</div>
                  </div>
                  <span class="sc-time">{{ fmtSec(song.duration) }}</span>
                  <button class="sc-add" @click.stop="addToQueue(song)" title="加入队列">+</button>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- 用户消息 -->
        <div v-else class="msg-bubble user">
          <p>{{ msg.content }}</p>
        </div>
      </div>
    </div>

    <!-- 快捷提示 -->
    <div v-if="messages.length <= 1" class="quick-hints">
      <button v-for="h in hints" :key="h" @click="sendHint(h)" :disabled="loading">{{ h }}</button>
    </div>

    <!-- 输入栏 -->
    <div class="chat-input">
      <input
        v-model="inputText"
        @keyup.enter="doSend"
        placeholder="输入想听的歌，或描述你的心情..."
        :disabled="loading"
      />
      <button @click="doSend" :disabled="loading || !inputText.trim()">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex; flex-direction: column;
  height: calc(100vh - 180px);
  max-width: 800px; margin: 0 auto; padding: 0 24px;
}

.chat-messages {
  flex: 1; overflow-y: auto; padding: 24px 0;
  scroll-behavior: smooth;
}
.chat-messages::-webkit-scrollbar { width: 4px; }
.chat-messages::-webkit-scrollbar-thumb { background: #ddd; border-radius: 2px; }

.msg-row { display: flex; margin-bottom: 20px; }
.msg-row.ai { justify-content: flex-start; }
.msg-row.user { justify-content: flex-end; }

.msg-ai { display: flex; gap: 10px; max-width: 85%; }
.ai-avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: linear-gradient(135deg, #31c27c, #1abc9c);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; flex-shrink: 0;
}

.msg-bubble {
  padding: 12px 18px; border-radius: 16px;
  font-size: 15px; line-height: 1.6; word-break: break-word;
}
.msg-bubble.ai { background: #fff; color: #333; border-bottom-left-radius: 4px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.msg-bubble.user { background: #31c27c; color: #fff; border-bottom-right-radius: 4px; }
.msg-bubble p { margin: 0; }

/* 打字动画 */
.typing { display: inline-flex; gap: 4px; padding: 4px 0; }
.typing i {
  width: 6px; height: 6px; border-radius: 50%; background: #ccc;
  animation: dot 1.2s infinite;
}
.typing i:nth-child(2) { animation-delay: 0.2s; }
.typing i:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot { 0%, 60%, 100% { opacity: 0.2; transform: scale(0.8); } 30% { opacity: 1; transform: scale(1); } }

/* 音乐卡片 */
.song-cards {
  margin-top: 12px; display: flex; flex-direction: column; gap: 6px;
  border-top: 1px solid #f0f0f0; padding-top: 10px;
}
.song-card {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 10px; border-radius: 10px;
  background: #f8f8f8; transition: .12s; cursor: default;
}
.song-card:hover { background: #f0f0f0; }
.song-card.playing { background: rgba(49,194,124,0.08); }
.song-card.playing .sc-name { color: #31c27c; }

.sc-cover {
  width: 40px; height: 40px; border-radius: 6px; overflow: hidden;
  position: relative; flex-shrink: 0; cursor: pointer;
  background: #e0e0e0; display: flex; align-items: center; justify-content: center;
  font-size: 16px; color: #bbb;
}
.sc-cover img { width: 100%; height: 100%; object-fit: cover; }
.sc-play {
  position: absolute; inset: 0; border-radius: 6px;
  background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: .12s;
}
.sc-cover:hover .sc-play { opacity: 1; }

.sc-info { flex: 1; min-width: 0; cursor: pointer; }
.sc-name { font-size: 13px; font-weight: 500; color: #333; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sc-artist { font-size: 11px; color: #999; margin-top: 2px; }
.sc-time { font-size: 11px; color: #bbb; flex-shrink: 0; }
.sc-add {
  width: 26px; height: 26px; border-radius: 50%; border: 1px solid #ddd;
  background: #fff; color: #888; font-size: 14px; cursor: pointer;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.sc-add:hover { border-color: #31c27c; color: #31c27c; }

/* 快捷提示 */
.quick-hints { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 0 16px; justify-content: center; }
.quick-hints button {
  padding: 8px 16px; border-radius: 18px; border: 1px solid #e0e0e0;
  background: #fff; color: #666; font-size: 13px; cursor: pointer;
  transition: .15s;
}
.quick-hints button:hover { border-color: #31c27c; color: #31c27c; background: rgba(49,194,124,0.04); }
.quick-hints button:disabled { opacity: .5; cursor: default; }

/* 输入栏 */
.chat-input {
  display: flex; gap: 10px; padding: 16px 0 24px;
  border-top: 1px solid #eee;
}
.chat-input input {
  flex: 1; padding: 12px 18px; border: 1px solid #e0e0e0;
  border-radius: 24px; outline: none; font-size: 15px; color: #333;
  transition: .15s;
}
.chat-input input:focus { border-color: #31c27c; }
.chat-input input::placeholder { color: #bbb; }
.chat-input button {
  width: 46px; height: 46px; border-radius: 50%; border: none;
  background: #31c27c; color: #fff; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: .15s; flex-shrink: 0;
}
.chat-input button:hover { background: #28a86b; }
.chat-input button:disabled { background: #ccc; cursor: default; }

@media (max-width: 768px) {
  .chat-page { padding: 0 16px; }
  .msg-bubble.ai { max-width: 90%; }
}
</style>
