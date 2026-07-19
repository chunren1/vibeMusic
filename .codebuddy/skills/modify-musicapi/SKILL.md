---
name: modify-musicapi
description: >
  Safety checklist for modifying musicapi/server.js or config.js.
  Trigger when user says "change musicapi", "modify server.js",
  "更新 Cookie", or makes changes to the musicapi/ directory.
---

# Skill: 修改 musicapi（安全清单）

## 改之前
  - 确认当前运行模式：`npm run dev` 还是单独 `node server.js`
  - 备份：修改量大时先 git stash 或复制原文件

## 改之后必须做的事

### 1. 语法验证
```bash
cd musicapi && node -c server.js
```
⚠️ Node.js 不会在 IDE 里报红，语法错误只在运行时暴露

### 2. 新增依赖
```bash
cd musicapi && npm install <pkg> --save
```
不要手动改 package.json

### 3. Cookie 相关修改
- 检查 `config.js` 中 `parseQQCookie` 是否还在用
- 更新后调用 `/cookie-status` 验证是否生效
- Cookie 从 .env 读取（dotenv 在 server.js 第 2 行加载）

### 4. 新增路由
- 检查路径冲突：`/qq/search` vs `/netease/search` 等
- 需要 CORS 吗？已在 app.use(cors()) 全局放开
- 需要鉴权吗？musicapi 层没有鉴权，放行所有

### 5. 修改搜索/播放核心逻辑
- searchQQ: 现用 `c.y.qq.com/soso/fcgi-bin/client_search_cp`（无需 Cookie）
- 旧接口 `u.y.qq.com` 已弃用（需要登录，返回 code:2001）
- `/song/url/v1`: 网易云标准接口
- `/song/url/qq`: QQ URL，需要 authst 参数（qqmusic_key）

### 6. 重启
```bash
# Ctrl+C 停掉，然后重新
npm run dev
# 或只重启 musicapi
cd musicapi && node server.js
```

## 常见报错排查
| 错误 | 原因 |
|---|---|
| `Cannot find module 'xxx'` | 没 install 或 require 路径错 |
| `process.env.MUSIC_QQ_COOKIE undefined` | .env 未加载或路径错 |
| `qqMusic.api is not a function` | setCookie 未调用 |
| 搜索返回空 | Cookie 过期 / API 变更 |