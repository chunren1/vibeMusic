---
name: cookie-refresh
description: >
  Guide for refreshing NetEase and QQ music cookies when they expire.
  Trigger when user says "Cookie过期", "Cookie失效", "更新Cookie",
  "musicapi返回空", or QQ search returns no results.
  Supports two methods: automatic (Playwright) and manual (F12).
---

# Skill: 音乐平台 Cookie 刷新

## 🚀 自动刷新（推荐）

### QQ Cookie — 一键完成
```bash
node scripts/get_qq_cookie.mjs
```
浏览器窗口自动打开 y.qq.com → 检测登录状态 → 提取 Cookie → 写入 `.env`。
如果你的浏览器已记住登录，整个过程 10-30 秒，无需手动操作。

### 网易云 Cookie — 一键完成
```bash
node scripts/get_netease_cookie.mjs
```
同理，浏览器打开 music.163.com → 自动提取 → 写入 `.env`。

### 重启使生效
musicapi 的 Cookie 监控（15分钟间隔）下次检查时自动加载 `.env` 新值；
如需立即生效：重启 musicapi。

---

## 🔧 自动恢复机制 (musicapi v3.1)
musicapi 每 15 分钟检测 Cookie 状态。检测到过期时：
1. 自动尝试执行 `scripts/get_qq_cookie.mjs` 恢复
2. 自动重载 `.env` 中的新 Cookie
3. 失败时通过 Prometheus 指标 + 日志告警

---

## 📋 手动方法（备用）

### QQ Cookie
1. 打开 `https://y.qq.com` → 扫码登录
2. F12 → Application → Cookies → `y.qq.com`（不是 `.qq.com`）
3. 复制关键字段：

| 字段 | 必须 | 说明 |
|---|---|---|---|
| uin | ✅ | QQ号 |
| qqmusic_key | ✅ | 音乐密钥 |
| psrf_qqaccess_token | ✅ | 访问令牌 |
| psrf_qqopenid | ✅ | 开放ID |
| psrf_qqrefresh_token | ✅ | 刷新令牌 |
| psrf_qqunionid | ✅ | 联合ID |
| ptcz | ✅ | 防伪造令牌 |

4. 更新 `.env` → `MUSIC_QQ_COOKIE={"uin":"xxx","qqmusic_key":"xxx",...}`
5. 验证：`curl http://localhost:3000/cookie-status`

### 网易云 Cookie
1. 打开 `https://music.163.com`
2. F12 → Application → Cookies → `music.163.com`
3. 复制 `MUSIC_U` + `__csrf`
4. 更新 `.env` → `MUSIC_NETEASE_COOKIE=MUSIC_U=xxx; __csrf=yyy`

## 常见问题
- `qqCookieKeys < 5` → Cookie 不完整，检查域名（必须 `y.qq.com`）
- 账号密码登录没有 `psrf_*` → 必须扫码登录
- Cookie 有效期 3-7 天 → 建议设置自动化定期刷新
