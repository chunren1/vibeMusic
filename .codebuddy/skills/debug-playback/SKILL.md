---
name: debug-playback
description: >
  Structured debug guide for "song not playing", "404/403 stream error",
  "playback failed", "cookie expired" issues. Trigger when user reports
  music playback problems.
---

# Skill: 音乐播放故障排查

## 排查顺序（按概率从高到低）

### Step 1: Cookie 存活检查
```bash
curl http://localhost:3000/cookie-status
```
- `qq: false` → QQ Cookie 过期，需更新 .env
- `netease: false` → 网易云 Cookie 过期
- 注意：checkCookies() 有 bug，`qqRes.list` 空数组也会判 true（已修复为 length>0）

### Step 2: QQ 搜索是否正常
```bash
curl "http://localhost:3000/qq/search?keyword=周杰伦&limit=1"
```
- 返回空 → Cookie 过期或 API 变更
- 新版 API (u.y.qq.com) 返回 code:2001 → 需要登录
- 已改用 c.y.qq.com/soso/fcgi-bin/client_search_cp 经典接口

### Step 3: 后端 Stream 端点
```bash
curl -I "http://localhost:8080/api/songs/stream?sourceId=xxx&name=xxx&artist=xxx"
```
- 404 → getPlayUrl 返回 null（歌源不可用/VIP限制）
- 403 → CDN 域名不在白名单（检查 AUDIO_CDN_WILDCARDS）
- 502/503 → musicapi 挂了

### Step 4: Service Worker 拦截
浏览器 F12 → Application → Service Workers：
- 报错 `Failed to execute 'put' on 'Cache': Partial response (status code 206)`
- SW 把音频 Range 请求的 206 响应误缓存，导致播放失败
- 修复：sw.js 跳过 `dest === 'audio'`

### Step 5: 后端日志
StreamController: `streamFromRemote: playUrl=null` → getPlayUrl 降级全失败
SongPlayService: `歌曲 XX 所有平台均无可用播放链接` → VIP/下架
NeteaseApiService: `获取歌曲 XX URL 失败` → musicapi 连接问题

### Step 6: 死循环（已修复）
- 症状：播放/暂停快速切换，无法切歌
- 根因：error code=4 → retry 2次 → _retryCount 清零 → 又触发 play → 无限循环
- 修复：_errorLocked + 自动 next()

## 已知平台限制
- QQ VIP 歌曲：c.y.qq.com 搜索可返回，但播放需要有效 Cookie + VIP 权限
- 网易云 VIP 歌曲：需要有效 Cookie 且有 VIP 订阅
- CDN 链接有效期：通常 5-15 分钟