# 2026-10-22 搜索译名失配 + 首播卡顿：根因与修复

> 症状一：搜「圣诞快乐劳伦斯先生」只出杨振龙等中文翻唱，坂本龙一原版消失（英文搜得到）。
> 症状二：没播过/没缓存的歌首播卡顿。
> 本文只记实测与结论，数值均为 2026-10-22 当次测量。

## 一、搜索：两个独立 bug 叠加

### 实测事实

| 观察点 | 结果 |
|---|---|
| 网易上游（中文查询，`cloudsearch`/`search`，limit=40） | 原版 `Merry Christmas Mr. Lawrence` **排第 1**（id 4899152），`tns=["圣诞快乐，劳伦斯先生"]`，`alia=[]` |
| 网关 `/netease/search?keyword=圣诞快乐劳伦斯先生&limit=40` | 原版**在第 1 位**（说明网关没丢结果） |
| 合并后 API（`size=40`） | 原版**完全消失**；前 26 条全是同名中文翻唱，最低分 1.59 |

### 根因

1. **平台内重复项覆盖（决定性）**：网易前 40 里有 6 条同名同歌手（`Merry Christmas Mr. Lawrence / 坂本龍一`），
   去重键 `name|artist` 相同。`addPlatformSongs()` 对 netease 走 `mergeOnDuplicate=false` 分支，
   语义是「后者覆盖前者」→ 名次最低那条（分 ≈0.05）把第 1 名（2.0）冲掉，直接跌出结果集。
2. **译名字段被白名单丢弃**：网关 `search.js` 的网易 mapper 只透传 7 个字段，`tns`（中文译名）从未进入后端，
   于是「中文查询 vs 英文曲名」相关性恒为 0 分；同名中文翻唱却能吃 +1.0/+2.0 的字面命中分。
3. 结果缓存 6h（`song:search:v6:`）会把一次错误排序冻结 6 小时。

### 修复

| 文件 | 改动 |
|---|---|
| `musicapi/src/search.js` | 网易 mapper 透传 `alias`（合并 `tns` + `alia`，去空去重） |
| `vibeMusic-backend/.../dto/SongDTO.java` | 新增加性字段 `alias`（不影响 `{code,message,data}` 信封，老缓存反序列化为 null） |
| `.../service/SongSearchService.java` | `parseAlias()` 宽容解析；`addPlatformSongs()` 重复键改用 `pickBestByScore()` 保留高分条目 |
| `.../service/SongRanking.java` | 相关性打分把 `alias` 与歌名同权（精确 +2.0 / 包含 +1.0） |
| `.../service/SongCacheService.java` | `song:search:v6:` → `v7:`，旧排序整体失效 |
| `musicapi/test/search-cache-regression.test.js` | 公开字段白名单显式加入 `alias`（有意加性字段，非内部泄漏） |
| 新增 `SongSearchAliasRankingTest` | 6 例：译名精确/包含、null 别名不变、`parseAlias` 容错、重复项不被覆盖、端到端原版排翻唱前 |

修复后预期：原版 = 1.5(网易第1) + 0.5(头名) + 2.0(译名精确) + 0.5(非VIP) = **4.5**，翻唱最高 3.2。

### 验证

- 后端 `mvn verify` 全绿（JaCoCo 门禁不降）。
- 网关 `node --test --test-force-exit` 14 文件 155 例全绿。
- 部署后线上回归：中文查询 Top1 = 坂本龍一原版。

## 二、首播卡顿：不是手机，是链路 + 客户端参数

### 三跳实测（同一首歌，13,407,129 B）

| 路径 | TTFB | 全量耗时 | 折算吞吐 |
|---|---|---|---|
| 云上 nginx → 后端（含 SSH 反向隧道 + 上游取链） | **0.24s** | 2.14s | ~6.3 MB/s |
| 公网经 Cloudflare（手机实际路径） | **1.3–2.9s** | 12.5s | ~1.07 MB/s |
| 对照：`index.html` 经 CF | 0.88s | — | — |

- 连续 5 次公网 TTFB：1.72 / 2.93 / 1.62 / 1.36 / 1.53s → **抖动大**，TLS 握手单独就吃掉 0.4–2.2s。
- `cf-cache-status: DYNAMIC`（音频从未被 CF 缓存）；源站已是 `public, max-age=3600` + `206` + `accept-ranges`。
- 服务端每次 `/stream` 都重新取链（无 URL 缓存），最坏 12s 不吐首字节。

结论：**服务器本身和手机都不是主因**；主因是 CF 隧道链路（出口境外，TTFB 2s+、吞吐抖动）+ 客户端参数放大了抖动
（缓冲 15s/30s、起播 1s；`DefaultHttpDataSource` 超时用 Media3 默认 8s，**短于**服务端 12s 预算）。

### 修复（客户端）

| 位置 | 改动 |
|---|---|
| `PlaybackService.kt:playbackLoadControl()` | 15s/30s/1s/2s → **30s/60s/1.5s/4s**（320kbps 下 60s 仅约 2.4MB） |
| `PlaybackService.kt:httpUpstream` | connect 15s / read 20s（原默认 8s/8s）+ `allowCrossProtocolRedirects` |
| `PlaybackService.kt` | 新增 `onPlaybackStateChanged` 缓冲观测：BUFFERING ≥2s 记 `buff-ms:<n>` 进 `PlayDiag`（不碰 vivo 媒体键红线） |

### 需要人工做的一步（无 CF API token）

Cloudflare 控制台 → **Cache → Cache Rules → Create rule**：
1. Rule name：`cache-audio-stream`
2. Custom filter expression：**URI Path** `starts with` `/api/songs/stream`
3. Then → **Cache eligibility: Eligible for cache**；Edge TTL 可留默认（回源头 3600s）
4. Deploy

效果：同一首歌第二次播放（含其他用户）直接命中 CF 边缘，TTFB 从 1.3–2.9s 降到几十毫秒。
注意：cache key 含查询串（`sourceId`/`platform` 等），同名参数一致才共享缓存。

## 三、回滚

- 后端：云上 `docker-compose.demo.yml` + 旧 jar 备份（部署脚本自动 `target` 清理与备份位）。
- 网关：`/opt/vibeMusic/musicapi/src/search.js` 回退为上一提交即可（本改动为重编译镜像）。
- App：本地提交未推送，回退无感。
