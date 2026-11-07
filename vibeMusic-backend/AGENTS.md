# PROJECT KNOWLEDGE BASE — vibeMusic-backend

**Generated:** 2026-08-13（2026-11-06 核对更新）
**Scope:** Spring Boot 4 后端（8080）。父级全局约定见仓库根 `AGENTS.md`，此处只列本模块专属事实。

## OVERVIEW
Spring Boot 4.1.2（编译目标 Java 17，运行时 JDK 25） + MyBatis-Plus 3.5.18 + jjwt 0.12.6 + MinIO 8.5.17 + springdoc 2.8.5 + Flyway。
包结构 `com.vibemusic.{controller,service,entity,mapper,config,dto,common,security}`。

## PACKAGE COUNTS（2026-11-06 实测；口径=顶层 .java，common 含子包。数量会随迭代漂移，引用前先 `ls` 复核）
- controller: 19（Auth, Upload, Song, Playlist, PlayHistory, Favorite, Recommend, Assistant, AssistantClient, AssistantPayloads, AssistantRequestIdentity, AssistantStreamOrchestrator, Stream, Download, Proxy, CacheMonitor, Cookie, ClientError, SearchHistory）
- service: 45，按职责分组：
  - 搜索：SongSearch, SongCache, SongRanking, SongMatcher, SongMerger, SongDtoParser, PlatformSearchAdapter, SearchQueryHints, SearchHistory, JsonCache
  - 播放：SongPlay, PlayUrlCache, AudioProxy, NeteaseApi, NeteaseDegradeChain, NeteaseUrlPayloads, NeteaseUrlProbe, PlatformUrlFetcher, QqCircuitBreaker, CrossPlatformFailover
  - 推荐：Recommend, RecommendRules, RecommendPersonalizer, RecommendGuestBuilder, RecommendCacheStore, AiRecommend, AiRecommendVerifier
  - AI：AiTool, AiToolCallGuard, AiToolJson, AiCallBudget, AiPlaylistTools, ChatMemory
  - 鉴权与防滥用：User, RateLimit, LoginAttemptGuard, RegistrationGuard, IdempotentGuard
  - 业务：Song, Playlist, PlayHistory, PlayHistoryCleanup, Favorite, Storage, Download
- entity: 8（User, Song, Playlist, PlaylistSong, UserFavorite, PlayHistory, UserSearchHistory, BaseEntity 抽象）
- mapper: 7 · config: 14（含 AudioQualityTier 枚举、RateLimitProperties、AiClientConfig）· dto: 18（14 个类型化请求 record + SongDTO/SearchResult/RecommendResult/ClientErrorReport）· common: 19（顶层 Result + exception/concurrent/filter/resilience/utils 五个子包）· security: 3（CustomUserDetails, JwtAuthenticationFilter, TokenBlacklistService）

## CRITICAL COUPLINGS（改坏即崩）
1. `SongSearchService.search()` 返回 `SearchResult`，外部调用方：SongController、AiToolService、AiPlaylistTools、RecommendPersonalizer、AiRecommendVerifier（AI 推荐逐首验证）；另有 getRandomSongs() 内部调用。改 SearchResult 全链路受影响。
2. `MyBatisPlusConfig` 手建 SqlSessionFactory，必须 (a) 显式设 mapperLocations（`classpath*:/mapper/**/*.xml`）否则 BindingException，(b) 把 MetaObjectHandler 注入 GlobalConfig 否则自动填充静默失效。
3. `SecurityConfig` 端点白名单：公开 /api/auth/**、swagger、/uploads/**、/actuator/**、GET /api/songs/**、GET /api/recommend/**、GET /api/playlists/songs|detail、/api/assistant/**、GET /api/image-proxy、GET /api/download/**。其余都要 JWT。新端点必须加进来否则 401。
4. `JwtUtils` 双 Token：access 15min / refresh 7d，httpOnly Cookie。access 在 HTTPS 下用 `__Host-VIBE_TOKEN`（前缀强制 Secure + Path=/ + 无 Domain），明文 http 调试仍写 `VIBE_TOKEN`；服务端双读（`JwtUtils.extractAccessToken`，Bearer 优先 → 新名 → 旧名），logout 下发 4 条删除指令。refresh 固定 `VIBE_REFRESH` Path=/api/auth（迁移期另清 `/api/auth/refresh` 旧路径）。Secure 依 X-Forwarded-Proto 条件开启。

## CONVENTIONS（仅列偏离标准处）
- 时间字段：`@TableField(fill=FieldFill.INSERT/INSERT_UPDATE, insertStrategy=FieldStrategy.NEVER)` + DB DEFAULT CURRENT_TIMESTAMP。BaseEntity/UserFavorite/Playlist/PlaylistSong 遵循。**例外**：PlayHistory.playedAt 由 MetaObjectHandler 填充、无 FieldStrategy.NEVER，勿"修复"。
- 每个 controller 端点必须有 `@Operation(summary=...)`（PlaylistController/ProxyController 违反）。
- Service：@Service @RequiredArgsConstructor @Slf4j。Entity：Lombok @Data @Builder + @TableName 蛇形 + @TableId(type=AUTO)。
- Redis Key 版本化前缀：song:search:v4:、recommend:v3:、lyric:v2:、banner:v2:、playlist:v2:、chat:session:、ratelimit:、idempotent:、token:blacklist:、user:auth:、minio:exists:v1:。
- 共享 Apache HttpClient5 连接池（RestTemplateConfig），禁止自建独立 HTTP 客户端。
- 错误：抛 BusinessException(code,msg) → GlobalExceptionHandler → Result<T> 信封；500 内嵌 traceId（TraceIdFilter MDC）。
- 配置：密钥只走环境变量（DotenvLoader 读 ../.env）；application.yml 默认 dev profile；Flyway 迁移在 db/migration（**云上在跑**：`application-docker.yml` 为 `flyway.enabled:true` + `validate-on-migrate:true`（2026-10-25 起，云库 checksum 已逐条核对一致）；迁移只能追加新版本号，改历史文件会让启动立即失败——原重复的 `V3__add_indexes` 已改 `V3_1__add_indexes`）。

## ANTI-PATTERNS
- PlaylistMapper.listPlaylistsWithStats 同时有 @Select 注解和 XML，MyBatis 以 XML 为准，注解是死代码。
- StorageConfig.java 硬编码 minioadmin 默认值，已知违规，勿复制。
- application-dev.yml 有兜底密钥（JWT dev secret、DB_PASSWORD=123456），仅 dev，禁用于 prod。
- 线程池：daemon 线程 + 有界队列 + DiscardOldestPolicy + @PreDestroy 关闭（SongSearchService、StreamController）。
- FavoriteService 重试必须放在 @Transactional 之外（rollback-only 陷阱）。

## TESTS
3 层：BaseTest（@SpringBootTest RANDOM_PORT + H2 + schema-test.sql + loginAsTestUser）、TransactionalServiceTest（@Transactional + 每方法 data-test.sql）、纯 Mockito 单测。按类跑：`mvn test -Dtest=<Class> -pl .`。JaCoCo 60% LINE 门禁绑 `mvn verify`（非 test）。@DisplayName + 中文描述。H2 不兼容 SQL 用 @Disabled。

## NOTES
- DotenvLoader 经 META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor 注册。
- WebClient（webflux）现仅用于 AI 助手 SSE 流式输出（AssistantController）。
- Config profiles：dev（默认，SQL StdOut + knife4j）、docker（关 springdoc）、prod（无 JWT 兜底、actuator 受限）、test（H2 MODE=MySQL，排除 Redis）。
- Application：VibeMusicBackendApplication（@SpringBootApplication + @MapperScan("com.vibemusic.mapper") + @EnableScheduling）。
