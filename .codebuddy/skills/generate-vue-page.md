# Skill: 新建 Vue 页面（桌面端 + 移动端双端）

## 触发条件
用户说"新建一个XX页面"或"添加XX功能的前端页面"

## 执行流程

### 1. 创建桌面端视图
- 路径：`vibemusic-web/src/views/XxxView.vue`
- 模板：`<script setup>` + `<template>` + `<style scoped>`
- 必须包含：TopBar 组件、page-title、空态提示、loading 态
- API 调用：`import { xxxApi } from '@/api/song'`
- Store：按需引入 `usePlayerStore`/`useFavoriteStore`/`useAuthStore`

### 2. 创建移动端视图
- 路径：`vibemusic-web/src/views/mobile/MXxxView.vue`
- 命名：`M` 前缀，如 `MProfileView`
- 样式：使用 CSS 变量 `var(--m-bg-base)` `var(--m-text-primary)` 等
- 触摸反馈：`.tap-scale:active { transform: scale(0.97) }`
- 安全区：`padding-bottom: calc(12px + var(--m-safe-bottom))`

### 3. 注册路由
- 桌面：`router/index.js` → `{ path: '/xxx', name: 'xxx', component: ... }`
- 移动：`router/index.js` → `/m` children → `{ path: 'xxx', name: 'm-xxx', ... }`
- 鉴权：需要登录加 `meta: { requiresAuth: true }`
- 桌面→移动映射：`beforeEach` 中的 `map` 对象

### 4. API 接口
- 如有新接口，在 `vibemusic-web/src/api/song.js` 添加
- 格式：`export function xxxApi(params) { return request.method('/path', params) }`
- 批量操作需同时添加 batch 版本

### 5. 样式规范
- 桌面端：浅色主题，`#1a1a1a` 标题，`#999` 副标题，`#f5f5f5` 背景
- 移动端：Velvet Night 暗色主题，使用 `--m-*` CSS 变量
- 列表页：grid + auto-fill + minmax，卡片 hover lift
- 骨架屏：`.skeleton` + shimmer 动画

### 6. 验证
```bash
cd vibemusic-web && npm run dev  # 确认无编译错误
```
