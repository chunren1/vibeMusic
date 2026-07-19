---
name: add-page
description: 为 vibeMusic 创建新页面 — 自动生成桌面端视图、移动端视图、路由注册和 API 层封装
---

## 职责

当用户要求"添加新页面"时，按以下流程执行。流程包含交互式问题收集 + 多文件创建。

## 步骤

### 1. 收集页面信息

通过 `question` 工具向用户询问：

| 问题 | 字段 | 说明 |
|------|------|------|
| 页面名称（中文） | `pageName` | 如"最近播放"、"收藏歌单" |
| 路由路径 | `routePath` | 如 `/recent`、`/playlists`，**不含 `/m` 前缀** |
| 是否需要登录 | `requiresAuth` | 是/否 |
| 页面标题（导航栏显示） | `pageTitle` | 如"最近播放" |
| 是否需要新 API 文件 | `needNewApi` | 是/否（如果已有对应 API 文件则填否） |
| API 端点列表 | `apiEndpoints` | 格式：`方法 路径 说明`，如 `GET /favorites/list 获取收藏列表` |

### 2. 读取参考文件

必须读取以下文件以保持代码风格一致：
- `vibemusic-web/src/router/index.js` — 路由注册格式
- `vibemusic-web/src/api/song.js` 或同层已有 API 文件 — API 调用风格
- 与目标页面功能最相似的桌面端视图（如新增列表页参考 `LikesView.vue`）
- 对应的移动端视图（如新增列表页参考 `MLikesView.vue`）

### 3. 创建桌面端视图

路径：`vibemusic-web/src/views/<PageName>View.vue`

必须遵循以下模式：

```vue
<script setup>
import { ref, onMounted } from 'vue'
import TopBar from '@/components/TopBar.vue'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'
import { useFavoriteStore } from '@/stores/favorite'

const player = usePlayerStore()
const authStore = useAuthStore()
const favStore = useFavoriteStore()
</script>

<template>
  <div class="page">
    <TopBar title="页面标题" />
    <!-- 内容区域 -->
  </div>
</template>

<style scoped>
.page { /* 标准布局 */ }
</style>
```

模板规则：
- 导入 `TopBar` 组件作为顶部导航
- 通过 Pinia store 访问全局状态（player / auth / favorite）
- API 调用统一从 `@/api/xxx` 导入，禁止直接使用 axios
- 使用 `<script setup>` 语法
- 样式使用 `scoped`，类名遵循 kebab-case

### 4. 创建移动端视图

路径：`vibemusic-web/src/views/mobile/M<PageName>View.vue`

必须与桌面端功能一致，但适配小屏交互：

```vue
<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const player = usePlayerStore()
const authStore = useAuthStore()
</script>

<template>
  <div class="m-page">
    <!-- 移动端头部（返回按钮 + 标题） -->
    <div class="m-header">
      <button class="back" @click="router.back()">
        <i class="icon-arrow-left"></i>
      </button>
      <h1>页面标题</h1>
    </div>
    <!-- 内容区域 -->
  </div>
</template>

<style scoped>
.m-page { /* 小屏适配布局 */ }
.m-header { /* 固定头部 */ }
</style>
```

模板规则：
- 无 `TopBar` 组件，使用自定义 `m-header`（含返回按钮）
- 列表点击播放后调用 `router.push('/m/player')`
- 页面内部留有底部播放栏的空间（`padding-bottom: 80px`）
- 使用 MobileShell 的默认 padding，不额外添加左右内边距

### 5. 注册路由

编辑 `vibemusic-web/src/router/index.js`，在对应位置添加两条路由：

**桌面端路由**（`desktop routes` 区域）：
```js
{ path: '/xxx', name: 'xxx', meta: { requiresAuth: true }, component: () => import('@/views/XxxView.vue') },
```

**移动端路由**（`MobileShell` children 区域）：
```js
{ path: 'xxx', name: 'm-xxx', meta: { requiresAuth: true }, component: () => import('@/views/mobile/MXxxView.vue') },
```

同时更新导航守卫中的移动端映射表（`map` 对象）：
```js
'/xxx': '/m/xxx',
```

### 6. 添加 API 层

如果 `needNewApi = true`，在 `vibemusic-web/src/api/` 下创建新文件；否则在现有 API 文件中追加。

```js
import request from './request'

export function getXxxList(params) {
  return request.get('/xxx/list', { params })
}

export function createXxx(data) {
  return request.post('/xxx/create', data)
}
```

规则：
- 统一使用 `request` 实例（已封装 token 注入 + X-Request-Id 幂等防护）
- 请求路径以 `/xxx/xxx` 格式，**不含 `/api` 前缀**
- 所有写请求（POST/PUT/DELETE）自动携带 X-Request-Id

### 7. 验证

最终生成并展示给用户的验证清单：

```
## 验证清单
- [ ] 编译通过：`cd vibemusic-web && npx vite build`（或 `npm run build`）
- [ ] 桌面端路由可访问：`/xxx` 正确渲染
- [ ] 移动端路由可访问：`/m/xxx` 正确渲染
- [ ] 移动端自动跳转：手机 UA 访问 `/xxx` → 重定向到 `/m/xxx`
- [ ] Auth 保护生效：未登录访问需登录页面 → 弹出登录弹窗 / 重定向
- [ ] 代码风格一致：参考了同类已有页面
```

## 约束

- 不要修改 `SecurityConfig.java`（由用户手动处理后端权限变更）
- 不要修改后端代码，仅处理前端层面
- 每个创建的文件必须先读参考文件再写，确保风格一致
- 如果页面需要新增后端端点，提醒用户"需要同时在 `SecurityConfig.java` 添加端点白名单"
