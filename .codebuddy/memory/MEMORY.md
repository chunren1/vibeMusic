# vibeMusic 项目记忆

## 项目概述
全栈音乐学习项目，模拟网易云音乐核心功能。
- 架构：Vue 3 + Spring Boot + MySQL + Redis + RustFS/MinIO
- 前端：`vibemusic-web/` (Vite + Vue3)
- 后端：`vibeMusic-backend/` (Spring Boot + JPA)
- API 网关：`musicapi/` (Express, 端口 3000, 多平台聚合)

## 重要规则
- 修改 Java 后端文件后，必须提醒用户重启后端服务
- 前端 Vue 文件由 Vite 热更新无需重启
- `replace_in_file` 和 `write_to_file` 工具对 `.vue` 和 `.java` 文件经常静默失败，需要用 Node.js 脚本或 PowerShell 脚本作为 workaround
- 修改 SecurityConfig 后也需要重启后端

## 技术决策
- 不使用第三方破解，使用自有 VIP 账号 Cookie 获取音乐
- 自建对象存储实现歌曲离线缓存（RustFS）
- Redis 缓存搜索结果，TTL 1 小时
- 缓存键前缀 `song:search:v2:` (v2 是因为 v1 只有单平台数据)
- 播放历史上限 300 条，自动清理旧数据

## MySQL 优化 (2026-06-22)
- MySQL 慢查询日志已开启：`long_query_time=1`，日志文件 `/var/lib/mysql/slow.log`
- 删除了 3 个冗余索引（playlist_song.idx_playlist_id, user_favorite.idx_user_id, play_history.idx_user_id）
- 新增 2 个索引：song.idx_name(50), song.idx_created_at
- PlaylistMapper 删除了 Java 和 XML 中的重复 SQL，统一在 XML 维护
- ORM: MyBatis-Plus + HikariCP（未引入 Druid）
- Flyway 迁移：V2__optimize.sql

## 当前架构状态
- musicapi (端口 3000): 提供 `/search` 多平台聚合搜索、`/cloudsearch` 网易云单平台、`/song/url/qq` QQ播放URL
- 聚合搜索算法: 网易云权重 1.0，QQ 权重 0.9，同名歌曲 bonus 0.3
- QQ Cookie 需定期更新（存储在 musicapi/server.js 中）
- QQ Cookie 已外置到 musicapi/config.js
- RustFS 缓存兜底策略（2026-06-09 修复）:
  - 播放/流代理优先检查 RustFS 缓存 → API → DB兜底
  - DB 中始终存直接URL（不过期），不再存7天有效的预签名URL
  - stream 端点增加 RustFS 直读兜底（StorageService.getObject）
  - 下载文件名改为 "歌手 - 歌曲名.mp3"

## 内网穿透（Cloudflare Tunnel）
- 域名: www.vibemusic.abrdns.com (注册于 abrdns.com/cloudns.net)
- Cloudflare Tunnel ID: ae061393-aae9-4ced-b40e-3a9818849993
- 启动脚本: scripts/start-cloudflare-tunnel.bat
- 配置文件: C:\Users\靖敏\.cloudflared\config.yml
- CNAME 记录: www → ae061393-aae9-4ced-b40e-3a9818849993.cfargotunnel.com
- 必须先启动前端(5173)再开隧道
