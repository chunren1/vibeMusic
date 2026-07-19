---
name: frontend-debug
description: >
  Common frontend debugging checklist for vibeMusic. Trigger when user
  reports "前端报错", "页面白屏", "播放不了", "Service Worker", "Vite 代理".
---

# Skill: 前端常见问题排查

## Service Worker 问题
### SW 拦截音频导致播放失败
- 症状：error code=4，sw.js 报 `Partial response (status code 206) is unsupported`
- 原因：SW 用 Cache API 缓存音频 Range 请求的 206 响应
- 修复：sw.js 跳过 `dest === 'audio' || dest === 'video'`
- 快速解：F12 → Application → Service Workers → Unregister → 刷新

### SW 缓存旧代码
- 症状：改了文件但浏览器还是旧效果
- 修复：Ctrl+Shift+R 硬刷新，或 SW → Unregister
- 注意：sw.js 改版号（CACHE_NAME）强制刷新

## Player 播放器问题
### 播放/暂停快速切换死循环
- 症状：按钮闪烁，无法切歌，error code=4 刷屏
- 根因：重试耗尽后 `_retryCount=0` → 又触发 play → 无限循环
- 修复：`_errorLocked` 锁 + 自动 `next()`

### 歌曲播到一半中断
- 根因：CDN 链接过期（5-15分钟有效）
- 表现：error code=4 → retry → 如果 URL 一样则 retry 无效
- 修复：重试机制已改为 `audio.src=''` + `audio.load()` 强制重新请求

### 播放的是错误的歌
- 原因：platform 参数丢失，后端猜错了平台
- 检查链路：搜索结果 .platform → playSongFromApi → playBySourceId → streamURL

## Vite 代理问题
### API 请求 404
- 检查：`vite.config.js` 中 proxy 配置
- `/api` → `http://localhost:8080`（后端）
- musicapi 直连：`http://localhost:3000`（不走代理）

### CORS 错误
- 后端 `CorsConfig.java` 检查 allowedOrigins
- musicapi：全局 `cors()` 已放开

## 页面渲染问题
### 白屏
1. 检查 console 报错
2. 检查 Vue Router：路径是否注册
3. 检查 Store 初始化：Pinia store 是否有未 catch 的异常
4. 检查组件引入：import 路径是否正确

### 数据不显示
1. Network 面板看 API 返回
2. 检查响应格式：`{ code: 200, data: [...] }`
3. 检查 Store 更新：是否调用了对应的 action
4. 检查 computed/watch：依赖是否正确