# REFACTOR-search-decomposition — 搜索模块拆解：定案与拒绝清单

> **状态：结论已钉死，本文件不启动、不授权任何代码改动。**
> 定案来源：oracle 评审（O1，2026-10-19）+ 侦察复核（R1，2026-10-19）。
> 本文所有 `文件:行` 均于落库当日逐条实测复核（复核记录见 §9）。
> 时间线：两刀**延后到「App 发版 + 观测期」之后**再做（见 §8）。开工前请新会话直接读本文，不要重新侦察。

## 0. 这份文档解决什么问题

两个巨型文件的拆解方案此前已被 oracle 评审并定案，但结论只散落在会话与记忆里，没有落进仓库。
后果：换一个会话就会重新提一遍**已经被否决的 7 刀**，白烧一整轮侦察。

本文的作用是「一次性把结论钉死」：

1. 记录体量事实（实测值，不接受二手数字）；
2. 记录**只批准 2 刀**，以及这两刀的硬约束；
3. 记录**成本单位**（这是评审的判据，也是所有否决的出发点）；
4. 记录**明确拒绝的 7 刀**，避免重复提案；
5. 记录「试听 ≠ 可播」判定点的**真实坐标**（已有的口头描述是错的，见 §5）；
6. 记录停手判据与时间线。

**为什么新建本文件而不是续写既有文档**：已 grep `docs/` 全部 markdown，`OPTIMIZATION-PLAN.md`、`architecture-review-2026-09-16.md`、各 `code-review-round*.md`、`musicapi-gateway-review-report-2026-09-14.md` 均**不含**本主题（关键词 `SourceWeightRegistry` / `SearchResultMerger` / `SearchFanout` / `SearchCacheGate` / `PlatformSongFetcher` / `HotKeywordPrewarmer` / `SearchMetrics` 在整个仓库零命中；`architecture-review-2026-09-16.md:212` 反而建议把 `SongCacheService` **并回** `SongSearchService`）。`docs/archive/PROJECT-AUDIT.md` 只提过线程池问题，与本主题无关。故本次新建，命名沿用 `docs/` 现有的 `UPPER-PREFIX-topic.md` 风格（先例：`OPTIMIZATION-PLAN.md`、`TESTCASE-playback-chain.md`、`DEPLOY.md`）。

---

## 1. 目标文件与体量事实（实测）

| 文件 | 总行 | 纯 LOC | 备注 |
|---|---|---|---|
| `vibeMusic-backend/src/main/java/com/vibemusic/service/SongSearchService.java` | **999** | **793** | 7 参构造器（`@RequiredArgsConstructor`）；`@PostConstruct void initMetrics()` 在 `:72` |
| `musicapi/src/search.js` | **1212** | **949** | 单一 `module.exports = {` 在 `:1179`，**实测导出 33 个符号**（`:1180`–`:1212`，含 `searchCache` / `urlCache` / `miguCopyrightCache` / `kugouDevice` 等单例） |

纯 LOC 口径：`grep -cvE '^\s*($|//|/\*|\*)'`（去空行与注释行）。
复核说明：`musicapi/src/search.js` 的导出符号数**实测为 33**，此前会话转述的「27」已过期，以 33 为准。

> ⚠️ 已知陷阱：`musicapi/src/cookie.js:269` 有一处**惰性 `require('./search')`，不可顶层化**——`search.js` 顶层依赖 `cookie.js`，顶层互引会形成循环依赖。原注释在 `cookie.js:268`：
> `// 延迟 require：search.js 顶层依赖本模块，顶层互引会形成循环。`

---

## 2. 批准的两刀（这是唯一许可清单）

> 只有下面两刀被批准。清单之外的任何拆法都视为已被否决（§4）。

### 刀 1（Java 侧）：抽 `SourceWeightRegistry`，**含其中的 Gauge**

- 搬走的对象：源权重一族（`NET_WEIGHT:143` / `MIGU_WEIGHT:144` / `KUGOU_WEIGHT:148` / `BILI_WEIGHT:156` / `QQ_WEIGHT:160`、`WEIGHTED_SOURCES:205`、`deweightFactor:208`、`baseWeightFor:213`、`effectiveWeight:222`）**以及 `initMetrics():72` 内 `:83`–`:85` 的 `Gauge.builder("search.source.weight", …)` 注册**。
- 「含 Gauge」是这一刀不可拆的原因：Gauge 的取值函数就是 `effectiveWeight(platform, baseWeightFor(platform))`，权重注册与权重求值必须同属一地。
- Gauge 必须改读新 Bean（`MeterRegistry` 仍由原类持有，不可重复注册同名指标）。

### 刀 2（网关侧）：抽 B站 / 酷狗的**纯函数组**

- 候选集合（实测坐标，均在 `musicapi/src/search.js`）：
  - 酷狗：`kugouAndroidSignature:431`、`kugouUrlKey:437`、`mapKugouSong:455`、`pickKugouUrls:596`（纯）；`searchKugou:491`、`getKugouUrl:643`、`getKugouPlayData:660`（含 I/O，需要注入依赖或保持原地）
  - B站：`isBiliTrackId:801`、`parseBiliDuration:810`、`parseBiliTitle:841`、`mapBiliVideo:1007`、`isBiliSingle:1039`（纯）；`searchBili:1054`、`getBiliView:1094`、`getBiliUrl:1125`（含 I/O）
- **硬约束：新模块不持有任何缓存单例。** 下列四个必须是**同一实例**、留在原处：`searchCache:27`、`urlCache:34`、`miguCopyrightCache:244`、`kugouDeviceCache:410`。
- `cookie.js:269` 的惰性 require 关系不得因这次抽取被改成顶层 require。

### 两刀共有的硬约束（不可谈判）

1. **构造函数签名一字不变**（7 参：`SongMapper` / `NeteaseApiService` / `SongCacheService` / `MeterRegistry` / `searchExecutor` / `warmExecutor` / `StringRedisTemplate`）。旧位置必须**留 delegate**，不是「搬走即删除」。
2. **13 项包私有 / 静态 API 一字不变**（不改名、不改签名、不改可见性）。实测重建的 13 项 = 12 项被测试直接引用的 API + `initMetrics()`：

   | # | 符号 | 位置 | 测试直接依赖 |
   |---|---|---|---|
   | 1 | `hotKeywords()`（public static） | `SongSearchService.java:111` | ✅ 2 个测试文件 |
   | 2 | `baseWeightFor(String)` | `:213` | ✅ |
   | 3 | `deweightFactor(int)` | `:208` | ✅ |
   | 4 | `remainingSec(long,int)` | `:978` | ✅ |
   | 5 | `QQ_BREAKER_REDIS_KEY` | `:247` | ✅ |
   | 6 | `QQ_BREAKER_OPEN_MS` | `:201` | ✅ |
   | 7 | `effectiveWeight(String,double)` | `:222` | ✅ |
   | 8 | `getSourceFailures(String)` | `:237` | ✅ |
   | 9 | `recordSourceOutcome(String,boolean)` | `:227` | ✅ |
   | 10 | `recordQqSuccess()` | `:638` | ✅ |
   | 11 | `recordQqFailure()` | `:652` | ✅ |
   | 12 | `setQqClockForTest(LongSupplier)` | `:260` | ✅ |
   | 13 | `shouldSkipQq()` | `:621` | ✅ 2 个测试文件 |
   | （另计） | `initMetrics()` | `:72` | ✅ 7 处调用，见 §3 |

   注：oracle 口径给的是「13 个」，本文的 13 项为**实测重建**（等于「测试直接引用的 12 项 + `initMetrics()`」）。此外类内还有 `MAX_RANDOM_COUNT:182`（被主代码 `SongController:94` 引用）、`QQ_BREAKER_FAILURE_THRESHOLD:200`、`isBiliCdnCover:891`、`toLongOrNull:938`、`preWarmHotKeywords:121` 等包私有成员——**冻结约束按并集执行更安全**：整个包私有 / 静态面一律不动。
3. `searchExecutor` 与 `warmExecutor` **不可合并**：前者 `AbortPolicy`（过载即 `RejectedExecutionException` → 503，见 `config/ThreadPoolConfig.java:38-59`），后者 `DiscardOldestPolicy`（预热任务可丢，`:62-80`）。语义不同。
4. 新类**不得**加 `@Transactional`。
5. `perUser=true` 绕过全部共享缓存与单飞锁的不变量**最易破**，抽取时不得把该分支切散或让新类持有缓存写入职责。

---

## 3. 成本单位：Bean / 构造器签名变化（这是判据）

**重构的成本单位不是行数，是 Bean / 构造器签名变化。** 判据来源（均已实测复核）：

- `new SongSearchService(...)` 全仓 **7 处**（全部在测试里，主代码走 Spring 注入）：

  | # | 位置（实测行号） |
  |---|---|
  | 1 | `vibeMusic-backend/src/test/java/com/vibemusic/service/SourceWeightAdjustTest.java:55` |
  | 2 | `.../SongSearchServiceTest.java:73` |
  | 3 | `.../SongSearchServiceTest.java:585` |
  | 4 | `.../SongSearchServiceTest.java:1358` |
  | 5 | `.../SongSearchUserCookieIsolationTest.java:65` |
  | 6 | `.../AdversarialFaultToleranceTest.java:69` |
  | 7 | `.../SongSearchFallbackTest.java:52` |

- `initMetrics()` 显式调用 **7 处**（行号与上面的构造点相差 +2，属同一批 `@BeforeEach`）：

  | # | 位置（实测行号） |
  |---|---|
  | 1 | `SourceWeightAdjustTest.java:57` |
  | 2 | `SongSearchServiceTest.java:76` |
  | 3 | `SongSearchServiceTest.java:587` |
  | 4 | `SongSearchServiceTest.java:1360` |
  | 5 | `SongSearchUserCookieIsolationTest.java:69` |
  | 6 | `AdversarialFaultToleranceTest.java:71` |
  | 7 | `SongSearchFallbackTest.java:54` |

  （定义点：`SongSearchService.java:72`，`@PostConstruct`。）

- **先例证据（关键）**：`8c4eaa0`「排序/相关性纯逻辑拆到 `SongRanking`」——拆的**全是纯函数**（新类 218 行，`SongSearchService` 1011→872 行），仍然**连带改了 `SongSearchServiceTest` 16 处引用**（提交信息原文：「SongSearchService 内 7 处调用点加 `SongRanking.` 前缀；SongSearchServiceTest 16 处引用同步改写」；`git show --stat` 该文件 32 行变更 = 16 对）。
- **推论**：连纯函数刀都这么贵，编排刀（改构造器 / Bean 边界）只会更贵。R1 曾给「Java 9 类 + Node 10 模块」的全拆方案 = 至少 9 次这种乘数，收益不足以付成本。

> 因此「构造函数不变 + 包私有面不变 + 旧位置留 delegate」**不是形式主义**：它是让 7 + 7 处测试零改动、改动不外溢的唯一手段。

---

## 4. 明确拒绝的 7 刀（避免重复提案）

以下 7 个提案已被否决（`QqCircuitBreaker` 为**延后**，非永久否决）。逐条理由如下，**照写，不再重新论证**：

| # | 被拒刀 | 否决理由（oracle 评审结论） |
|---|---|---|
| 1 | `SearchResultMerger` | 它要改的那点算术 = **静默改错排序**，风险高于收益 |
| 2 | `SearchFanout` | 现有行内注释本身就是 17s 毛刺事故的复盘，抽出反而丢掉事故上下文 |
| 3 | `SearchCacheGate` | 会把 `perUser` 不变量劈到两个文件，**严格更差** |
| 4 | `PlatformSongFetcher` | 5 源的失败归因是**故意不同**的，统一封装会抹掉差异 |
| 5 | `QqCircuitBreaker` | **延后**（不是永久否决）；当前收益不足以付构造器成本 |
| 6 | `HotKeywordPrewarmer` | `@PostConstruct` 顺序会从显式变隐式 |
| 7 | `SearchMetrics` | 仅 25 行，负收益 |

未列出的其它拆法即视为无收益或已被上述理由覆盖；有疑问时按「oracle 评审结论：暂不动」处理，**不要自行重新论证**。

---

## 5. 「试听 ≠ 可播」判定点（7 处，实测坐标）

> ⚠️ **重要更正**：这 7 处**不在**本文 §1 的两个目标文件里。实测 `SongSearchService.java` 与 `musicapi/src/search.js` 对该判据**零命中**（`search.js` 仅 `:924`/`:944`/`:951` 的正则把「试听版」当作**标题噪声词**匹配，与播放权限无关）。7 处实际分散在 **Java 3 个来源 + Node 2 个来源 + 前端 1 个来源**。
>
> 数量与语义口径：**7 处判定（Java 4 / Node 2 / 前端 1）**，但**不是同一判据的拷贝**，语义分档。

| # | 文件:行（实测） | 语义 | 关键差异 |
|---|---|---|---|
| ② | `NeteaseUrlPayloads.java:43-44` `hasUsableFullUrl` | `hasPlayableUrl && freeTrialInfo == null` | **不含 `time` 判定**（刻意的另一档）；供 `isVipBlocked:57`、`isFullPlayable:71` 使用 |
| ③ | `SongPlayService.java:462-464` | `freeTrialInfo != null \|\| time <= TRIAL_DURATION_MS` | 取链探针档（并行探测循环内） |
| ④ | `SongPlayService.java:660-662` | **与 ③ 逐字相同** | 孪生副本（网易云降级链循环内） |
| ③′ | `SongPlayService.java:571`、`:648` | `duration <= TRIAL_DURATION_MS` | **信号不同**（搜索响应 `duration`，非取链 `time`），**实测 2 处**（R1 只报了 1 处） |
| ⑤ | `NeteaseApiService.java:151-183` `precheckUrlPayload` | 用 `hasPlayableUrl`（**故意不用** `hasUsableFullUrl`），命中在 `:164` | 源码注释原文：「付费曲目未授权：非无版权（否则会误跳过 QQ 降级，App 侧 404）」→ **收敛 = 重演事故，禁止动** |
| ⑥ | `musicapi/src/cookie-probe.js:31-35` `isPlayableUrlPayload` | `url` 非空 && `freeTrialInfo` 为 `null/undefined`，**只取 `data[0]`** | 与 ② 语义等价，但空串 `url` 判定不同（Node 收 / Java 拒）；网关槽位健康的唯一口径 |
| ⑦ | `musicapi/src/scoring.js:192-202` `refineResults`（过滤体 `:195-201`） | `duration < 50s` 丢弃 | **搜索质量过滤，与播放权限无关**（注释借用了「试听」一词）→ 保持独立 |
| ⑧ | `vibemusic-web/src/stores/player.js:76`（`isTrialSong`，写死 `false` 在 `:217`，透出在 `:494`）+ `components/PlayerBar.vue:52`/`:179` | 仅渲染徽标 | **后端 main 零命中 → 死接线**（跨栈待决策，不在本两刀范围） |

`TRIAL_DURATION_MS = 30000` 定义于 `SongPlayService.java:159`。

### 收敛边界（已定案）

- Java 侧**可**真正收敛：真源 = `NeteaseUrlPayloads`，补 `isTrialDuration` + `isPlayableItem`，把 ③④ 改为调用它。
- **⑤、⑦ 必须保持相互独立（与 ③④ 及彼此都不得合并）——合并会改变语义**：
  - ⑤ 用 `hasPlayableUrl` 是**故意**的（防止误跳过 QQ 降级导致 App 侧 404），一旦改用 `hasUsableFullUrl` 就是重演历史事故；
  - ⑦ 是搜索质量过滤，与播放权限无关，合并会把「过滤噪声」和「权限判定」两件事绑死。
- ⑥ 跨栈**不做代码共享**，改为**双栈同源 fixture 表**（现成两份）：
  - `SongPlayVipBlockFailoverTest.java:92-104`
  - `musicapi/test/netease-capability-probe.test.js:57-68`

---

## 6. 停手判据（触发即 revert，不许硬扛）

任一条命中，**立刻 `git checkout` / revert 回到绿点**，记录原因，停止该刀：

1. 测试红 **> 30 min 未定位**；
2. **JaCoCo 掉 0.1**（含义：delegate 成了不可达代码 = **边界切错了**，不是覆盖率噪声）；
3. 同一刀**第 3 次失败**；
4. 出现**行为性** diff（排序变化、返回字段变化、日志语义变化等任何非纯搬运差异）。

---

## 7. 引用与验证附注（供复核，不必重跑）

- 全部 `文件:行` 于落库当日实测；复核命令与本次修正记录见 §9。
- 主代码调用方（改动若外溢，会先在这里出问题）：`SongController:79/86/94/95`、`AiToolService:104`、`RecommendService:155/187/268/396/525`。
- 测试对私有方法的反射依赖：`ReflectionTestUtils.invokeMethod(..., "getQqWithTimeout", ...)`（`SongSearchServiceTest.java:466`）——包私有面冻结同样覆盖这类反射调用。

---

## 8. 时间线

- **两刀延后到「App 发版 + 观测期」之后**再做。本文档当前只做「结论钉死」，**不代表要开工**。
- 观测期最小版（10 min，原文照录）：类头 8 行结构导览 + 本拒绝清单留在文档里。
- 开工时请开**新会话**，先读本文件，再读 `~/.dsh/memory/facts/optimization-program-2026-10.md` 的 R1/O1 章节。

---

## 9. 本文件未覆盖 / 待确认

- 本文只钉死「搜索模块拆解」的结论，**不覆盖**其余优化项（fail-open、异地备份、监控纳管等），那些见 `~/.dsh/memory/facts/optimization-program-2026-10.md`。
- oracle 原文给的「13 个包私有/静态 API」未逐项列出，§2 的 13 项是本文**实测重建**（测试直接依赖的 12 项 + `initMetrics()`）；若 oracle 原口径的 13 项与此不同，以**并集**为冻结面。
- `musicapi/src/search.js` 导出符号数：本文实测 **33**，历史记录写作 27 —— 已按实测更正，未追溯 27 的来源。
- 未做：未跑 `mvn`、未跑 `docker`、未连生产、未改任何代码（本文为纯文档）。
