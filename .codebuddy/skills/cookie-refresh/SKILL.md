---
name: cookie-refresh
description: >
  Guide for refreshing NetEase and QQ music cookies when they expire.
  Trigger when user says "Cookie过期", "Cookie失效", "更新Cookie",
  "musicapi返回空", or QQ search returns no results.
---

# Skill: 音乐平台 Cookie 刷新

## 网易云 Cookie
### 获取步骤
1. 打开 `https://music.163.com`
2. F12 → Application → Cookies → `music.163.com`
3. 复制 `MUSIC_U` 的值
4. 同时复制 `__csrf` 的值
5. 拼成：`MUSIC_U=xxx; __csrf=yyy`

### 更新位置
`.env` → `MUSIC_NETEASE_COOKIE`
```
MUSIC_NETEASE_COOKIE=MUSIC_U=xxx; __csrf=yyy
```

### 验证
```bash
curl http://localhost:3000/cookie-status
# 应返回: netease: true
curl "http://localhost:3000/search?keyword=周杰伦&limit=1"
# 应有结果
```

## QQ Cookie
### 获取步骤
1. 打开 `https://y.qq.com` → 右上角 **扫码登录**（必须扫码！）
2. F12 → Application → Cookies → `y.qq.com`
3. 复制关键字段（不是 `.qq.com` 域）：

| 字段 | 必须 | 说明 |
|---|---|---|
| uin | ✅ | QQ号 |
| qqmusic_key | ✅ | 音乐密钥（最重要） |
| psrf_qqaccess_token | ✅ | 访问令牌 |
| psrf_qqopenid | ✅ | 开放ID |
| psrf_qqrefresh_token | ✅ | 刷新令牌 |
| psrf_qqunionid | ✅ | 联合ID |
| ptcz | ✅ | 防伪造令牌 |
| qm_keyst | 可选 | 备份密钥 |
| tmeLoginType | 可选 | 登录类型(1或2) |

### 更新格式
`.env` → `MUSIC_QQ_COOKIE`（JSON对象）
```json
{"uin":"1273616219","qqmusic_key":"Q_H_L_xxx","psrf_qqaccess_token":"xxx",...}
```

### 验证
```bash
curl http://localhost:3000/cookie-status
# 应返回: qq: true, qqCookieKeys: 7+
curl "http://localhost:3000/qq/search?keyword=周杰伦"
# 应有结果
```

## 常见问题
- `qqCookieKeys < 5` → Cookie 不完整，可能用了 `.qq.com` 域而不是 `y.qq.com`
- 扫码后才有 `psrf_*` 字段，账号密码登录没有
- Cookie 有效期通常 3-7 天
- 搜索已改用 `c.y.qq.com` 公共接口（无需 Cookie）
- 播放 VIP 歌曲仍需有效 QQ Cookie

## 更新后
1. 重启 musicapi（`Ctrl+C` → `npm run dev`）
2. 验证 `/cookie-status` 返回 true