<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const playlistId = route.params.id

// ----- 模拟歌单数据 -----
const playlist = {
  id: playlistId,
  name: '华语热门 | 抖音神曲合集',
  cover: '',
  creator: 'vibeMusic',
  description: '精选当下最热门的华语歌曲，每周更新。',
  songCount: 56,
  playCount: '1280万',
}

const songs = ref([
  { id: 1, title: '晴天', artist: '周杰伦', album: '叶惠美', duration: '4:29' },
  { id: 2, title: '孤勇者', artist: '陈奕迅', album: '孤勇者', duration: '4:16' },
  { id: 3, title: '起风了', artist: '买辣椒也用券', album: '起风了', duration: '5:08' },
  { id: 4, title: '错位时空', artist: '艾辰', album: '错位时空', duration: '3:42' },
  { id: 5, title: '若月亮没来', artist: '黄绮珊', album: '若月亮没来', duration: '4:11' },
  { id: 6, title: '罗生门', artist: '张子豪', album: '罗生门', duration: '3:33' },
  { id: 7, title: '篇章', artist: '张韶涵 / 王赫野', album: '篇章', duration: '4:05' },
  { id: 8, title: '我记得', artist: '赵雷', album: '署前街少年', duration: '5:22' },
  { id: 9, title: '兰亭序', artist: '周杰伦', album: '魔杰座', duration: '4:13' },
  { id: 10, title: '青花瓷', artist: '周杰伦', album: '我很忙', duration: '3:59' },
  { id: 11, title: '稻香', artist: '周杰伦', album: '魔杰座', duration: '3:43' },
  { id: 12, title: '夜曲', artist: '周杰伦', album: '十一月的肖邦', duration: '3:51' },
])

function playAll() {
  console.log('播放全部')
}

function playSong(song) {
  console.log('播放:', song.title)
}
</script>

<template>
  <div class="playlist-page">
    <!-- 歌单头部 -->
    <div class="playlist-header">
      <div class="cover-box">
        <div class="cover-img">♪</div>
      </div>
      <div class="header-info">
        <span class="tag">歌单</span>
        <h1 class="name">{{ playlist.name }}</h1>
        <p class="creator">{{ playlist.creator }} 创建</p>
        <p class="desc">{{ playlist.description }}</p>
        <div class="stats">
          <span>{{ playlist.songCount }} 首</span>
          <span>播放 {{ playlist.playCount }}</span>
        </div>
      </div>
    </div>

    <!-- 操作栏 -->
    <div class="action-bar">
      <button class="btn-play-all" @click="playAll">▶ 播放全部</button>
      <button class="btn-secondary">❤ 收藏</button>
      <button class="btn-secondary">↗ 分享</button>
    </div>

    <!-- 歌曲列表 -->
    <div class="song-table">
      <div class="table-header">
        <span class="col-index">#</span>
        <span class="col-title">歌曲</span>
        <span class="col-artist">歌手</span>
        <span class="col-album">专辑</span>
        <span class="col-time">⏱</span>
      </div>
      <div
        v-for="(song, idx) in songs"
        :key="song.id"
        class="table-row"
        @dblclick="playSong(song)"
      >
        <span class="col-index">{{ idx + 1 }}</span>
        <span class="col-title">{{ song.title }}</span>
        <span class="col-artist">{{ song.artist }}</span>
        <span class="col-album">{{ song.album }}</span>
        <span class="col-time">{{ song.duration }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playlist-page { padding: 32px; padding-bottom: 100px; }

/* ===== 头部 ===== */
.playlist-header {
  display: flex; gap: 28px; margin-bottom: 28px;
}
.cover-box {
  width: 180px; height: 180px; flex-shrink: 0;
  border-radius: 12px; overflow: hidden;
}
.cover-img {
  width: 100%; height: 100%;
  display: flex; align-items: center; justify-content: center;
  font-size: 56px; color: #ec4141;
  background: linear-gradient(135deg, #2a2a3a, #1e2024);
}
.header-info {
  display: flex; flex-direction: column; justify-content: center; gap: 6px;
}
.tag {
  display: inline-block; padding: 2px 8px;
  border: 1px solid #ec4141; border-radius: 4px;
  color: #ec4141; font-size: 12px; width: fit-content;
}
.name {
  font-size: 26px; font-weight: 700; color: #fff;
}
.creator { font-size: 13px; color: #666; }
.desc { font-size: 13px; color: #999; max-width: 480px; }
.stats { font-size: 12px; color: #555; display: flex; gap: 16px; }

/* ===== 操作栏 ===== */
.action-bar {
  display: flex; gap: 12px; margin-bottom: 24px; padding-bottom: 20px;
  border-bottom: 1px solid #2a2a2a;
}
.btn-play-all {
  padding: 10px 28px; border: none; border-radius: 20px;
  background: #ec4141; color: #fff; font-size: 14px; font-weight: 600; cursor: pointer;
}
.btn-play-all:hover { background: #d63434; }
.btn-secondary {
  padding: 10px 20px; border: 1px solid #444; border-radius: 20px;
  background: transparent; color: #ccc; font-size: 13px; cursor: pointer;
}
.btn-secondary:hover { border-color: #666; color: #fff; }

/* ===== 表格 ===== */
.table-header, .table-row {
  display: grid;
  grid-template-columns: 40px 2fr 1fr 1fr 60px;
  align-items: center; gap: 12px;
  padding: 10px 12px; font-size: 13px;
}
.table-header {
  color: #666; border-bottom: 1px solid #2a2a2a;
  margin-bottom: 4px; font-size: 12px;
}
.table-row {
  border-radius: 6px; cursor: pointer; transition: .15s;
  color: #ccc;
}
.table-row:nth-child(odd) { background: rgba(255,255,255,.015); }
.table-row:hover { background: rgba(255,255,255,.06); }
.col-index { text-align: center; color: #555; }
.col-title { color: #ddd; }
.col-artist, .col-album {
  color: #777; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.col-time { text-align: right; color: #555; }
</style>
