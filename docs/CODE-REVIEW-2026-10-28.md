# 代码审查报告 — vibemusic-native

> 审查对象：`2.0.19-ai`（versionCode 97，HEAD `0baaa31`）
> 日期：2026-10-28 · 性质：**只读审查，未改一行代码**
> 规模：57 个 main `.kt` / 83 个测试文件 / 970 例单测 / 3 个 workflow
> 证据标准：每条结论都带 `文件:行号`，且由我或子线**真读过代码**；未取证的标「需确认」。工具链（Gradle 8.10.2 / AGP 8.5.2 / Kotlin 2.0.20 / compileSdk 35）视为冻结，所有建议都不要求升级工具链。

---

## 0. 摘要

**没有发现 P0（必崩 / 必丢数据 / 可直接利用的漏洞）。** 但有 **12 条 P1**，其中 3 条涉及**凭据与隐私**，2 条是**条件崩溃**，2 条让**核心播放场景（离线连播 / 歌词页）在有好数据时反而失效**。

按主题分：

| 主题 | 结论 | 条目 |
|---|---|---|
| 凭据 | 登出只清 DataStore，不清内存 CookieJar → 可能静默登回原账号 | SEC-1 |
| 隐私 | 搜索历史不按账号隔离 → 换账号时上一个人的关键词进入新账号云端 | SEC-2 |
| 崩溃 | `isSameSignature` 用 API 28 才有的 `signingInfo` 字段，API 26/27 抛 `NoSuchFieldError`（`Error` 逃出 `catch (Exception)`） | SEC-3 |
| 播放 | 离线时一个坏本地文件掐断整条队列的自动前进（服务端 `return` + 客户端只弹提示） | PB-1 |
| 播放 | `localFailCounts` 越线后三不管：不自愈、不跳歌、不重下，而文案承诺"联网后重新下载" | PB-2 |
| 状态覆盖 | 历史 / 收藏 / 歌单 / 我的 四屏**没有 error 态**，请求失败被渲染成"你什么都没有" | UI-1 |
| 崩溃 | 4 处 `LazyColumn` 的 key 未带 index，重复 key 会 `IllegalStateException` | UI-2 |
| 可用性 | 歌词页每 500ms 全量重建列表，且自动滚动无条件抢用户手势 | UI-3 |
| 可用性 | 看歌词时自动切歌会被弹回封面（`remember` 的 key 是歌曲 id） | UI-4 |
| 可用性 | 旋转屏幕回到首页；横屏播放页控件被挤出屏幕 | UI-5 |
| 无障碍 | 歌单详情唯一的返回键没有 `contentDescription` → TalkBack 用户出不去 | UI-6 |
| 工程 | **改任何 `.kt` 都不触发 CI**（三个 workflow 的 `paths` 都不含 `app/src/**`） | CI-1 |

另外三条结构性事实，比单点 bug 更值得排期：

1. `MainActivity.kt` **3517 纯 LOC**、104 个 `remember`、71 个局部函数、最深 15 级嵌套 —— 一个 `onCreate` 持有整个应用状态。`docs/REFACTOR-mainactivity.md` 有现成方案，**C1/C4 已做，C2/C5/C6 停着**（§5）。
2. 项目自定规矩「单文件 ≤250 纯 LOC」被 **18 个文件**违反，且**零机器守卫**（无 detekt/ktlint，无自定义断言）（§5.2）。
3. 三处文档数字漂移 + 两处版本号过期，会让下一个 agent 按错的门禁干活（§6）。

---

## 1. 审查方法

四条并行只读线 + 我自己复核：

| 线 | 范围 | 产出 |
|---|---|---|
| 播放与媒体核心 | `PlaybackService` / `MediaCache` / `SchemeDataSource` / `CachePolicy` / `PlaybackStateStore` / `MainActivity` 播放片段 | §3.3 |
| 数据·契约·安全 | `VibeApi` / `AuthStore` / 三个 Store / `UpdateCheck` / Manifest / ProGuard | §3.1、§4.1、§4.2 |
| 机械扫描 | 体量 / 吞错 / 危险写法 / 孪生副本 / CI / 依赖 / 硬编码 | §4.4、§6 |
| UI·UX | 8 个界面文件 + 路由 | §3.2、§4.3 |

**复核方式**：子线给的每条 P0/P1，我都回原文件读行号，能复现的留下，读不到或已被现状推翻的删掉。本报告里没有"子线说了但没人看过"的条目。

---

## 2. P0

**无。**

明确说明为什么几条"看起来像 P0"的不算：

- 外部音频 `content://` 走 HTTP 上游（100% 失败）已在 `2.0.19-ai` 修掉（`8dd0a41`），不在本次范围。
- `isSameSignature` 崩溃只发生在 API 26/27 且用户点"立即更新"，属条件崩溃 → P1。
- 搜索历史跨账号是隐私泄漏，但需要"同一台设备换账号"这一前提 → P1。
- PB-1（离线坏文件掐断队列）不会崩、不丢文件，手动点下一首就能继续 → P1。
- PB-2（`fails` 越线后三不管）只让**单曲**在本次进程内哑掉，且需要"头部合法但解码不了"的坏文件 → P1。

---

## 3. P1

### 3.1 凭据与隐私

#### SEC-1 登出只清 DataStore，不清内存 CookieJar —— 退出登录可能被后台请求"撤销"

**证据**

```kotlin
// VibeApi.kt:364-371 —— 持久化的 refreshToken 空掉后，会回退到 jar 里的 cookie
val rt = AuthToken.refreshToken
val jarCookie = refreshCookieFor(endpoint)
if (rt.isBlank() && jarCookie.isBlank()) return RefreshOutcome.NETWORK_FAIL
val res = try { postRefresh(endpoint, client, rt.ifBlank { jarCookie }) } ...
AuthToken.token = res.token
AuthStore.saveTokens(ctx, AuthToken.token, AuthToken.refreshToken)   // ← 写回磁盘
```

```kotlin
// VibeApi.kt:333 —— 全仓唯一的 cookieJar.clear()，只在测试里用
internal fun clearCookiesForTest() = cookieJar.clear()
```

```kotlin
// AuthStore.kt:67-77 —— clear() 只清 token/refresh/username/nickname
suspend fun clear(context: Context) {
    context.authDataStore.edit { p -> p.remove(KEY_TOKEN); p.remove(KEY_REFRESH); ... }
    AuthToken.token = ""; AuthToken.refreshToken = ""; AuthToken.username = ""
}
```

`grep -rn "cookieJar"` 全仓 5 处命中，**生产路径没有一处调用 `clear()`**。

**触发条件**：登出瞬间仍有在飞的 authed 请求（`pullAndMerge`、`loadPlaylists(background=true)`、`reportPlay`），它们吃到 401 → `trySilentRefresh` → 持久化 refreshToken 已空 → 用 jar 里的 `VIBE_REFRESH` → 后端认 → 新 token 写回 DataStore → 下次冷启动 `AuthStore.load` 静默登回原账号。

**后果**：共享设备上"退出登录"等于没退；且用户看不到任何提示。`doLogout` 的注释与 `AuthStore.clear` 的命名都暗示"清干净了"，实际没有。

**修法要点**
1. `doLogout` 与 `handleAuthLost` 都调 `cookieJar.clear()`（把 `clearCookiesForTest` 改成正式的 `clearCookies()`）。
2. `doLogout` 先取消所有 authed job 再清凭据（顺序反了就等于给在飞请求留窗口）。
3. `trySilentRefreshWith` 加一道闸：`AuthToken.username.isBlank()` 时拒绝使用 jar cookie（"已登出"与"cookie-only 会话"要能区分）。
4. 需确认：后端 `/api/auth/logout` 是否吊销 refresh（注释称 JWT stateless，很可能不吊销）—— 若不吊销，客户端这道闸是唯一防线。

#### SEC-2 搜索历史不按账号隔离 —— 换账号时上一个人的关键词进新账号云端

**证据**

```kotlin
// MainActivity.kt:2303-2316 —— 登出清理清单，没有 searchHistory / SearchStore
currentUser = null; playlists = emptyList(); selectedPlaylist = null
playlistSongs = emptyList(); favIds = emptySet(); favBusy = emptySet()
favSongs = emptyList(); historyItems = emptyList(); lastReportedKey = null
screen = Screen.Login
```

```kotlin
// MainActivity.kt:2124-2126 —— 登录成功后直接 pullAndMerge
SearchHistorySync.pullAndMerge(context)?.let { searchHistory = it }
```

```kotlin
// SearchHistorySync.kt:83-96 —— merge 的 local 来自本机 DataStore，merge 后全量 push
val local = SearchStore.loadHistory(context)
val merged = mergeSearchHistories(local, remote)
SearchStore.saveHistory(context, merged)
VibeApi.pushSearchHistory(merged)      // ← 把 A 的词写进 B 的云端
```

**后果**：A 的私人查询词（可能含姓名、病情、公司内部词）① 登出后在登录页的搜索框仍然可见；② 登录 B 后进入 B 的云端历史，B 换设备也能拉到。

**修法要点**：登出时 `SearchStore.clearHistory` + `searchHistory = emptyList()`（A 的云端副本留在服务端，别 `clearCloud`）；或更彻底：`SearchStore` 按 `userId` 分文件，登录后先清本地再 pull。

#### SEC-3 `isSameSignature` 在 API 26/27 抛 `NoSuchFieldError`（`Error` 不被 `catch (Exception)` 接住）

**证据**

```kotlin
// UpdateCheck.kt:524-543
fun isSameSignature(context: Context, apkFile: File): Boolean {
    try {
        val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        val apkSigners = archive.signingInfo?.apkContentsSigners    // ← API 28+ 字段
        val appSigners = installed.signingInfo?.apkContentsSigners
        ...
    } catch (_: Exception) { return false }                          // Error 逃逸
}
```

`app/lint-baseline.xml:50/72` 已经把这条记成历史债（`Field requires API level 28 (current min is 26)`），所以 lint 永远不会红。

调用点 `UpdateCoordinator.kt:198` **没有 SDK 守卫**：`if (!isSameSignature(context, apk)) { ... }`。

**后果**：minSdk 26 = 安卓 8.0/8.1 在支持范围内。这两版上 `PackageInfo.signingInfo` 字段不存在 → 字段访问抛 `NoSuchFieldError`（`Error` 的子类，不是 `Exception`）→ 逃出 `isSameSignature` 的 catch → 也逃出 `tryInstall` 的 `catch (e: Exception)`（`UpdateCoordinator.kt:184/213`）→ 主线程协程未捕获 → **App 崩溃**。即使不崩，注释也承认 API<28 返回 false，等于 8.x 用户永远装不上更新。

**修法要点**：`Build.VERSION.SDK_INT >= 28` 走 `signingInfo`；26/27 走 `@Deprecated getPackageInfo(..., GET_SIGNATURES).signatures` 比较；catch 改 `Throwable`（或至少 `catch (e: Error)` 单列并记 SilentFail）。修完把 `lint-baseline.xml` 里这两条删掉，让 lint 重新守住。

### 3.2 UI 状态与崩溃

#### UI-1 历史 / 收藏 / 歌单 / 我的 四屏没有 error 态 —— 请求失败被渲染成"你什么都没有"

**证据**

```kotlin
// HistoryScreen.kt:138-149 —— 形参里没有 error
fun HistoryScreen(items: List<VibeApi.HistoryItem>, loading: Boolean, favIds: Set<String> = emptySet(), ...)
// HistoryScreen.kt:176-183 —— 失败与"确实没有记录"共用同一分支
if (items.isEmpty()) {
    Spacer(Modifier.height(16.dp))
    Text("还没有播放记录，去搜一首播吧")
    OutlinedButton(onClick = onRetry) { Text("刷新") }
```

`FavoritesScreen.kt:138-148` 同样没有 `error`。`MineScreen.kt:146` 声明了 `onRetryPlaylists: () -> Unit` 但全文件再无引用（死参数），`:293-300` 加载失败落到 `EmptyStateLine("还没有歌单…")`。

对照：仓库里已经有现成的三态模型 —— `PlaylistDetail.kt:75-103` 六态、`BeautyPhase10.kt:98-106` 的 `selectListState`，只有搜索页和歌单详情在用。

**后果**：断网 / 后端 502 时"我的收藏"显示"还没有收藏"，用户第一反应是"我的收藏被清空了"；"我的"页连重试按钮都没有，只能杀进程。这是**信任级**问题，不是美观问题。

**修法要点**：四个屏加 `error: String?` 形参，用现成 `selectListState(loading, error, isEmpty)` 分派；`ERROR` 分支复用 `DiscoverRetryRow(message, onRetry)`；文案 `"加载失败：$error"`。顺手删掉 `MineScreen` 的死参数。

#### UI-2 `LazyColumn` 稳定 key 只修了一处，其余 4 处重复 key 会崩

**证据**

```kotlin
// SearchScreen.kt:352-356 —— 已修，注释写明了原因
// key 带上 index：randomSongs 不保证 sourceId+platform 唯一，
// 重复 key 会让 LazyColumn 直接抛异常。
key = { index, s -> "guess-$index-${s.sourceId}-${s.platform}" }
```

```kotlin
// SearchScreen.kt:501 —— 未修
itemsIndexed(visible, key = { _, s -> s.sourceId + s.platform })
// DiscoverUi.kt:456 / :501 / :539 —— 未修
key = { _, s -> "daily-" + s.sourceId + s.platform }
key = { _, s -> "guess-" + s.sourceId + s.platform }
key = { _, s -> "encounter-" + s.sourceId + s.platform }
```

**后果**：同一屏内同曲重复（多源聚合去重不严、后端随机接口重复返回）→ `IllegalStateException: Key was already used` → 闪退。注意已修的那处注释明确说"randomSongs 不保证唯一"，而 `daily`/`guess` 走的是同一批随机接口。

**修法要点**：抽 `fun songRowKey(index: Int, song: Song) = "$index-${song.sourceId}-${song.platform}"` 纯函数 + 单测，四处统一调用（顺带把"前缀"参数化，避免再次漏改）。

#### UI-3 歌词页每 500ms 全量重建列表，自动滚动无条件抢用户手势

**证据**

```kotlin
// PlayerScreen.kt:355-361 —— 每次重组都新建 List（无 remember）
val lines = (lyricState as? LyricUiState.Ok)?.lines.orEmpty()
    .filterNot { isBlankLyricLine(it.text) || isMusicSymbolLine(it.text) }
val currentLine = lines.indexOfLast { it.timeSec * 1000 <= effPositionMs }
```

```kotlin
// PlayerScreen.kt:403-410 —— 没有 isScrollInProgress 保护
LaunchedEffect(currentLine, lines, view) {
    if (view == PlayerView.LYRICS && currentLine >= 0) { lyricsListState.scrollToItem(currentLine) }
```

```kotlin
// Karaoke.kt:449-452 vs :463 —— 激活行 titleMedium、非激活行 bodyMedium
Text(text = plain, style = MaterialTheme.typography.bodyMedium, ...)
val style = MaterialTheme.typography.titleMedium
```

**后果**：① 想往上翻看副歌，每换一句就被硬拉回当前行，长按也压不住；② 激活行变大会让整列上下抖动；③ 300 行歌词 × 每 500ms 一次分配 + 全表扫描，低端机耗电掉帧。

**修法要点**：`val lines = remember(lyricState) { ... }`、`val currentLine by remember(...) { derivedStateOf { ... } }`；`scrollToItem` 外包 `if (!lyricsListState.isScrollInProgress)`，用户手动滚动后 5s 内不自动跟随；非激活行也用 `titleMedium`，只改颜色（`KaraokeDim` → `KaraokeLit`）保持行高恒定。

#### UI-4 看歌词时自动切歌会被弹回封面

**证据**

```kotlin
// PlayerScreen.kt:363
var view by remember(song?.sourceId) { mutableStateOf(PlayerView.COVER) }
```

**后果**：用户正在看歌词，一首播完自动下一首 → 画面闪回封面，得再点一次"词"。连播整张专辑时几乎无法停留在歌词页。

**修法要点**：去掉 key（`remember { mutableStateOf(PlayerView.COVER) }`）；若要"新会话回封面"，用显式关闭动作触发，不要挂在歌曲变化上。

#### UI-5 旋转屏幕回到首页；横屏播放页控件被挤出屏幕

**证据**

```kotlin
// MainActivity.kt:222 —— 普通 remember，非 rememberSaveable
var screen by remember { mutableStateOf<Screen>(Screen.Discover) }
// UpdateCheck.kt:279-280 —— 官方文案自己承认了
else "跟随系统 · 旋转会重建界面并回到首页"
// PlayerScreen.kt:699-702 —— 固定 padding 的 Column，无滚动
Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), ...)
```

**后果**：设置里选"跟随系统"后横屏，播放页非加权子项合计约 440dp > 横屏可用约 360dp → 进度条/播放键被推出屏幕；转一下手机直接跳回首页，歌还在响但界面没了。

**修法要点**：`screen` 改 `rememberSaveable` + `Saver`（`PlaylistDetail` 存 id）；`PlayerScreen` 根部加 `verticalScroll(rememberScrollState())`，或横屏改 `Row`（左封面右控件）。

> 需真机确认：横屏到底是"被裁"还是"被压"——`VinylCover(fraction = 0.62f)` 按宽度取尺寸，可能纵向超出。上面的 440dp 是按固定高度累加的推演，未真机验证。

#### UI-6 歌单详情唯一的返回键没有 `contentDescription`

**证据**

```kotlin
// AppIconDrawing.kt:277-278 —— 返回箭头默认标签被显式设成 null（当装饰用）
AppIconKind.CHECK, AppIconKind.CHEVRON_RIGHT, AppIconKind.CHEVRON_LEFT,
AppIconKind.MUSIC_NOTE, AppIconKind.MESSAGE -> null
// PlaylistDetailUi.kt:136-138 —— 但它是这一页唯一的返回入口
IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { AppIcon(AppIconKind.CHEVRON_LEFT, UiMuted) }
```

**后果**：TalkBack 用户在歌单详情页找不到"返回"（读屏只念"按钮"或直接跳过），只能杀 App。

同类（同一修法，一并处理）：`UiAtoms.kt:636-641` 的 `FavHeart` 已收藏仍读"收藏"，用户无法判断点下去是收藏还是取消。

**修法要点**：详情页返回传 `contentDescription = "返回"`；`FavHeart` 传 `if (faved) "取消收藏" else "收藏"`；下载键按状态给"下载中/已下载/下载"。

### 3.3 播放与媒体核心

#### PB-1 离线时一个坏本地文件会掐断整条队列的自动前进（两端都不接手）

**证据**

```kotlin
// PlaybackService.kt:682-687 —— 服务端把 local: 完全交出去
val isLocal = exo.currentMediaItem?.mediaId?.startsWith("local:") == true
if (isLocal) return
```

```kotlin
// CachePolicy.kt:49-53 —— 离线 + local → 只报错，不跳
fun localErrorDecision(mediaId: String, isOnline: Boolean): LocalErrorAction = when {
    !mediaId.startsWith("local:") -> LocalErrorAction.SKIP
    isOnline -> LocalErrorAction.DELETE_AND_STREAM
    else -> LocalErrorAction.ERROR_MESSAGE
}
// MainActivity.kt:2546-2548 —— 该分支只弹提示
LocalErrorAction.ERROR_MESSAGE -> { showError("本地文件已损坏，联网后重新下载") }
```

服务端注释（`PlaybackService.kt:678-681`）写的理由是"Skipping here would race the heal and burn the queue"——在线时成立；**离线时 heal 什么都不做**，于是没人推进。讽刺的是安全前进的机制（`selectNextOfflineIndex`，`:139-163`，会排除当前项防死循环）就在同一个文件里，只服务非 `local:` 分支。

**后果**：离线状态下列表里只要有一个截断/损坏的下载，播到它就停住，后面所有歌**一首都不会自动响**，必须手动点下一首。离线连播这个核心场景直接失效。

**修法要点**：离线 local 错误走与 stream 错误同一条单属主前进路径（服务端已有 `buildOfflineAvailability + selectNextOfflineIndex`），提示文案保留；不要两端各写一半。

顺带：`CachePolicy.kt:74` 的 `!isOnline -> HealAction.ERROR_MESSAGE` 是**不可达死分支** —— 外层 `localErrorDecision` 已用同一个 `online` 判过，进到 `healAction` 时必然在线。这也印证了"离线 local 错误无人接管"。

**✅ 已修（2026-10-28，PB-1）**

- 新增纯函数 `defersLocalErrorToHeal(mediaId, isOnline)`（`CachePolicy.kt`）= `local:` **且在线**才放手。`PlaybackService.onPlayerError` 原来无条件 `if (isLocal) return`，改成 `if (defersLocalErrorToHeal(mediaId, online)) return` —— **离线时 local 项落进与 stream 错误同一条单属主离线跳歌路径**（`buildOfflineAvailability` + `selectNextOfflineIndex`），Activity 只报告不推进。
- 顺手补了这条路径的**上界**：离线跳歌原来看 `target >= 0` 就跳，两个坏文件 + 列表循环会互相弹跳、永远在报错。现在复用既有的 `shouldAutoSkip(consecFails, target >= 0)`（`consecFails < 3`），连续 3 次失败即停（`consecFails` 在 `onIsPlayingChanged(true)` 归零，语义仍是"连续"）。
- Activity 的离线 local 文案由「本地文件已损坏，联网后重新下载」改为「本地文件已损坏，离线无法播放」—— 离线时没人会去重下，旧文案承诺的是不会发生的事。
- 真机待验：飞行模式 + 一个截断的下载文件，确认播到它时**自动跳下一首**、连播不断。

#### PB-2 `fails` 越线后三不管：不自愈、不跳歌、也不重下 —— 而文案承诺了"联网后重新下载"

**证据**

```kotlin
// MainActivity.kt:2492-2497
val fails = (localFailCounts[key] ?: 0) + 1
if (fails > LOCAL_HEAL_QUIET_AFTER) {          // 4
    showError("本地文件已损坏，联网后重新下载")
    return                                      // 不转在线、不删、不跳、不重下
}
```

```kotlin
// CachePolicy.kt:73-78 —— fileValid 分支在阈值判断之前，所以"头部合法"的文件永远走 KEEP_STREAM_ONCE
fun healAction(fileValid: Boolean, consecFails: Int, isOnline: Boolean): HealAction = when {
    !isOnline -> HealAction.ERROR_MESSAGE
    fileValid -> HealAction.KEEP_STREAM_ONCE
    consecFails >= LOCAL_HEAL_DELETE_THRESHOLD -> HealAction.DELETE_AND_STREAM
    else -> HealAction.KEEP_STREAM_ONCE
}
```

`OfflineStore.isAudioFileIntact` 只读 4 字节 + 大小门，**截断体完全能过**。这种文件会稳定累积到第 5 次，然后走上面那条 `return`。`redownloadSong` 只在 `DELETE_AND_STREAM` 分支调用（`:2527`），所以"联网后重新下载"从未发生。

`localFailCounts` 的清理点只有 4 处（`MainActivity.kt:1087/1332/1409/2382`），最后一处是"local 项真的出声了"——而它已经不可能再出声。**本次进程内该曲永久哑掉。**

**修法要点**：到顶后要么真删（`OfflineStore.delete` + `redownloadSong`），要么交给 PB-1 的离线跳歌路径前进；不要留下"只弹提示"的第三态。文案要么改成真话（"本地文件损坏，请手动删除后重下"），要么让它成真。

**✅ 已修（2026-10-28，PB-2）—— 选"让文案成真"**

- 根因在 `healAction` 的分支顺序：`fileValid` 排在阈值之前，而 `isAudioFileIntact` 只读 4 字节 + 大小门（截断体能过）→ 这种文件**永远**走 `KEEP_STREAM_ONCE`，攒到第 5 次后撞上 `MainActivity` 的 `fails > LOCAL_HEAL_QUIET_AFTER` 只弹提示就 `return`。
- 改法：常量改名 `LOCAL_HEAL_QUIET_AFTER(4)` → `LOCAL_HEAL_FORCE_DELETE_AFTER(5)`，`healAction` 里 `consecFails >= 5` **先于** `fileValid` 判断 —— 连续失败越线 = 探针不可信，一律按确认中毒处理（删 + 转在线 + `redownloadSong`）。`MainActivity` 的"只弹提示"第三态整段删除，`when` 不再有"什么都不做"的分支。
- `DELETE_AND_STREAM` 分支的计数器由 `key to fails` 改为**清掉该 key**：文件已不在，计数属于旧文件；重下若又是个坏文件，会重新攒满一轮预算，而不是"第一次失败就立刻再删"。
- 真机待验：截断的下载文件连播 5 次后应看到「本地文件已损坏已删除，转在线播放」，且下载列表里该曲重新出现下载中 → 完成。

PB-3 起为播放线的 P2（弱网重试撤销暂停、预热字节骗过离线门、自愈用陈旧下标、单曲循环假跳过、shuffle 下离线跳错、controllerGen、consecFails 混账），统一收在 §4.5。

---

## 4. P2

### 4.1 契约健壮性（API）

| ID | 结论 | 证据 | 后果 / 修法 |
|---|---|---|---|
| API-1 ✅ | `parseToggleResult` 把"data 缺失"当"已取消收藏" | `VibeApi.kt:1416-1425`：`if (root.isNull("data")) return false`（`isNull` 对"键不存在"也返回 true） | 后端省略 data → 收藏成功被读成取消，本地心形反向更新。改：`!root.has("data") \|\| root.isNull("data")` 抛协议错误 |
| API-2 ✅ | `parseFavIds` / 删除计数用 `opt*` 兜底，把契约变更吞成空集/0 | `VibeApi.kt:1429-1432`（`optJSONArray("data") ?: return emptySet()`）、`:1842-1845`、`:1858-1861`（`else -> 0`） | 后端把 data 改成 `{ids:[…]}`/`{count:3}`（本仓库常见的加性演进）→ 心形全空、toast"已移除 0 首"而服务端已删。改：与 `listData`（`:583-593`）同策略，形状不符即抛 |
| API-3 ✅ | 信封级 401（HTTP 200 + `code:401`）不走静默续期 | `VibeApi.kt:568-570` `checkEnvelope` 抛 `AuthException`；而 `myPlaylists()` 是 `rawGet(...)` 之后**在 `authed` 之外**调 `checkEnvelope`（`:1003-1007`） | 若后端改用 200+code:401 表达过期，所有列表调用会直接清 token 登出。改：把 `checkEnvelope` 挪进 `authed`，与 `me()` 同构。**需确认后端实际返回哪种**（当前两条路各走一半） |
| API-4 ✅ | `AuthStore.save` 在 refreshToken 为空时保留旧值 | `AuthStore.kt:51/55`：`if (refreshToken.isNotBlank()) p[KEY_REFRESH] = refreshToken` | 登录响应当次没带 `Set-Cookie` 时，新账号沿用旧账号的续期凭据。改：`save` 语义改为整体替换（`remove` + 清内存）；`saveTokens` 的轮转语义另说 |

**✅ 已修（2026-10-28，第 3 批）**

- **API-1**：`parseToggleResult` 现在 `!root.has("data") || root.isNull("data")` 一律抛 `data missing`（不再把"契约缺失"读成"已取消收藏"）。
- **API-2**：`parseFavIds` 改走既有的 `listData(root, "Favorite ids", "ids")`（形状不符即抛）；新增 `parseRemovedCount`（接受 `Number` 或 `{count:N}`，其余抛）。三处 `opt*` 兜底全部移除。
- **API-3**：**已实测后端源码定性** —— `SecurityConfig` 把 `GET /api/songs/…` 放成 `permitAll`，`PlayHistoryController.java:39` 直接 `return Result.error(401)`（无 `@ResponseStatus`）→ **HTTP 200 + body `code:401` 真实可达**，不是"潜在"。修法：在 `rawGet` / `rawPost` / `rawPut` / `rawDelete` 四个 `authed { }` 包装内调 `throwIfEnvelopeAuth(body)`（`internal`），一处覆盖全部调用点，与 `me()` 同构。**注意 Kotlin 块注释可嵌套**：KDoc 里写 `/api/songs/**` 会开出未闭合注释（编译报 `Unclosed comment`），必须写 `/api/songs/…`。
- **API-4**：`AuthStore.save` 改为整体替换（refreshToken 为空则 `p.remove(KEY_REFRESH)` + 清内存），`saveTokens` 的轮转语义不动（它本来就是"只换 token"）。
- 测试：新增 `EnvelopeAuthTest`（3 例）+ `AccountFavHistoryTest` +5 + `AuthStoreTest` +2。**`EnvelopeAuthTest` 必须标 `@RunWith(RobolectricTestRunner::class)`**：不标时 `org.json` 是 android.jar 的 stub，抛 `RuntimeException("Stub!")` 被 `catch (_: Exception)` 吞掉 → 假绿。

### 4.2 自更新（UPD）

| ID | 结论 | 证据 | 后果 / 修法 |
|---|---|---|---|
| UPD-1 | **`checkOnLaunch` 先写 24h 时间戳再拉取** —— 一次网络失败烧掉整个窗口 | `UpdateCoordinator.kt:91-104`：`saveLastUpdateCheck(context, now)` 在 `fetchLatestForChannel` 之前 | 用户断网启动一次 → 24 小时内不再检查（手动检查不受影响）。改：拿到 release 之后再写时间戳；失败记 `lastCheck` 为"上次成功"而非"上次尝试" |
| UPD-2 ✅ | 测试通道下载回退会拿"正式版 latest"的 APK | `UpdateCoordinator.kt:161-168` + `UpdateSupport.kt:31-33`：`otherUpdateSourceUrl` 指向 `.../releases/latest`（排除 prerelease） | `rel` 来自含 prerelease 的列表，回退源却是正式版 → 版本不同。Gitee 资产无 `size` 时尺寸门退化成 Content-Length，混版文件照样通过签名门。改：回退 URL 带上同 tag；安装前断言 `versionCode >= 当前` |
| UPD-3 ✅ | 双源只取"第一个成功"，不做版本比较 | `UpdateCheck.kt:207-208` `resolveUpdateSource`、`:229-237` 成功即 return | Gitee 滞后一个版本时永远"已是最新"（与 2026-10-27 事故同一失效面）。改：两源都取，`isNewerVersion` 取大者 |
| UPD-4 ✅ | 自更新不校验 body 里的 sha256、不限制下载 host | `UpdateCheck.kt:305-315` `pickApkFromAsset` 接受任意 host；`:128-135` body 里的 sha256 只展示不校验 | 底线仍是签名门（同签名才能装），所以恶意包装不进去；但**降级/错版本**无拦截，账号被盗时会把用户引到第三方 host。改：解析 body sha256 落盘后校验；host 白名单（gitee.com / github.com / objects.githubusercontent.com） |

**✅ 已修（2026-10-28，第 3 批）**

- **UPD-2**：`otherUpdateSourceUrl(primaryApkUrl, tag)` 改拼**单 tag** 端点（`UPDATE_GITHUB_TAG_URL_BASE` / `UPDATE_GITEE_TAG_URL_BASE`），回退源与主源必是同版本；安装前新增两道门 —— `readApkVersionCode`（API 28+ 用 `longVersionCode`，26/27 用废弃字段，与 `packageSigners` 同一处分叉）+ `isInstallableUpdate(apk >= 当前)`，坏包 `deleteQuietly` 后报错（不删的话 `isDownloadComplete` 会认为已下完，重试只会反复撞同一个坏文件）。
- **UPD-3**：`pickBestRelease(gitee, github)` 取版本大者、相等优先 Gitee；`fetchBestOf` 并行 `async` 两源。**镜像只给 `UPDATE_MIRROR_GRACE_MS = 2500L` 宽限**并显式 `h.cancel()` —— 无条件 `await` 两边会让"检查更新"从 1s 变 15s（`api.github.com` 在国内常年不可达，connect 超时 15s）。`resolveUpdateSource` 保留但 KDoc 已标注"只表示谁应答，选择策略已被 `pickBestRelease` 取代"。
- **UPD-4**：`isAllowedUpdateHost` 白名单 `{gitee.com, github.com, objects.githubusercontent.com}`，匹配 `host == it || host.endsWith(".$it")`（子域放行、`github.com.evil.com` 拒绝）；`pickApkFromAsset` 内拒绝并 `Log.w`，`parseLatestRelease` 把 `rejectedHost` 记进错误文案（否则线上只看到没头没尾的 "no .apk asset"）。`parseSha256FromBody` 从 release notes 抽 `APK sha256: <反引号包裹的 64 位 hex>`（正则 ``(?i)sha256[\s:：`=]*([0-9a-f]{64})``，与 `release.yml:216-219` 同格式），`verifyApkSha256` 落盘后校验；缺字段跳过（可选字段不能变硬要求）。
- 测试：新增 `UpdateIntegrityTest`（20 例）；`UpdateWiringTest` 两例改为同 tag 断言；`UpdateCheckTest` / `UpdateChannelTest` / `UpdateSourceTest` 的 fixture host 由 `*.example` / `example.invalid` 换成 `gitee.com` / `github.com`（**白名单是行为的一部分，fixture 也必须落在白名单内**）。

### 4.3 UI 一致性（UIX）

| ID | 结论 | 证据 | 修法 |
|---|---|---|---|
| UIX-1 | 播放页「封面」与「歌词」是两套手写控件：主播放键 56dp vs 64dp、关闭键右上 vs 左上 | `PlayerScreen.kt:651-663`（歌词分支 56dp 手写 Box）vs `:875-881`（`HeroControls(heroSize = 64.dp)`，默认值 `:140`） | 抽 `PlayerControlDeck(heroSize = 56.dp)` 两分支共用；关闭键统一左上 |
| UIX-2 | 触达区标准不统一：`MIN_TOUCH_DP = 44` + M3 默认 40dp 按钮 + 约 38dp 联想行 | `BeautyPhase10.kt:43`、`:284/293`；`SearchScreen.kt:235-240`；对照 `UiAtoms.kt:447` 行内按钮 48dp | 常量改 48；`Button/OutlinedButton` 统一 `heightIn(min = 48.dp)`；联想行 `heightIn(min = 48.dp)` |
| UIX-3 | `SettingsRowShell` 把 `padding` 写在 `clickable` 前，整行上下 8dp 点不到 | `BeautyPhase10.kt:403-408`：`.heightIn(min=56.dp).padding(vertical=8.dp)` 之后才 `.clickable` | 调换顺序：`clickable` 在前、`padding` 在内 |
| UIX-4 | 切 Tab 用 `Crossfade` 销毁旧屏，所有列表滚动位置与多选态丢失 | `MainActivity.kt:3289-3293`；全仓无 `rememberLazyListState` 实际使用 | 用 `SaveableStateHolder().SaveableStateProvider(screen)` 包住 `when`，或把 listState 提到 Activity |
| UIX-5 | 设置页把"后台保活"讲了两遍；"最近 5 条诊断"被 `maxLines = 2` 截掉 | `SettingsScreen.kt:501-514` 与 `BeautyPhase10.kt:202-211`；`BeautyPhase10.kt:420-426` 的 `maxLines = 2` | 删重复行；`SettingsRowShell` 加 `subtitleMaxLines` 参数 |
| UIX-6 | 空状态两套实现，且"去搜一首"旁边是「刷新」按钮 | `HistoryScreen.kt:176-183`（裸 Text + 刷新）vs `BeautyPhase10.kt:270-288`（`EmptyStateLine`） | 统一用 `EmptyStateLine(text, actionLabel = "去搜索", onAction, secondaryActionLabel = "刷新")` |
| UIX-7 | 搜索页空态写"下拉刷新试试"，但这页没有下拉刷新（全仓只有 `DiscoverUi.kt:368` 一处） | `SearchScreen.kt:371-375` | 改「换一批」+ `onRetryGuess` |
| UIX-8 | 同一概念四种叫法：「最近播放」「最近」「历史」「播放记录」；首页"偶遇心动单曲"复用的就是"每日推荐"那份列表 | `UiAtoms.kt:322/731`、`HistoryScreen.kt:161/225/178`、`AppIconDrawing.kt:273`；`DiscoverUi.kt:536-547` | 全站统一「最近播放」；「偶遇」换独立数据源，拿不到就整段隐藏 |
| UIX-9 | 设计 token 三份拷贝，副标题颜色/圆角/骨架屏各写各的 | `UiTheme.kt:9-15` / `UiAtoms.kt:129-137` / `BeautyPhase10.kt:36-40` 同组颜色三份；`SettingsScreen.kt:159-164` 副标题没设 color；圆角 8/10/12 混用；横滑卡片位用 `SearchSkeleton`（`DiscoverUi.kt:446-448`） | `UiTheme.kt` 作唯一 token 源；副标题统一 `UiMuted`；封面圆角统一 12dp；加 `CardRowSkeleton` |

### 4.4 代码健康（CODE）

| ID | 结论 | 证据 |
|---|---|---|
| CODE-1 ✅ | `currentMediaItem!!` 在队列空时 NPE，被外层 `catch (Exception)` 吞掉 → 播放计数/上报静默丢失 | `MainActivity.kt:2434` + `:2437` 的 catch |
| CODE-2 ✅ | 播放/下载失败文案各有 2-3 份孪生字面量（且半角冒号与全仓全角不一致） | `MainActivity.kt:826 / :932 / :1043`（`"播放失败: "`）、`:1092/:1343`、`:1097/:1350` |
| CODE-3 ✅ | 下载流程两份独立实现（`redownloadSong` vs `downloadSong`） | `MainActivity.kt:1075-1100` vs `:1300-1355` |
| CODE-4 ✅ | 播放上报判定散在 3 个函数（`shouldReportPlay` / `maybeReportPlay` / `maybeCountPlay`），各自判 `sourceId` / `isExternalSong` / `playKey` | `VibeApi.kt:1496`、`MainActivity.kt:1750`、`:1357` |
| CODE-5 ✅ | 搜索历史去重写了 3 遍（单条插入 / 两表合并 / decode），折叠规则共用但去重不共用 | `SearchStore.kt:65`、`SearchHistorySync.kt:18`、`SearchStore.kt:88` |
| CODE-6 ✅ | 后端域名硬编码两处、格式不同 —— **漏改 `Song.kt` 会"登录正常但所有播放流 404"** | `VibeApi.kt:266` `BASE_URL`、`Song.kt:23` `.authority("vibe.cyk666.top")` |
| CODE-7 ✅ | 6 处用墙钟 `System.currentTimeMillis()` 算间隔，与 `PlaybackService` 的 `elapsedRealtime` 两套并存（用户改系统时间会让节流失真） | `MainActivity.kt:975/1082/1325/1384/2991/3027`、`PlayerScreen.kt:415`；对照 `PlaybackService.kt:578/601/620/628/660/1068/1082` |
| CODE-8 ⏸ 不改 | 中文字符串字面量直写（实测 ≥742 处，粗略正则单行统计；`strings.xml` 只有 4 行）。无 i18n 计划则可接受，但孪生文案使"改一处漏一处"必现 | `grep -oP '"…CJK…"'` 统计 |
| CODE-9 ✅（4/5 原为误报） | 测试缺口 top5：`betaNumber`/`pickApkFromAsset`、`songFromJsonObject` round-trip、`Discover.kt` 整套解析、`sanitizeFilePart`、`UpdateCoordinator` 整个 class | 全仓符号↔测试映射。**复核后**：前四项已被 `UpdateCheckTest`（beta 6 例）/ `UpdateIntegrityTest`（host 拒绝 2 例）/ `HotspotStoreTest`（JSON 往返）/ `DiscoverTest` / `OfflineStoreTest`（文件名清洗）**行为级覆盖**；真缺口只有 `UpdateCoordinator` |
| CODE-10 ✅ 部分 | 依赖可升级 4 项 | media3 1.9.3→**1.9.4（已升，`verify-deps.sh` 实测 minCompileSdk=35）**；activity-compose 1.10.1 / robolectric 4.17 / bugly 4.1.9.3 **评估后不升**，理由见下 |

**✅ 已修（2026-10-28，第 4 批）**

- **CODE-1**：`MainActivity` 的 `c.currentMediaItem!!` 改 `?.let { songFromMediaItem(it) }` —— 队列空（服务端被系统重建、时间线还没物化）时不再 NPE 后被外层 `catch` 吞掉、静默丢掉播放计数与上报。
- **CODE-2**：新增 `ErrorCopy.kt`（`DOWNLOAD_FAILED_PREFIX` / `DOWNLOAD_SAVE_FAILED_MESSAGE`）；`diagnosableError` 冒号统一为**全角「：」**，并把 `MainActivity` 里 **33 处** `showError("<前缀>: ${friendlyNetworkMessage(e)}")` / `showError("<前缀>: ${e.message ?: …}")` 全部改走 `diagnosableError("<前缀>", e)`（顺带获得"附原始报错"的诊断能力）。`DiagnosableErrorTest` 6 处断言同步。
- **CODE-3**：新增 `OfflineStore.downloadToStore(context, song): Boolean`（suspend，IO 线程），三处下载（`downloadSong` / `redownloadSong` / `maybeCountPlay` 的常听自动离线）共用；网络异常原样抛出、错误文案留在调用方（三处文案本就不同）。
- **CODE-4**：新增 `Song.kt` 顶层纯函数 `librarySongOrNull(song)`（曲库身份：非空 + `sourceId` 非空白 + 非外部导入）与 `VibeApi.shouldRecordOnce(key, lastKey)`（"同一首只记一次"）；`maybeCountPlay` / `maybeReportPlay` 都建在这两条之上，`shouldReportPlay` = `loggedIn && shouldRecordOnce(...)`。
- **CODE-5**：新增 `dedupeSearchHistory(list, cap)`（trim / 丢空 / 去重保首次 / 截断），`foldPrefixHistory` / `SearchStore.decode` / `mergeSearchHistories` / `VibeApi.buildSearchHistoryBody` 四处共用。**读侧不做前缀折叠**（折叠是写侧的事，读侧改了历史却不落盘反而更乱）。`mergeSearchHistories` 由"先各自去重再折叠再截断"改成 `foldPrefixHistory(local + remote).take(cap)` —— 折叠腾出的位置会被 remote 补上。
- **CODE-6**：新增 `ApiConfig.kt` 的 `const val API_HOST`，`VibeApi.BASE_URL = "https://$API_HOST/"`、`Song.streamUrl()` 用 `.authority(API_HOST)`。`ApiHostTest` 断言两边 host 相同（漏改一处就是"登录/列表正常、所有播放流 404"，因为流地址不走 Retrofit 的 baseUrl）。
- **CODE-7**：只换**两端都在内存**的间隔 —— `lastSavedMs`（位置保存节流）与 `lastGestureMs`（播放页手势去抖）改 `SystemClock.elapsedRealtime()`。`*LastLoaded` 家族与 `saveBytes` 时间戳**必须留墙钟**：`HotspotStore` 磁盘 ts 会播种 `*LastLoaded`，并与 `shouldBackgroundRefresh(…, System.currentTimeMillis())` 比较，换成单调时钟会让每次进页都判 stale 重拉。
- **CODE-8 ⏸ 不改**：无 i18n 计划（`strings.xml` 只有 4 行，产品也未提多语言）。抽取成本 ≥742 处且会把中文文案推到资源文件、破坏"改文案即读得懂 diff"的现状；孪生文案这条**已由 CODE-2 的常量 + `diagnosableError` 单点收口**解决掉真正的风险（改一处漏一处），剩下的只是"字面量在 Kotlin 里"这一形式问题。若将来要做多语言，应先加 `strings.xml` + lint `HardcodedText` 门，再批量迁。
- 测试：新增 `ApiHostTest`（9 例：host 一致 / baseUrl 尾斜杠 / 曲库身份 4 例 / `shouldRecordOnce` 2 例 / `shouldReportPlay` 叠加门）+ `SearchStoreTest` +5（`dedupeSearchHistory` 4 例 + 折叠与清洗共用 1 例）。

**CODE-10 决策（2026-10-28）**

- **media3 1.9.3 → 1.9.4：已升**。`scripts/verify-deps.sh` 解包 AAR 实测 `minCompileSdk=35`（与项目 compileSdk 一致）；`testDebugUnitTest` 1057 例全绿、`lintRelease` 0 error 0 warning、`assembleDebug` + `assembleRelease`（R8）通过，release APK 4,240,185 B、证书 `873ba7ca…` 未变。
- **顺带关掉 lint 的 `GradleDependency`（2026-10-29 实测发现）**：升级 media3 后 `lintRelease` 冒出 **8 条新 warning**。原因是这条检查把**旧版本号写进 message**（`than 1.9.3 is available: 1.11.1`），而 baseline 按 message 匹配 —— 依赖一升，整批条目（本次 27 条 / 286 行）全部失效并变成新 warning；上游发布新版本号同样会让它再次失效。版本升级在本仓库已由 `AGENTS.md`（工具链冻结）+ `scripts/verify-deps.sh`（断言 `minCompileSdk ≤ compileSdk`）管，lint 这条提示不可行动，故在 `app/build.gradle.kts` 的 `lint {}` 里 `disable += "GradleDependency"`（附理由注释）并重生成 baseline（−286 行）。收益：lint 输出不再依赖"能否连上 Maven metadata"，门禁结果可复现。
- **activity-compose 1.9.3 → 1.10.1：不升**。实测 AAR metadata `minCompileSdk=35`（能装），但收益只有新增 `LocalActivity` CompositionLocal（类表 diff 仅多 `LocalActivityKt`），`activity` 核心 1.10.1 的 `ComponentActivity` / `EdgeToEdge` 公开签名与 1.9.3 **逐字相同**（javap diff 为空）——本仓库没有"拿不到 Activity"的痛点，升级只换来一批内部 bugfix，却要动 Compose 侧依赖。
- **robolectric 4.14.1 → 4.17：不升**。官方 release notes（GitHub API 实取）：支持 SDK 37；破坏性变更 `AndroidVersions` 移除、`ShadowCameraCharacteristics.set` 签名收紧；JDK ≥ 17 可能需额外 `jvmFlags`。本仓库单测固定跑 compileSdk 35、不使用这两个 API → 收益≈0，风险全落在测试基础设施。
- **bugly 4.1.9 → 4.1.9.3：不升**。实测 AAR：类列表与 4.1.9 **完全一致**（无新 API），差别是新增 `x86` / `x86_64` 的 `libBugly_Native.so`（解包 1.8 MB → 2.2 MB）。对 ARM 真机零收益，反而增大包体。

**✅ CODE-9 已修（2026-10-28，第 5 批）**

- **先复核再补测**：CODE-9 的 top5 里有 4 项是"符号↔测试名映射"造成的误报 —— `betaNumber` 由 `isNewerVersion` 的 6 个 beta 用例覆盖、`pickApkFromAsset` 由 `UpdateIntegrityTest` 的 host 拒绝用例覆盖、`songFromJsonObject` 由 `HotspotStoreTest` 的往返断言覆盖、`Discover.kt` 有整个 `DiscoverTest`、`sanitizeFilePart` 由 `OfflineStoreTest.filename_*` 覆盖。
- 真缺口 `UpdateCoordinator`：给构造函数加 `fetchLatest` 测试接缝（默认 `::fetchLatestForChannel`，放在 `onError` **之前**，避免现有尾随 lambda 绑错参数），新增 `UpdateCoordinatorTest`（10 例）钉住三条事故行为 —— 拉取失败不推进 24h 窗口、窗口内零请求、测试通道走 beta 端点；另覆盖手动检查的成功/失败、`dismiss`、`selectChannel` 持久化。
- `SongTest` +3：JSON 往返保 `vip`、缺 `platform` 回落 `netease`、坏 JSON 返回 null（而不是空表 —— 空表会让 UI 显示"没有歌曲"而不是重拉）。
- 断言必须**轮询等待**协调器里 `scope.launch` 完成（`awaitTrue { … }`），不能用固定 `delay`：本机快、CI 慢，固定 sleep 会假红。
- 验收：`testDebugUnitTest` **1037 例全绿**（1023 → +14）；`lintRelease` 0 error（仍只有 2 条 Information）；`assembleDebug` 通过（24,135,819 B）；`scripts/check-file-size.sh` 通过。

### 4.5 播放相关（PB）

| ID | 结论 | 证据 | 后果 / 修法 |
|---|---|---|---|
| PB-3 ✅ | 弱网延迟重试只校验"还是同一首"，**不校验用户是否刚暂停** → 静默把暂停撤销 | `PlaybackService.kt:788-804`：`stillSame` 只比 `currentMediaItemIndex` + `mediaId`，随后 `cur.prepare(); cur.play()` | 错误出现后 800ms/1600ms 内按暂停（或来电被 `AUDIO_FOCUS_LOSS` 停掉）→ 被强行拉起。改：延迟体补一条与 `shouldFireRetryProbe` 同源的意愿门（`playWhenReady \|\| isPlaying`） |
| PB-4 ✅ | 256KB 预热字节让"没下完"的歌通过离线点播门 → 离线点歌必在歌中间炸 | `MediaCache.kt:63` `PREFETCH_WARM_BYTES = 256KB`；`OfflineAvailability.kt:73` `cachedBytes(...) > 0L` 即算 `isCached`；`OfflineAvailability.kt:105-106` `isGatePlayable`；`PlaybackService.kt:590/669` 每次切歌都 `prefetchNext()` | 离线点"下一首"必过门，播 4-6 秒命中空洞报错 → "该歌曲未缓存完整"（`MainActivity.kt:2623`）。感知是"离线总在歌中间断"而非"未缓存"。改：门槛改"可连续读到的前缀 ≥ 阈值"，别让预热字节参与 offline 判定 |
| PB-5 ✅ | 自愈路径跨 IO 间隙用陈旧 index，越界兜底把整条时间线换成 1 首 | `MainActivity.kt:1050-1056`：`if (idx in 0 until cc.mediaItemCount) replaceMediaItem(idx, …) else cc.setMediaItem(streamItem)`；调用处 `:2512/:2534` 的 `idx` 抓自错误回调，中间隔了 `isAudioFileIntact` 的 IO hop，**无任何身份复核** | (a) 探测期间用户点了别的歌 → 把别人位置替换成本曲 stream，本曲重复、被点那首丢失；(b) 用户删掉该项 → `idx == mediaItemCount` → 队列压缩成 1 首。改：落地时重读 `currentMediaItemIndex`/`mediaId` + `playGen` 做身份校验；去掉 `setMediaItem` 兜底 |
| PB-6 ✅ | 单曲循环下"自动跳过"其实是原地重开同一首，还报"已跳过" | `PlaybackService.kt:834-838`：`shouldAutoSkip(consecFails, exo.hasNextMediaItem())` → `seekToNextMediaItem()`；`PlayMode.kt:33` `SINGLE_LOOP = REPEAT_MODE_ONE`，此时 `hasNextMediaItem()` 为 true，Media3 的 `seekToNextMediaItemInternal` 在 `next == current` 时走 `repeatCurrentMediaItem()` | 坏歌重试 3 次，3 次 toast 都是"播不了，已跳过"，实际一首没跳。改：判"存在**不同**的可播下标"（复用 `selectNextOfflineIndex` 排除当前项的语义），单曲循环下直接 stop + 提示 |
| PB-7 ✅ | 随机播放下离线跳歌按**未洗牌的下标顺序**走 | `PlaybackService.kt:703-729`：`repeatAll = repeatMode == REPEAT_MODE_ALL` → `selectNextOfflineIndex(songs, from, …)`（`:147-161` 是 `from+1..size` 顺序扫描）；`PlayMode.kt:34` `SHUFFLE = REPEAT_MODE_ALL + shuffleOn` | 开随机时离线跳歌落到歌单下一行，之后又跳回洗牌序 → "随机模式下会突然按歌单顺序播"。`prefetchNext()` 反而对 shuffle 做了正确规避（`PlaybackService.kt:993` `if (shuffleOn) return`）。改：shuffle 开启时不按下标扫描 |
| PB-8 ✅ | `playGen` 不覆盖 controller 重绑，跨代 controller 会在后台返回时抛"播放失败" | `MainActivity.kt:766-781` 只守 `playGen`；`:2759` `controllerGen += 1` 会 dispose 旧 effect → `release()`（`:2695-2712`），但 `playGen` 不变 | 息屏很久回前台立刻点歌 → IO hop 里 `c` 已被 release → `setMediaItems` 抛 `IllegalStateException` → `:824` 显示"播放失败: …"。改：异步落地前再校验 `c === controller`（或把 controllerGen 并进 gen 比较） |
| PB-9 ✅ | `consecFails` 在线/离线共用，只在"真的出声"时清零 → 一次在线失败就报"连续多次失败" | `PlaybackService.kt:566-567`（唯一清零点，`if (playing)`）；`:733/739`（离线分支也自增，从不校验） | 离线连坏 3 首 → 恢复网络后点一首 404 的歌 → `shouldAutoSkip(3, …)` 为 false → 直接 `STOPPED_CONSEC_FAILURES`"连续多次失败，播放停止"，且不跳歌。改：`onMediaItemTransition` 里清零，或在线/离线各一份计数 |

**✅ 已修（2026-10-28，第 3 批）**

- **PB-3**：弱网延迟重试体里补意愿门 —— `cur.playWhenReady || cur.isPlaying` 为假直接 `return@postDelayed`，与 `shouldFireRetryProbe` 同源。这 800/1600ms 里用户按暂停（或来电 `AUDIO_FOCUS_LOSS` 停掉）不再被 `prepare()+play()` 静默撤销。
- **PB-4**：新增 `MediaCache.cachedPrefixBytes` + 纯函数 `contiguousPrefixBytes(List<CacheSpanRange>)` —— 按 span 顺序累加、遇空洞 span 或 `position > end` 立即停，得到"从头连续可读到哪"。离线门由 `cachedBytes > 0` 改成 `>= OFFLINE_MIN_PREFIX_BYTES`（1MB ≈ 128kbps 下 65 秒）。**这是下界不是全长**：`SimpleCache` 不落 EOF 标记，无法区分"256KB 的完整小文件"和"5MB 文件的前 256KB"，所以只能取阈值 + 无空洞；残留局限（只播了 1-2 分钟的长歌仍可能过门）已写进常量 KDoc。
- **PB-5**：`swapToStreamAndPlay(idx, song, expectedMediaId)` 落地前复核 `currentMediaItemIndex == idx && mediaId == expectedMediaId`，不匹配 `SilentFail.note("MainActivity.swapToStreamAndPlay:stale")` 返回；**删掉 `setMediaItem` 越界兜底**（那正是"队列压缩成 1 首"的来源）。
- **PB-6**：`shouldAutoSkip(consecFails, hasNext, repeatMode = REPEAT_MODE_OFF)`，`REPEAT_MODE_ONE` 恒 false；新增 `SkipOutcome.STOPPED_SINGLE_LOOP` + `exo.stop()` + 文案「单曲循环下无法跳过」（不再谎称已跳过）。
- **PB-7**：shuffle 开启时**不再**走 `selectNextOfflineIndex` 的下标扫描，改 `exo.seekToNextMediaItem()`（尊重洗牌序），仍受 `offlineFails` 预算约束。原计划的 `STOPPED_SHUFFLE_OFFLINE` 文案**已撤回删除** —— 跳歌现在是"沿洗牌序前进"，不存在"跳不了"这个结局，多一种文案就是多一条谎。
- **PB-8**：`playAt` 与 `ensureTimeline` 在 IO hop 之后加 `if (c !== controller) return` —— `playGen` 不覆盖 controller 重绑（`controllerGen += 1` 会 release 旧实例），落地前必须确认还是同一个。
- **PB-9**：新增 `private var offlineFails`，离线跳歌只增它；`onIsPlayingChanged(playing = true)` 同时清零 `consecFails` / `offlineFails`；**切轨不清零**（清了两个坏文件会互相弹跳、永远跳不完）。
- 测试：新增 `PlaybackGuardTest`（12 例）。PB-3 / PB-5 / PB-7 / PB-8 **无法单测**（需真实 Player / Activity），只有代码审阅 + 真机验证。

**播放线查过、确认没问题（不用重复排查）**

1. 焦点被拒补试不会把"播放中被打断"误判成"点播被拒"：`PlaybackService.kt:594-617` 依赖 `wasPlayingBeforeFocusChange` 的旧值，已核对 Media3 1.9.3 的 `onPlayWhenReadyChanged` 派发顺序成立。
2. 不会误删好文件：`healAction` 的 `DELETE_AND_STREAM` 只来自两条路 —— `fileValid == false && fails >= 2`，或（PB-2 新增）`fails >= LOCAL_HEAL_FORCE_DELETE_AFTER(5)` 的越线强删；`delete(OfflineMeta.of(song, 0L))` 重建的 probe Song 得到同一个 `offlineBaseName`，删不到别的文件。
3. `customCacheKey` 与 `local:` 前缀不冲突：local 项是 `file://`，走 `DefaultDataSource`，完全绕过 CacheDataSource。
4. 资源无泄漏路径：`MediaMetadataRetriever`/`Cursor`/`InputStream` 都有 `finally`/`use`；`serviceIoScope` 在 `onDestroy` cancel；`MediaCache.clear()` 用 `removeResource` 而非 `release`（在读音轨不被物理删除）。

**需确认（不下结论）**：`suppressSwitchStart`（`PlaybackService.kt:489/795/821`）假设 `replaceMediaItem` 会触发 `onMediaItemTransition`；若 1.9.3 走 `updateMediaSourcesWithMediaItems` 快路径则不产生 transition，标志会泄漏到下一次真实切轨——只影响 `lastSwitchElapsedMs` 度量，无用户可见后果。要证实需一条真机 `VibePrefetch fast-switch` 日志对照。

---

## 5. 结构性债务

### 5.1 `MainActivity.kt` 3460 纯 LOC —— 拆分方案已有，C5 已重新设计（`REFACTOR` §8）

实测（本次重新统计，非引用文档）：

| 指标 | 数值 |
|---|---|
| 纯 LOC（排除空行/注释） | **3460**（第 4/5 批 −91：CODE 收敛 + C5-lite 搬出系统交互组） |
| `by remember` 状态 | **107** |
| 类内局部 `fun` | **71** |
| `LaunchedEffect` | **19** |
| 最深缩进 | **15 级** |

`docs/REFACTOR-mainactivity.md` 的方案是对的（先状态提升再切文件，机械切分不可行），进度是 **C1/C4 已执行，C2/C5/C6 停着**。C4 已把自更新整段搬进 `UpdateCoordinator.kt`（-158 行），证明这条路能走通。

**建议**：把 C5（播放组，最大一刀）单独排一轮，按文档的停手判据执行（单测红 >2 次 / 单 diff >800 行 / 需改 >5 个 screen 调用点 / 出现行为变化 → 回滚该片）。

### 5.2 「单文件 ≤250 纯 LOC」零机器守卫 —— ✅ 已落地（2026-10-28）

18 个文件超标（当前最重 6 个：`MainActivity` 3460 / `VibeApi` 1603 / `PlayerScreen` 933 / `PlaybackService` 791 / `DiscoverUi` 612 / `UiAtoms` 609）。

CI 里**没有 detekt / ktlint / 覆盖率插件**，所以「≤250 纯 LOC」「禁 `!!`」「禁空 catch」三条规范全靠人记 —— 与全局 AGENTS.md 里"靠人记住的约定必须变成机器断言"直接冲突。

**已落地**：`scripts/check-file-size.sh`（bash + 内联 python，不引第三方插件）+ `scripts/file-size-baseline.txt`（18 条白名单），绑进 `.github/workflows/android-smoke.yml` 的 `File size guard` 步骤（放在 Gradle 之前，2 秒出结论），并把 `scripts/**` 加进该 workflow 的 `push` / `pull_request` 两处 `paths`。

规则是**棘轮**（只降不升），三条都会失败：
1. 不在白名单里的文件 > 250 纯 LOC（不许新增超标文件）；
2. 白名单里的文件 > 记录值（想加行必须显式改白名单，让"加债"出现在 diff 里）；
3. 白名单里的文件 < 记录值（拆小了要 `--update` 降记录值，否则棘轮会被悄悄放松）。

纯 LOC 口径 = 排除空行、`//` 行、`/* … */` 块（与 `docs/REFACTOR-mainactivity.md` 一致）。`--update` 重写白名单。

> 没做成 Gradle 任务：这个守卫只需读源码文本，走 Gradle 要起 JVM + 配置整个 Android 插件（本机 30s+），而 CI 里它排在单测之前、越快越好。bash 版本在任何有 python3 的 runner 上都能跑，且本地随时可跑。

### 5.3 吞错的分布（不是"到处都是"，是可枚举的）

`catch (_: Exception)` 共 286 处，但**真空 catch 只有 3 处**（`MainActivity.kt:472`、`PlayDiag.kt:68/75`）。其余都有 `SilentFail.note(...)`，符合"catch 必须有可观察信号"的规范。

真正的问题是**该给用户看的路径也只 `SilentFail`**，高风险项集中在：

- `PlaybackService.kt:743/802/828`（`onPlayerError` 三条自动恢复路径失败 → 播放彻底停住、UI 无提示）
- `MainActivity.kt:1058`（`swapToStreamAndPlay` —— 本地文件损坏 heal 的唯一出口）、`:2529`
- `VibeApi.kt:1786`（收藏失败静默，UI 仍乐观翻红心）

**建议**：给这批加一个"用户可见信号"通道（复用 `showError` 或播放页的状态条），不要与"日志型 SilentFail"混用同一个 API。

---

## 6. 文档与 CI 漂移

### 6.1 数字漂移（五处，会让下一个 agent 按错的门禁干活）—— 四处已修

| 文件 | 写的 | 实际 | 状态 |
|---|---|---|---|
| `README.md:18`、`:67` | 956 用例 | **1037** | ✅ 已改 |
| `AGENTS.md:7/12/14/19/28` | 964 用例 / 2.0.18-ai（96）/ 83 测试文件 / 57 个 `.kt` / Media3 1.9.3 | **1057**；**2.0.19-ai**（97）；**91**；**60**；**1.9.4** | ✅ 已改（**用户明确授权**，只动数字，5 行） |
| `PLAN.md:198`（另 `:150`/`:186` 的"当前门禁 956"） | AI 版当前 `v2.0.17-ai` | **2.0.19-ai** / **1037** | ✅ 已改 |
| `docs/REFACTOR-mainactivity.md:3/9/12` | 3940 行 / 108 `remember` / 956 例 | **3460 纯 LOC**（`wc -l` 3976，口径不同）/ **107** / **1037** | ✅ 已改（并补 §8 C5 重新设计记录） |
| `.github/workflows/release.yml:2` | 注释「当前 v2.0.17-ai」 | 2.0.19-ai | ✅ 已改 |

> `AGENTS.md` 的 5 处数字已在用户授权后更新，其余文字未动。

### 6.2 CI 门禁的真实覆盖面

| workflow | 触发 | 跑什么 | 不跑什么 |
|---|---|---|---|
| `release.yml` | **仅 tag `v*`** | cover fallback gate、`testDebugUnitTest`、`lintRelease`（baseline + abortOnError）、`assembleRelease`、签名/版本/单调/sha256 断言、Gitee 镜像 + 匿名自证 | release 变体单测、覆盖率、静态规范 |
| `deps-guard.yml` | `libs.versions.toml` / `app/build.gradle.kts` / `verify-deps.sh` / 自身 | media3 两个 artifact 的 `minCompileSdk ≤ compileSdk` | 其余依赖的 `minCompileSdk` / `minAGP` |
| `android-smoke.yml` | 仅 workflow / `gradle/**` / 各 `*.gradle.kts` / `gradle.properties` | `assembleDebug` + `aapt2 dump badging` | 测试、lint |

**CI-1（P1）：`app/src/**` 不在任何一个 workflow 的 `paths` 里 → 日常改 Kotlin 代码零 CI。** 问题只会在打 tag 那一刻暴露，而工作流取自 tag 指向的提交，**tag 打错了改不动**（2026-10-27 已踩过）。

其余盲区：
- `assembleRelease`（R8 + `isShrinkResources`）只在发版时跑，`proguard-rules.pro` 的充分性没有机器断言。
- 只跑 `testDebugUnitTest`，`testReleaseUnitTest` 从未执行 → "只在 release 崩"（反射/R8）测不出来。
- 无覆盖率门禁（无 jacoco/kover）。
- 无 `androidTest` 目录，设备级行为（通知栏、播放服务、安装器）零自动化。

**建议（按性价比排序）**：① 加 `pull_request` 触发跑 `testDebugUnitTest + assembleDebug`（带 Gradle 缓存，约 3-5 分钟）；② 加纯 LOC 守卫任务；③ 发版前手动跑一次 `assembleRelease` 已足够（R8 问题本来就只能这样发现）。

---

## 7. 建议修复批次

不追求一次修完。按"风险 × 改动量"分四批：

**第 1 批（凭据与崩溃）—— ✅ 已修（2026-10-28）**
- SEC-1 `AuthToken.sessionEnded` 闸 + `VibeApi.endSession()`（清 jar + 置闸），`trySilentRefreshWith` 在 `refreshMutex` 内首行检查；`doLogout` / `handleAuthLost` **同步**先关闸再走网络
- SEC-2 会话终结统一收口成 `MainActivity.endLocalSession()`（关闸 + 清凭据 + 清 `SearchStore` + `searchHistory`）。**实际不止登出两处**：`loadPlaylists` / `playlistSongs` / `recommendDetailSongs` 的 `AuthException` 分支与冷启动 `me()` 凭据失效共 4 处原本各自 `AuthStore.clear`，同样会把上一个人的关键词带进下一个账号，一并改走同一收口
- SEC-3 `packageSigners()` 按 `SDK_INT >= 28` 分流（`GET_SIGNING_CERTIFICATES` / `GET_SIGNATURES`）+ `catch (Throwable)`；`lint-baseline.xml` 删掉 4 条 `signingInfo`/`apkContentsSigners` NewApi
- UI-2 抽 `songRowKey(index, song, prefix)` 到 `Song.kt`，5 处调用点统一
- UPD-1 `checkOnLaunch` / `checkManual` 的 `saveLastUpdateCheck` 移到拉取成功之后

验收：`testDebugUnitTest` **975 例全绿**（+5：`SongRowKeyTest` 4 + `CookieRefreshTest` 1）、`lintRelease` 通过（仅 8 条 `GradleDependency` 版本提示 + baseline 信息项，`UpdateCheck` 零告警）、`assembleDebug` 通过。

**第 2 批（用户可见的"数据消失"与"页面不好用"）—— ✅ 已修（UI 六条 + CI-1，2026-10-28）**
- UI-1 四屏 error 态：`HistoryScreen` / `FavoritesScreen` / `PlaylistsScreen` / `MineScreen` 各加 `error: String? = null`（`MineScreen` 为 `playlistsError`），统一走纯函数 `selectListState(loading, error, isEmpty)` 分派，ERROR 分支复用 `DiscoverRetryRow`。**只有"列表空 + 有 error"才显示失败行**，有数据时不抢屏。`MainActivity` 新增 `favError` / `historyError` / `playlistsError`（成功清零、失败记 `friendlyNetworkMessage(e)`），`endLocalSession()` 一并清三者（否则换账号会看到上一个人的失败态）。`MineScreen.onRetryPlaylists` 由此从死参数变成真实重试入口
- UI-3 歌词页：`lines` 过滤改 `remember(lyricState)`（原每 tick 全量 `filterNot` + 分配）；`currentLine` 改 `derivedStateOf` + `rememberUpdatedState`（失效粒度仍是每 tick，**通知粒度降到"行号变了"**）；跟随改 `snapshotFlow { currentLine }` + `isScrollInProgress` + 手动拖动打点（`interactionSource` 的 `DragInteraction.Start`）+ 5s 静默窗（`LYRIC_MANUAL_SCROLL_HOLD_MS`，`SystemClock.elapsedRealtime()`），进歌词页首帧强制定位。`KaraokeLine` 非激活行 `bodyMedium` → `titleMedium`，字号层级改由颜色表达，行高恒定
- UI-4 `view` 去掉 `song?.sourceId` key（换歌不再弹回封面）。已核实离开 `Screen.Player` 时 `Crossfade` 分支销毁组合、`remember` 自然重置，"关闭再打开回封面"不受影响
- UI-5 导航态：`Screen.kt` 新增 `screenRoute` / `encodeScreen` / `decodeScreen` / `ScreenSaver`（`route|字段…`，`%` 与 `|` 转义；**未知 route / 字段截断 / 非法数字一律降级 `Screen.Discover`，绝不崩**）；`screen` 改 `rememberSaveable(stateSaver = ScreenSaver)`，`recommendImportId` 一并存盘（丢了会把 `recommend:xxx` 当歌单 id 请求 → 必然 404）；恢复成 `PlaylistDetail` 且 `playlistSongs` 已空时补拉一次（`songsLoadedFor` / `songsLoading` 守卫，正常点入不会重复请求）
- UI-5 横屏：`screenHeightDp < PLAYER_COMPACT_HEIGHT_DP(480)` 时**只给封面分支**加 `verticalScroll`，封面改固定 180dp 正方形 + `fraction = 1f`（`weight` 与滚动互斥，滚动容器下加权子项会塌成 0）。歌词分支仍只有 `LazyColumn` 一层垂直滚动 —— 不存在"滚动容器套 LazyColumn"的无限高崩溃
- UI-6 语义标签：`PlaylistDetailUi` 唯一的图标返回键补 `contentDescription = "返回"`；`FavHeart` 按 `faved` 给「取消收藏 / 收藏」。`CHEVRON_LEFT` 其余 7 处均为 `TextButton` 内"图标 + 中文文字"，补标签会重复播报，判定为装饰不动（`AppIcon` 形参本就有 `contentDescription`，未改签名）
- CI-1：`android-smoke.yml` 的 `paths` 补 `app/src/**` / `lint-baseline.xml` / `proguard-rules.pro`，加 `pull_request` 触发、`testDebugUnitTest` 步骤、同分支并发取消。此后改一行 Kotlin 也会跑全量单测 + `assembleDebug`（`lintRelease` 仍留 `release.yml`，属发版门）

验收：`testDebugUnitTest` **980 例全绿**（+5：`ScreenTest` 5；`SongRowKeyTest` 4 已在第 1 批）、`lintRelease` 通过（0 error / 8 条 `GradleDependency` 版本提示 + 2 条 baseline 信息项）、`assembleDebug` 通过（`app-debug.apk` 24,129,073 B）。

**第 2 批（播放两条）—— ✅ 已修（PB-1 / PB-2，2026-10-28）**
- PB-1 离线 local 错误交回单属主前进（`defersLocalErrorToHeal` + 离线跳歌 3 次预算）
- PB-2 `fails` 越线后真删+重下（`LOCAL_HEAL_FORCE_DELETE_AFTER` 先于 `fileValid`；删除后清计数）
- 验收：`testDebugUnitTest` **982 例全绿**（+2：`HealActionTest.validFile_forcedDeleteAfterQuietThreshold`、`SelfHealTest.serviceDefersToHeal_onlyForLocalItemsWhileOnline`；`validFile_alwaysStreamsOnce` 按新真值表改写）；`lintRelease` 0 error；`assembleDebug` 通过
- 需真机回归：飞行模式下截断的下载文件应自动跳下一首、连播不断；在线连播 5 次后应真删 + 转在线 + 重下

**第 3 批（契约健壮性 + 自更新 + 播放边缘）—— ✅ 已修（2026-10-28）**
- API-1~4：`throwIfEnvelopeAuth` 收进四个 `authed` 包装（一处覆盖全部调用点）、`parseFavIds` 走 `listData`、新增 `parseRemovedCount`、`parseToggleResult` 缺 `data` 抛错、`AuthStore.save` 整体替换
- UPD-2~4：回退源改**单 tag** 端点 + 安装前 `versionCode` 门、`pickBestRelease` 两源取大者（镜像 2.5s 宽限）、host 白名单 + body sha256 落盘校验
- PB-3~PB-9：弱网重试意愿门、离线门改"连续前缀 ≥ 1MB"、自愈身份复核、单曲循环如实停手、shuffle 走 Exo 洗牌序、`c !== controller` 双处守卫、`offlineFails` 与 `consecFails` 分账
- 验收：`testDebugUnitTest` **1023 例全绿**（992 → +31：`EnvelopeAuthTest` 3 + `UpdateIntegrityTest` 20 + `PlaybackGuardTest` 12，另有 `AccountFavHistoryTest` +5 / `AuthStoreTest` +2 / 旧更新用例改写）；`lintRelease` **0 error**（仅 2 条 Information：`LintBaseline` / `LintBaselineFixed`）；`assembleDebug` 通过（`app-debug.apk` 24,136,149 B）
- `lint-baseline.xml` 按 `build.gradle.kts` 注释跑了 `updateLintBaseline` 重生成（163+/157-）：新增 4 条 `UnsafeOptInUsageError`（MediaCache 新代码）+ 重新分组既有条目。**`@OptIn(UnstableApi::class)` 在本工具链（AGP 8.5.2）下对 `UnsafeOptInUsageError` 不生效**（`PlaybackService` 类级标注后条目仍在 baseline 里），所以只能靠 baseline；而 baseline 按**行号**匹配，本文件一插行就失配成 error —— 这就是为什么改完 MediaCache 必须先跑 `updateLintBaseline` 再跑 `lintRelease`
- 需真机回归：随机模式下离线跳歌落点（PB-7）、弱网重试期间按暂停不被拉起（PB-3）、息屏久回前台立刻点歌（PB-8）

**第 4 批（工程门禁与债务）—— ✅ 已修（2026-10-28；C5 拆分仍待单独一轮）**
- ~~CI-1 加 PR 触发~~（已在第 2 批完成）
- 纯 LOC 守卫：`scripts/check-file-size.sh` + `scripts/file-size-baseline.txt`（18 条棘轮）+ `android-smoke.yml` 的 `File size guard` 步骤与 `scripts/**` paths
- CODE-1~7 的孪生副本收敛：`currentMediaItem!!` 去除 / `ErrorCopy.kt` + 33 处文案统一走 `diagnosableError`（全角冒号）/ `OfflineStore.downloadToStore` 三处共用 / `librarySongOrNull` + `shouldRecordOnce` / `dedupeSearchHistory` 四处共用 / `ApiConfig.API_HOST` 域名唯一来源 / 内存间隔改 `elapsedRealtime`
- CODE-8 ⏸ 不改（无 i18n 计划；真正的风险"改一处漏一处"已由 CODE-2 收口）
- `MainActivity` C5 拆分**未做**：本轮明确不碰 MainActivity 结构，按 `docs/REFACTOR-mainactivity.md` 停手判据单独一轮执行
- 验收：`testDebugUnitTest` **1037 例全绿**（1023 → +14：`ApiHostTest` 9 + `SearchStoreTest` 5）；`lintRelease` 0 error（仅 2 条 Information）；`assembleDebug` 通过（24,135,819 B）；`scripts/check-file-size.sh` 通过（59 个文件 / 18 条白名单）

**第 5 批（C5 重新设计 + 测试缺口 + 文档漂移）—— ✅ 已修（2026-10-28）**
- C5 按原设计命中 `docs/REFACTOR-mainactivity.md` §4 停手判据（diff > 800 行 / 需改 > 5 个 screen / 无真机可回归），**改为 C5-lite**：系统交互组搬到 `SystemSettings.kt`（`readKeepAliveState` / `notificationSettingsIntent` / `batteryOptIntents` / `startFirstAvailable` / `launchNotificationSettings` / `shareText`），`MainActivity` 3530 → **3460 纯 LOC**；新增 `SystemSettingsTest` 7 例
- CODE-9：`UpdateCoordinator` 加 `fetchLatest` 接缝 + `UpdateCoordinatorTest` 10 例；`SongTest` +3
- CODE-2 收尾：`UpdateCoordinator` 的 `"更新失败: "` / `"安装失败: "`、`MainActivity` 的 `"注册失败: "` / `"登录态恢复失败: "` / `"切歌失败: "`×2 / `"进度跳转失败: "`、`PlayPureLogic` 的 `"播放器连接失败: "` 全部改全角（前四处顺带改走 `diagnosableError`）
- 文档漂移：README / PLAN（含两处"当前门禁"）/ REFACTOR / `release.yml` 注释已改；`AGENTS.md` 5 处数字经用户授权后更新（只动数字）
- CODE-10：media3 1.9.3 → 1.9.4 已升（`verify-deps.sh` 实测 minCompileSdk=35，1057 例全绿）；activity-compose 1.10.1 / robolectric 4.17 / bugly 4.1.9.3 评估后不升（证据见 §4.4）
- 验收：`testDebugUnitTest` **1057 例全绿**（1037 → +20：`UpdateCoordinatorTest` 10 + `SystemSettingsTest` 7 + `SongTest` 3）；`lintRelease` **0 error 0 warning**（media3 升级引发的 8 条 `GradleDependency` 已按上文关闭并重生成 baseline）；`assembleDebug` + `assembleRelease` 通过；`check-file-size.sh` 通过

**不建议现在做**：工具链升级（media3 1.10+ / compose BOM / core-ktx 全部被 compileSdk 35 硬挡，需整体升 36 + AGP 8.9.1，属用户决策）；i18n 抽取（≥742 处中文字面量，无产品需求）。

---

## 8. 未覆盖 / 需真机

**本次审查无法判断的（不猜）**

1. 歌词页性能：100ms ticker + 每 tick 全量 `filterNot`/`indexOfLast`，在 vivo Y 系低端机 + 200 行以上歌词时是否掉帧 —— 需 Perfetto / `dumpsys gfxinfo`。
2. 横屏播放页是"被裁"还是"被压"（§UI-5 的 440dp 是推演）。
3. 系统字号 1.3/2.0 下 `LyricOffsetRow`（4 个按钮无 weight）、`PlaylistsUi` 多选行、`UpdateChannelRow` 是否挤爆。
4. ~~后端 token 过期返回 HTTP 401 还是 200+`code:401`~~ —— **第 3 批已实测定性**：两种都有。`SecurityConfig` 的 entry point 走 HTTP 401；`GET /api/songs/…` 是 `permitAll`，`PlayHistoryController.java:39` 直接 `Result.error(401)`（无 `@ResponseStatus`）→ HTTP 200 + body `code:401` **真实可达**。API-3 因此是现实缺陷，已修。
5. 后端 `/api/auth/logout` 是否吊销 refresh（决定 SEC-1 的严重度上限）。

**需要真机才能验的**

- ✅ **已验（2.0.20-ai，2026-10-29，用户真机确认）**：实际出声 / 后台锁屏播放 / vivo 通知栏播放键 —— 本版播放线改动的核心盲区已关闭
- 微信「用其他应用打开」的 `content://` 修复（2.0.19 新增）
- 安装器在 API 26/27 设备上的行为（SEC-3 修完后回归）
- 离线连播 + 坏文件自动跳歌（PB-1/PB-2 修完后回归，需飞行模式 + 一个截断的下载文件）
- 随机模式下离线跳歌落点（PB-7）
- 第 2 批新增：横屏播放页（`PLAYER_COMPACT_HEIGHT_DP = 480` 阈值、封面 180dp 观感）、歌词页手势（拖动后 5s 内不抢滚动）与滚动流畅度、系统字号 1.3/2.0 下四屏与歌词页、旋转后是否停留原页（含歌单详情）

---

*本报告已入库（`docs/CODE-REVIEW-2026-10-28.md`）。第 1~6 批全部落地并随 **2.0.20-ai（98）** 发布（tag `v2.0.20-ai`，CI `40801760852` 全绿，双源 sha256 `d5c1a5de…`），单测基线 **1057 例**；真机三样（出声/后台锁屏/通知栏键）已验。**工具链已升到 compileSdk 36 / AGP 8.11.1 / Gradle 8.13 / Kotlin 2.1.21（`8c6b28a`，targetSdk 仍 35，单测 1057 例、`lintRelease` 0 error）**。剩余：`MainActivity` 播放组 C5 第 2~4 片（第 1 片 `playMode` 已搬，`774b35f`；⚠️ 只减 3 纯 LOC，剩余三片需重新权衡收益，见 `docs/REFACTOR-mainactivity.md` §8 执行记录）、CODE-8 i18n（⏸ 不改，无产品需求）。*
