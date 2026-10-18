# 核心播放链路测试用例表（TESTCASE-playback-chain.md）

> 覆盖：播放 / 暂停 / 切歌 / 断网 / 来电打断 / 通知控件 / 被杀恢复。
> App 实现锚点：`vibemusic-native` 的 `PlaybackService.kt` + `PlayDiag.kt`（`nextFocusRetryIndex` / `shouldFireRetryProbe`，见 `PlayDiagTest.kt` 20 个 `@Test`）；
> Web 锚点：`vibemusic-web/src/stores/player.js` + `player.test.js`。设计方法缩写：等=等价类，界=边界值，场=场景法。

| 用例ID | 模块 | 前置条件 | 步骤 | 预期结果 | 优先级 | 自动化状态 | 设计方法 |
|---|---|---|---|---|---|---|---|
| PB-001 | 正常播放入口 | 有可播 URL，WiFi 在线 | 点任意歌曲播放 | 3s 内出声，通知栏显示曲名 | P0 | 已覆盖：App 单测 + Web `player.test.js` | 场 |
| PB-002 | 暂停后补试不自动续播 | 播放中 | 点暂停 → 等待超过重试窗口 | 保持暂停，不自动响（`shouldFireRetryProbe`=false：有 userPauseMs） | P0 | 已覆盖：`PlayDiagTest.shouldFireRetryProbe` | 等+界 |
| PB-003 | 被对端打断不自动响 | 播放中，用户主动暂停过 | 收到音频焦点丢失（导航/语音）→ 焦点恢复 | 不自动抢声，等用户手动播 | P0 | 已覆盖：`PlayDiagTest.nextFocusRetryIndex`（playedBefore=true → -1） | 等 |
| PB-004 | 来电瞬断自动续播 | 播放中，无手动暂停 | 模拟来电（焦点瞬断）→ 通话结束焦点回归 | 自动续播，位置误差 <2s | P0 | 已覆盖：`PlayDiagTest`（playedBefore=false → index 0） | 场 |
| PB-005 | 断网切歌降级 | 播放中，断网 | 点下一首 | 走降级链（缓存/备用源），不断播报错；参考后端 `SongPlayDegradeChainTest` 思想 | P0 | 半覆盖：后端降级有单测；端侧手工 | 场 |
| PB-006 | 通知控件播放/暂停 | 播放中 | 点通知栏暂停 → 点播放 | 状态与 App/页内播放器三端一致 | P0 | 手工（真机） | 场 |
| PB-007 | 进程被杀恢复 | 播放中，记住队列 | 杀进程 → 重进 App | 恢复上次队列与进度（误差 <5s），不自动播（等用户点） | P0 | 手工（真机） | 场+界 |
| PB-008 | 弱网重试退避 | 弱网（超时但不断连） | 触发播放失败 → 观察重试间隔 | 按 `FOCUS_RETRY_DELAYS_MS` 退避递增，耗尽后停（index=-1）不再骚扰 | P1 | 已覆盖：`PlayDiagTest`（耗尽边界 → -1） | 界 |
| PB-009 | 切歌清零补试计数 | 一次补试进行中 | 切下一首 | 旧歌重试计数清零，新歌从 retriesDone=0 开始 | P1 | 已覆盖：`PlayDiagTest`（retriesDone=0 → index 0） | 界 |
| PB-010 | 连续快速点按不翻倍 | 列表页 | 300ms 内连点同一首 5 次 | 只发起 1 次播放请求，无重叠音 | P1 | 计划：api-test/（幂等 `IdempotentGuard` 后端已有 `IdempotentGuardTest`） | 界 |
| PB-011 | 暂停态切歌 | 已暂停 | 切下一首 | 新歌保持暂停态加载，不自动播 | P1 | 手工 | 等 |
| PB-012 | 耳机拔出暂停 | 播放中（有线/蓝牙） | 拔耳机 / 断蓝牙 | 自动暂停，`PlayDiag` 记录焦点事件 | P1 | 手工（真机） | 场 |
| PB-013 | 无版权歌曲跳过 | 队列含无版权歌曲 | 播到该歌曲 | 自动跳下一首并 toast 提示，不卡死（后端 `SongPlayCopyrightPrecheckTest` 类思想） | P1 | 半覆盖：后端有单测；端侧手工 | 等 |
| PB-014 | 飞行模式播放入口 | 飞行模式+无缓存 | 点播在线歌曲 | 友好错误页（非白屏/崩溃），开网后可重试成功 | P2 | 计划：api-test/ | 等 |
| PB-015 | 后台播放 30 分钟稳定 | 播放中，切后台 | 锁屏播放 30min | 无中断、无 ANR，Bugly 无新增崩溃，`PlayDiag` 面包屑连续 | P1 | 手工（真机 soak） | 场 |
| PB-016 | 通知栏下一首 | 播放中 | 点通知栏下一首 | 与页内切歌行为一致（计数清零 PB-009 同逻辑） | P1 | 手工 | 场 |
| PB-017 | 来电时用户正暂停 | 已暂停，有未完成补试 | 来电 → 挂断 | 仍保持暂停（用户意图优先于自动续播） | P0 | 已覆盖：`PlayDiagTest`（userPauseMs>0 → false） | 等 |

统计：17 例，P0 7 例（单测已覆盖 5，手工具 2）/ P1 8 例 / P2 2 例。真机手工 8 例集中在发版冒烟 20 例内执行。
