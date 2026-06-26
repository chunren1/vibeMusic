# vibemusic-web

vibeMusic 前端应用 — Vue 3 + Vite + Pinia

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5 | Composition API 响应式框架 |
| Vite | 6 | 构建工具 + HMR 热更新 |
| Pinia | 2 | 全局状态管理（auth / player / favorite） |
| Vue Router | 4 | 桌面端 `/` + 移动端 `/m` 自动分流路由 |
| Axios | 1 | HTTP 请求封装 + JWT 拦截器 + 幂等 X-Request-Id |

## 目录结构

```
src/
├── views/              # 桌面端页面 (HomeView, SearchView, PlaylistView...)
│   └── mobile/         # 移动端页面（每个桌面页对应一个移动端版本）
├── components/         # PlayerBar, LyricsView, LoginModal
│   ├── mobile/         # 移动端专用组件
│   └── __tests__/      # PlayerBar 组件单测 (20 cases)
├── stores/             # Pinia Stores: auth, player, favorite, recommend
│   └── __tests__/      # PlayerStore 单测 (21 cases)
├── composables/        # useAudioBackground, useClickOutside, useIsMobile, useToast, useVirtualList
├── router/             # 桌面/移动端路由自动分流
├── directives/         # v-lazy-img 图片懒加载指令
├── api/                # Axios 封装 + 请求拦截器 + 自动刷新 Token
└── assets/             # SVG 图标系统 (SvgIcon 组件 + 17 个内联 symbol)
```

## 开发

```bash
npm install
npm run dev             # → http://localhost:5173
npm run test            # Vitest 41 条测试
npm run test:watch      # 监听模式
npm run build           # 生产构建 → dist/
```

## 特性

- **双端适配** — 桌面侧栏布局 + 移动端底部 TabBar，共享 API/Store
- **播放状态管理** — 全局单例 Audio，队列/模式/音量/进度持久化到 localStorage
- **收藏全局同步** — Pinia Favorite Store，10 个组件统一接入，乐观更新 + 回滚
- **移动端优化** — `100dvh` 安全区域，`position: sticky` 输入栏，路由懒加载
- **暗色主题** — 统一暗色主题，SVG 图标 currentColor 自适应

---

详见项目根目录 [README.md](../README.md)
