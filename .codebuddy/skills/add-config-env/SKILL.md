---
name: add-config-env
description: >
  Guide for adding environment variables or configuration across the
  full stack: .env, YAML, docker-compose, Windows env vars.
  Trigger when user says "add env var", "configure XX", "set KEY=VALUE".
---

# Skill: 添加环境变量/配置（全栈同步）

## 必须修改的 5 个位置

### 1. .env（根目录）
填真实值。格式：`KEY=value`

### 2. .env.example（根目录）
填占位符。格式：`KEY=your_value_here`

### 3. application-dev.yml / application.yml
Spring Boot 读取：`${KEY:default_value}`
```yaml
my:
  config: ${MY_CONFIG_KEY:default}
```
⚠️ 环境变量优先级高于 yml，Windows 系统变量会覆盖此处！

### 4. docker-compose.yml（如有）
```yaml
services:
  backend:
    environment:
      - MY_CONFIG_KEY=${MY_CONFIG_KEY}
```

### 5. Windows 系统环境变量检查
```powershell
[System.Environment]::GetEnvironmentVariable("KEY", "User")
[System.Environment]::GetEnvironmentVariable("KEY", "Machine")
```
⚠️ 今天踩坑：AI_API_KEY 有 Machine 级环境变量，改了 .env 和 yml 都不生效
→ 必须在 `System Properties → Environment Variables` 里更新

### 6. musicapi/config.js（如果是 Cookie 相关）
```javascript
module.exports = {
  qq: JSON.parse(process.env.MUSIC_QQ_COOKIE || '{}'),
  netease: process.env.MUSIC_NETEASE_COOKIE || '',
};
```

## 改动生效方式
| 服务 | 生效方式 |
|---|---|
| 前端 (Vite) | 热更新自动 |
| musicapi (Node) | process.env 在进程启动时读取 → 必须重启 |
| 后端 (Spring Boot) | @Value 在启动时注入 → 必须重启 |
| Docker | docker-compose up -d 重启容器 |

## 变量命名规范
- 项目级：`VIBEMUSIC_*` 或 `MUSIC_*`
- 第三方：`DEEPSEEK_API_KEY`、`MYSQL_ROOT_PASSWORD`
- 布尔值：`ENABLE_FEATURE=true`