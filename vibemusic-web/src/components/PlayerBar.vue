<script setup>
import { ref } from 'vue'

const currentSong = ref({
  title: '晴天',
  artist: '周杰伦',
})

const isPlaying = ref(false)
const progress = ref(35)
const volume = ref(70)
const isMuted = ref(false)
const isLiked = ref(false)       // 当前歌曲是否已收藏

function togglePlay() { isPlaying.value = !isPlaying.value }
function prev() { console.log('上一首') }
function next() { console.log('下一首') }
function toggleMute() { isMuted.value = !isMuted.value }
function toggleLike() { isLiked.value = !isLiked.value }

// ===== 播放列表面板 =====
const showPlaylist = ref(false)

function togglePlaylist() {
  showPlaylist.value = !showPlaylist.value
}

const playlist = ref([
  { id: 1, title: '晴天', artist: '周杰伦', duration: '4:29' },
  { id: 2, title: '孤勇者', artist: '陈奕迅', duration: '4:16' },
  { id: 3, title: '起风了', artist: '买辣椒也用券', duration: '5:08' },
  { id: 4, title: '错位时空', artist: '艾辰', duration: '3:42' },
  { id: 5, title: '若月亮没来', artist: '黄绮珊', duration: '4:11' },
  { id: 6, title: '罗生门', artist: '张子豪', duration: '3:33' },
  { id: 7, title: '篇章', artist: '张韶涵 / 王赫野', duration: '4:05' },
  { id: 8, title: '我记得', artist: '赵雷', duration: '5:22' },
])

function removeFromPlaylist(id) {
  playlist.value = playlist.value.filter(s => s.id !== id)
}

function playFromPlaylist(song) {
  currentSong.value = { title: song.title, artist: song.artist }
  isPlaying.value = true
}
</script>

<template>
  <!-- 播放列表右侧面板 -->
  <Transition name="panel">
    <div v-if="showPlaylist" class="right-panel">
      <div class="panel-content">
        <div class="panel-header">
          <h3>📋 播放列表</h3>
          <span class="panel-count">{{ playlist.length }} 首</span>
          <button class="panel-close" @click="showPlaylist = false">✕</button>
        </div>
        <div class="panel-list">
          <div
            v-for="(song, idx) in playlist"
            :key="song.id"
            class="panel-song"
            @dblclick="playFromPlaylist(song)"
          >
            <span class="ps-index">{{ idx + 1 }}</span>
            <div class="ps-info">
              <span class="ps-title" :class="{ current: currentSong.title === song.title }">{{ song.title }}</span>
              <span class="ps-meta">{{ song.artist }}</span>
            </div>
            <span class="ps-time">{{ song.duration }}</span>
            <button class="ps-remove" @click.stop="removeFromPlaylist(song.id)">✕</button>
          </div>
          <p v-if="playlist.length === 0" class="empty">播放列表为空</p>
        </div>
      </div>
    </div>
  </Transition>

  <!-- 底部栏 -->
  <div class="player-bar">
    <!-- 歌曲信息 -->
    <div class="song-info">
      <div class="mini-cover">♪</div>
      <div class="info-text">
        <p class="song-title">{{ currentSong.title }}</p>
        <p class="song-artist">{{ currentSong.artist }}</p>
      </div>
    </div>

    <!-- 播放控件 -->
    <div class="player-controls">
      <div class="control-btns">
        <button class="ctrl-btn" @click="prev">⏮</button>
        <button class="ctrl-btn play-btn" @click="togglePlay">
          {{ isPlaying ? '⏸' : '▶' }}
        </button>
        <button class="ctrl-btn" @click="next">⏭</button>
      </div>
      <div class="progress-area">
        <span class="time">1:34</span>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progress + '%' }"></div>
        </div>
        <span class="time">4:29</span>
      </div>
    </div>

    <!-- 右侧：音量 + 喜欢 + 播放列表 -->
    <div class="right-area">
      <div class="volume-area">
        <button class="mute-btn" @click="toggleMute">{{ isMuted ? '🔇' : '🔊' }}</button>
        <div class="volume-bar">
          <div class="volume-fill" :style="{ width: (isMuted ? 0 : volume) + '%' }"></div>
        </div>
      </div>
      <div class="divider"></div>
      <button
        class="like-btn"
        :class="{ liked: isLiked }"
        @click="toggleLike"
        :title="isLiked ? '取消喜欢' : '喜欢'"
      >
        {{ isLiked ? '❤️' : '🤍' }}
      </button>
      <button
        class="panel-btn"
        :class="{ active: showPlaylist }"
        @click="togglePlaylist"
        title="播放列表"
      >
        📋
        <span class="pl-badge">{{ playlist.length }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
/* ============================== */
/*  播放列表右侧面板                  */
/* ============================== */
.right-panel {
  position: fixed; top: 0; right: 0; bottom: 80px;
  width: 360px; background: #1a1d22;
  border-left: 1px solid #2a2a2a;
  z-index: 99; display: flex; flex-direction: column;
  overflow: hidden;
}
.panel-content { display: flex; flex-direction: column; height: 100%; }
.panel-header {
  display: flex; align-items: center; gap: 12px;
  padding: 18px 20px; border-bottom: 1px solid #2a2a2a; flex-shrink: 0;
}
.panel-header h3 { font-size: 16px; color: #eee; flex: 1; }
.panel-count { font-size: 12px; color: #666; }
.panel-close {
  background: none; border: none; color: #666; font-size: 18px; cursor: pointer;
  padding: 4px 8px; border-radius: 4px;
}
.panel-close:hover { color: #fff; background: rgba(255,255,255,.06); }

.panel-list { flex: 1; overflow-y: auto; padding: 8px 0; }
.panel-song {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 16px; cursor: pointer; transition: .12s;
}
.panel-song:hover { background: rgba(255,255,255,.04); }
.panel-song:nth-child(odd) { background: rgba(255,255,255,.012); }
.panel-song:nth-child(odd):hover { background: rgba(255,255,255,.04); }

.ps-index { width: 24px; text-align: center; font-size: 12px; color: #555; }
.ps-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ps-title { font-size: 13px; color: #ccc; }
.ps-title.current { color: #31c27c; }
.ps-meta { font-size: 11px; color: #555; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ps-time { font-size: 11px; color: #555; }
.ps-remove {
  background: none; border: none; color: #444; font-size: 12px; cursor: pointer;
  padding: 2px 6px; border-radius: 4px; opacity: 0;
}
.panel-song:hover .ps-remove { opacity: 1; }
.ps-remove:hover { color: #ec4141; background: rgba(255,255,255,.06); }

.empty { text-align: center; padding: 40px 0; color: #555; font-size: 13px; }

/* 动画 */
.panel-enter-active, .panel-leave-active { transition: transform .25s ease; }
.panel-enter-from, .panel-leave-to { transform: translateX(100%); }

/* ============================== */
/*  底部播放条                       */
/* ============================== */
.player-bar {
  position: fixed; bottom: 0; left: 0; right: 0; height: 80px;
  background: #191b1f; border-top: 1px solid #2a2a2a;
  display: flex; align-items: center; gap: 20px;
  padding: 0 24px; z-index: 100;
}

.song-info {
  display: flex; align-items: center; gap: 14px; width: 240px; flex-shrink: 0;
}
.mini-cover {
  width: 54px; height: 54px; border-radius: 8px;
  background: linear-gradient(135deg, #2a2a3a, #1e2024);
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; color: #31c27c; flex-shrink: 0;
}
.info-text { flex: 1; min-width: 0; }
.song-title { font-size: 15px; color: #ddd; }
.song-artist { font-size: 13px; color: #666; margin-top: 3px; }

.player-controls {
  flex: 1; display: flex; flex-direction: column; align-items: center; gap: 8px;
}
.control-btns { display: flex; align-items: center; gap: 24px; }
.ctrl-btn {
  background: none; border: none; color: #ccc; font-size: 22px; cursor: pointer;
}
.ctrl-btn:hover { color: #fff; }
.play-btn {
  width: 44px; height: 44px; border-radius: 50%;
  background: #31c27c; color: #fff; font-size: 16px;
  display: flex; align-items: center; justify-content: center;
}
.play-btn:hover { background: #28a86b; }

.progress-area {
  display: flex; align-items: center; gap: 12px; width: 100%; max-width: 520px;
}
.time { font-size: 13px; color: #555; width: 40px; text-align: center; }
.progress-bar {
  flex: 1; height: 5px; background: #333; border-radius: 3px;
  position: relative; cursor: pointer;
}
.progress-bar:hover { height: 7px; }
.progress-fill {
  height: 100%; background: #31c27c; border-radius: 3px; transition: width .3s;
}

/* 右侧区域 */
.right-area {
  display: flex; align-items: center; gap: 14px; flex-shrink: 0;
}
.divider { width: 1px; height: 28px; background: #2a2a2a; }

/* 喜欢按钮 */
.like-btn {
  background: none; border: none; font-size: 22px; cursor: pointer;
  padding: 6px; border-radius: 6px; transition: .15s;
}
.like-btn:hover { transform: scale(1.15); }
.like-btn.liked { animation: heartBeat .3s ease; }

@keyframes heartBeat {
  0% { transform: scale(1); }
  50% { transform: scale(1.3); }
  100% { transform: scale(1); }
}

/* 播放列表按钮 */
.panel-btn {
  position: relative; background: none; border: none;
  color: #999; font-size: 22px; cursor: pointer;
  padding: 6px; border-radius: 6px; transition: .15s;
}
.panel-btn:hover { color: #fff; background: rgba(255,255,255,.06); }
.panel-btn.active { color: #31c27c; }
.pl-badge {
  position: absolute; top: 0; right: -2px;
  background: #31c27c; color: #fff; font-size: 10px;
  min-width: 16px; height: 16px; line-height: 16px;
  border-radius: 8px; text-align: center; padding: 0 4px;
}

/* 音量 */
.volume-area { display: flex; align-items: center; gap: 10px; }
.mute-btn {
  background: none; border: none; font-size: 20px; cursor: pointer; color: #ccc;
}
.volume-bar {
  width: 100px; height: 5px; background: #333; border-radius: 3px; cursor: pointer;
}
.volume-fill {
  height: 100%; background: #ccc; border-radius: 3px; transition: width .3s;
}
</style>
