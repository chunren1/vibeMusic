<script setup>
import { RouterView } from 'vue-router'
import PlayerBar from '@/components/PlayerBar.vue'

const navItems = [
  { path: '/', label: '主页', icon: '🏠' },
  { path: '/playlists', label: '歌单', icon: '📂' },
  { path: '/likes', label: '我的收藏', icon: '⭐' },
  { path: '/recent', label: '最近播放', icon: '🕐' },
]
</script>

<template>
  <div class="app-layout">
    <!-- 左侧导航 -->
    <aside class="sidebar">
      <div class="logo">♪ vibeMusic</div>
      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          active-class="active"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          <span class="nav-label">{{ item.label }}</span>
        </router-link>
      </nav>
    </aside>

    <!-- 右侧内容区 -->
    <main class="main">
      <RouterView />
    </main>

    <!-- 底部播放条 -->
    <PlayerBar />
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { height: 100%; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #16181c; color: #fff;
}
::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-thumb { background: rgba(255,255,255,.15); border-radius: 3px; }
</style>

<style scoped>
.app-layout { display: flex; height: 100%; }

.sidebar {
  width: 260px; background: #191b1f;
  border-right: 1px solid #2a2a2a;
  display: flex; flex-direction: column; flex-shrink: 0;
}
.logo {
  height: 80px; display: flex; align-items: center;
  padding: 0 28px; font-size: 26px; font-weight: 700;
  color: #31c27c; border-bottom: 1px solid #2a2a2a;
}
.nav { flex: 1; padding: 20px 0; }

.nav-item {
  display: flex; align-items: center; gap: 14px;
  padding: 18px 28px; color: #999;
  text-decoration: none; font-size: 18px; transition: all .2s;
  border-left: 4px solid transparent;
}
.nav-item:hover { color: #ddd; background: rgba(255,255,255,.03); }
.nav-item.active {
  color: #31c27c; background: rgba(49, 194, 124, .08);
  border-left-color: #31c27c;
}
.nav-icon { font-size: 20px; }
.nav-label { font-size: 18px; }

.main {
  flex: 1; overflow-y: auto; padding-bottom: 88px;
}
</style>
