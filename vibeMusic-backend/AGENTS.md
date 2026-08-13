# PROJECT KNOWLEDGE BASE — vibeMusic-backend

**Generated:** 2026-08-13
**Scope:** Spring Boot 4 后端（8080）。父级全局约定见根 `D:\vibeMusic\AGENTS.md`，此处只列本模块专属事实。

## OVERVIEW
Spring Boot 4.0.6 + Java 17 + MyBatis-Plus 3.5.9 + jjwt 0.12.6 + MinIO 8.5.17 + springdoc 2.8.5 + Flyway。
包结构 `com.vibemusic.{controller,service,entity,mapper,config,dto,common,security,task}`。

## PACKAGE COUNTS（实测，CLAUDE.md 已过时）
- controller: 11（Auth, Song, Playlist, PlayHistory, Favorite, Recommend, Assistant, Stream, Download, Proxy, CacheMonitor）
- service: 19（User, Song, SongSearch, SongPlay, SongCache, NeteaseApi, ESSearch, JsonCache, Recommend, Playlist, PlayHistory, PlayHistoryCleanup, Favorite, Storage, Download, RateLimit, IdempotentGuard, ChatMemory, AiTool）
- entity: 7（User, Song, Playlist, PlaylistSong, UserFavorite, PlayHistory, BaseEntity 抽象）
- mapper: 6 · config: 12（含 AudioQualityTier 枚举）· dto: 4（SongDTO, SearchResult, SearchResponse, RecommendResult）· common: 6 · security: 2 · task: 1（ESCleanupTask）

## CRITICAL COUPLINGS（改坏即崩）
1. `SongSearchService.search()` 返回 `SearchResult`，恰好 3 个外部调用方：SongController、AiToolService（被 AssistantController 用）、RecommendService；另有 getRandomSongs() 内部调用。改 SearchResult 全链路受影响。
2. `MyBatisPlusConfig` 手建 SqlSessionFactory，必须 (a) 显式设 mapperLocations（`classpath*:/mapper/**/*.xml`）否则 BindingException，(b) 把 MetaObjectHandler 注入 GlobalConfig 否则自动填充静默失效。
3. `SecurityConfig` 端点白名单：公开 /api/auth/**、swagger、/uploads/**、/actuator/**、GET /api/songs/**、GET /api/recommend/**、GET /api/playlists/songs|detail、/api/assistant/**、GET /api/image-proxy、GET /api/download/**。其余都要 JWT。新端点必须加进来否则 401。
4. `JwtUtils` 双 Token：access 15min / refresh 7d，httpOnly Cookie（VIBE_TOKEN Path=/；VIBE_REFRESH Path=/api/auth/refresh），Secure 依 X-Forwarded-Proto 条件开启。

## CONVENTIONS（仅列偏离标准处）
- 时间字段：`@TableField(fill=FieldFill.INSERT/INSERT_UPDATE, insertStrategy=FieldStrategy.NEVER)` + DB DEFAULT CURRENT_TIMESTAMP。BaseEntity/UserFavorite/Playlist/PlaylistSong 遵循。**例外**：PlayHistory.playedAt 由 MetaObjectHandler 填充、无 FieldStrategy.NEVER，勿"修复"。
- 每个 controller 端点必须有 `@Operation(summary=...)`（PlaylistController/ProxyController 违反）。
- Service：@Service @RequiredArgsConstructor @Slf4j。Entity：Lombok @Data @Builder + @TableName 蛇形 + @TableId(type=AUTO)。
- Redis Key 版本化前缀：song:search:v4:、recommend:v3:、lyric:v2:、banner:v2:、playlist:v2:、chat:session:、ratelimit:、idempotent:、token:blacklist:、user:auth:、minio:exists:v1:。
- 共享 Apache HttpClient5 连接池（RestTemplateConfig），禁止自建独立 HTTP 客户端。
- 错误：抛 BusinessException(code,msg) → GlobalExceptionHandler → Result<T> 信封；500 内嵌 traceId（TraceIdFilter MDC）。
- 配置：密钥只走环境变量（DotenvLoader 读 ../.env）；application.yml 默认 dev profile；Flyway 迁移在 db/migration（**注意**：两个 V3 文件 V3__add_indexes + V3__add_playlist_fields，已知冲突）。

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
- ESSearchService 用 WebClient（webflux）而非 elasticsearch-java；异步初始化线程等 ES 容器 2s。
- Config profiles：dev（默认，SQL StdOut + knife4j）、docker（关 springdoc）、prod（无 JWT 兜底、actuator 受限）、test（H2 MODE=MySQL，排除 Redis/ES）。
- Application：VibeMusicBackendApplication（@SpringBootApplication + @MapperScan("com.vibemusic.mapper") + @EnableScheduling）。
