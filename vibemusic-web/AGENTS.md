# PROJECT KNOWLEDGE BASE — vibemusic-web

**Generated:** 2026-08-13
**Scope:** Vue 3.5 + Vite 8 + Pinia + Vue Router + Axios 前端（纯 JS，无 TS/无 eslint/无 prettier）。Capacitor Android 打包（android/，appId com.vibemusic.app）。src/ 共 67 文件。

## STRUCTURE
```
src/
├── views/            # 桌面端页面（11）
├── views/mobile/     # 移动端页面（12，M 前缀）
├── components/       # 通用组件（10 + 4 mobile + 3 test）
├── stores/           # Pinia（4 + 3 test）
├── api/              # 请求封装（3）
├── composables/      # 组合式函数（5）
├── router/           # 路由（1）
├── directives/       # 自定义指令（1）
├── utils/            # 工具（2）
└── assets/           # 静态资源（4）
```

## CONVENTIONS（仅列偏离标准处）
1. **双端同步（强制）**：桌面页 `views/XxxView.vue` 必须有 `views/mobile/MXxxView.vue` 移动版（唯一例外 LoginView→LoginModal）。新增桌面页 = 建两个文件 + 注册 `/m` 子路由 + 在 `router/index.js` beforeEach 的 device→mobile 映射表加条目（`/playlist/` 有特殊处理）。路由名 m- 前缀。
2. **设备检测**：`useIsMobile.js` 模块级单例 ref（innerWidth<768 || UA 正则），给 `<html>` 加 `mobile` class；纯函数 `checkMobile()` 供路由守卫同步判断。App.vue 有独立 300ms 防抖 checkDevice 切换桌面壳（侧栏+PlayerBar）/移动壳（裸 RouterView）。
3. **API 层信封约定**：唯一 axios 实例 `src/api/request.js`，baseURL=`VITE_API_HOST + '/api'`（dev 为空走 Vite proxy /api+/uploads→8080，prod 为 .env.production 域名）。响应拦截器解包 `{code,data,message}`（code!==200 拒绝）；401/403 且非 /auth/ → handleUnauthorized（logout+登录弹窗）。请求拦截器：内存 token 加 Bearer；写方法自动加 X-Request-Id UUID（幂等）。**组件内禁止直接 axios，必须走 api/ 层。**
4. **播放器架构**：`stores/player.js` 全局单例 Audio（window.vibeAudio）+ 队列/localStorage 持久化（vibe_queue/idx/current_song/playback_time/volume/play_mode，300ms 防抖+flushSave）+ error 重试 2 次切歌 + AudioContext Analyser（window._vibeAnalyser）+ song-change CustomEvent + 大量 window.vibe* 全局（vibePlay/vibeNext/vibeAddToQueue…）供移动端调用。**新增播放入口需同步暴露 window.* 全局**。后台播放：`composables/useAudioBackground.js`（Page Visibility 不暂停 + 三层切歌检测 + Media Session + Worker 心跳 + Wake Lock）。
5. **store 职责**：auth=内存 token(禁 localStorage)+tryRestoreSession(httpOnly cookie→getMe→refresh)；favorite=favIds Set 乐观更新回滚；recommend=首页推荐(deviceId localStorage, 失败降级 getRandomSongs)。
6. **测试**：Vitest 4 + @vue/test-utils 2，jsdom，`__tests__/` 与源码同目录（components/stores/views），`*.test.js` 命名，全局 mock 在 `src/test-setup.js`，Pinia 测试用 `setActivePinia(createPinia())`。脚本：npm test (vitest run) / test:watch / test:coverage。
7. **其他**：v-lazy-img 指令懒加载（禁止同元素同时用 :style）；vite 生产 terser drop_console(log/info/debug)+manualChunks(vue-core/pinia/axios/capacitor/vendor)；移动端样式在 `assets/mobile-theme.css`（--m-* 变量）；搜索用 AbortController 取消旧请求防竞态。

## ANTI-PATTERNS
- 组件内直接 import axios 或 fetch —— 必须走 api/ 层。
- 新增页面忘建移动版 / 忘注册 /m 路由 —— 双端约定破坏。
- JWT 存 localStorage —— 必须 httpOnly cookie + 内存缓存。
- 移动端绕过 player store 直接操作 audio 元素。

## NOTES
- MobileShell.vue 是 /m 父路由壳（RouterView+MBottomPlayer+MTabBar+MQueuePopup，keep-alive 6 页，flushSave on beforeunload）。
- /m/player 是移动端独有页（无桌面对应）；桌面登录页独立路由，移动端用 LoginModal。
- 封面/头像 URL 拼接 API_HOST；图片懒加载用 v-lazy-img:bg 或 src。
