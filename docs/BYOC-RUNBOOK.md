# BYOC 运行手册（Bring Your Own Cookie）

> 范围：用户自带网易云 Cookie 绑定 → 加密落库 → 网关透传 → 缓存隔离 → 失效重绑。
> 云端 Flyway **未启用（UNMANAGED）**，`docs/byoc-handapply.sql` 手工执行是唯一线上部署路径。
> 红线：任何日志 / 响应 / 文档示例中**绝不出现 Cookie 值**（明文、密文、长度之外的任何字节均不记录）。

## 1. 架构一览

| 层 | 文件 | 职责 |
|---|---|---|
| 迁移 V7 | `vibeMusic-backend/src/main/resources/db/migration/V7__user_cookie_slots.sql` | `users` 表加 5 列（4 网易 + 1 B 站预留） |
| 手工 SQL | `docs/byoc-handapply.sql` | 线上手工执行版（与 V7 ADD 语句逐行一致） |
| 加解密 | `vibeMusic-backend/src/main/java/com/vibemusic/common/utils/CookieCryptoService.java` | JDK AES-256-GCM，密钥来自 `COOKIE_ENC_KEY` env，缺失/非法时启动 fail-fast |
| 业务 | `vibeMusic-backend/src/main/java/com/vibemusic/service/UserService.java` | `save/clear/status/resolve + markNeteaseCookieInvalid` |
| 接口 | `vibeMusic-backend/src/main/java/com/vibemusic/controller/CookieController.java` | `PUT/DELETE /api/cookies/netease`，`GET /api/cookies/status` |
| 透传 | `vibeMusic-backend/src/main/java/com/vibemusic/service/NeteaseApiService.java` | `X-Vibe-User-Cookie` 请求头（`USER_COOKIE_HEADER` 常量），只透传不判定 |
| 网关 | `musicapi/src/cookie.js` | `resolveNeteaseCookie(req)` 执行三级回落 |
| 缓存隔离 | `vibeMusic-backend/src/main/java/com/vibemusic/service/SongSearchService.java` | per-user 请求绕过全部共享缓存读写 |
| 取链 | `vibeMusic-backend/src/main/java/com/vibemusic/service/SongPlayService.java` | `resolveUserCookie()` → 有则走 `getSongUrl/searchNetease(..., userCookie)` 重载 |
| Web API | `vibemusic-web/src/api/auth.js` | `saveNeteaseCookie / deleteNeteaseCookie / cookieStatus`（纯 API，无 Settings 行 UI，见 §7） |

## 2. API 形状（统一信封 `{code,message,data}`）

> 以下示例均不含任何 Cookie 值。时间戳为示意。

### 绑定：`PUT /api/cookies/netease`（需登录）

请求：`{ "cookie": "<用户粘贴的完整 Cookie>" }`

服务端校验（`CookieController` + `UserService.saveNeteaseCookie`）：非空 ≤ 8192 字符、必须含 `MUSIC_U=`。

```json
{ "code": 200, "message": "success", "data": { "bound": true } }
```

失败示例：`{ "code": 400, "message": "Cookie 缺少 MUSIC_U，请重新粘贴完整 Cookie 并绑定", "data": null }`

### 解绑：`DELETE /api/cookies/netease`（需登录）

```json
{ "code": 200, "message": "success", "data": { "bound": false } }
```

### 状态：`GET /api/cookies/status`（需登录，仅元数据）

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "netease": { "has": true, "valid": null, "updatedAt": "2026-09-14T10:00:00", "needsRebind": false },
    "bili": { "reserved": true }
  }
}
```

字段语义（`UserService.NeteaseCookieStatus`）：

- `has`：密文 + IV 均存在。
- `valid`：`null` = 未知（刚绑定 / 未探测），`true` = 有效，`false` = 失效。
- `updatedAt`：最近绑定时间，`null` = 从未绑定。
- `needsRebind`：`has && valid == false` 时为 `true`。
- 未登录访问 `401`（`SecurityConfig` 默认 `authenticated()` 已覆盖 `/api/cookies/**`，无需白名单）。

## 3. 回落优先级（网关侧执行）

`musicapi/src/cookie.js → resolveNeteaseCookie(req)`，`NeteaseApiService` 只负责透传：

```
per-request X-Vibe-User-Cookie（合法时） → 共享 NETEASE_COOKIE → ''（匿名）
```

- Header 校验：非空、≤ 8KB、须含 `MUSIC_U=`，否则视为脏值丢弃并回落共享（仅记长度的 WARN 日志）。
- 信任边界：唯一调用方是内网后端；公网 query/body 中的 `cookie` 参数仍由 `sanitizeNeteaseParams` 剥离，**公网绝不能直调网关**。
- Header 缺席 = 回落共享 / 匿名，与 BYOC 上线前行为完全一致。

## 4. 上线 SQL（手工执行，云端唯一路径）

`docs/byoc-handapply.sql` 第 1–5 行与 V7 的 5 个 `ADD COLUMN` **逐行一致**（已交叉核对）：

```sql
ALTER TABLE users ADD COLUMN netease_cookie_enc TEXT NULL;
ALTER TABLE users ADD COLUMN netease_cookie_iv VARCHAR(32) NULL;
ALTER TABLE users ADD COLUMN netease_cookie_updated_at DATETIME NULL;
ALTER TABLE users ADD COLUMN netease_cookie_valid TINYINT(1) NULL;
ALTER TABLE users ADD COLUMN bili_sessdata_enc TEXT NULL;
```

⚠️ **注意**：`byoc-handapply.sql` 第 6 行是回滚语句（`DROP COLUMN …` 全删），**部署时只执行第 1–5 行，绝不执行第 6 行**。第 6 行仅用于整表回滚演练。

列语义：`netease_cookie_enc` = AES-GCM 密文段（base64），`netease_cookie_iv` = IV 段（base64），两者合起来即 `CookieCryptoService` 的 `"ivB64:ctB64"` 格式；`updated_at` = 最近绑定时间；`valid`：NULL 未知 / 1 有效 / 0 失效；`bili_sessdata_enc` = Phase-2 预留（Phase-1 无任何读写）。

## 5. `COOKIE_ENC_KEY` 配给（仅环境变量）

- 格式：32 字节随机数的 base64 编码（AES-256）。生成命令（只给命令，不给值）：
  ```bash
  openssl rand -base64 32
  ```
- 注入：后端经 `@Value("${COOKIE_ENC_KEY:}")` 读取进程环境变量；缺失 / 非法 base64 / 解码后非 32 字节 → 启动即抛 `IllegalStateException`，**绝无兜底默认值**（`CookieCryptoService` 构造器）。
- 日志红线：只记“缺失 / 非法 / 长度错误”，绝不打印密钥值、Cookie 明文或密文（绑定成功日志仅记 `userId + cookieChars`）。
- 轮换步骤：
  1. 生成新 key，在维护窗口将后端环境变量切到新值并重启（新绑定即用新 key 加密）。
  2. 旧 key 加密的历史行无法用新 key 解密 —— `resolveNeteaseCookie` 解密失败返回 `Optional.empty()`（自动回落共享/匿名，不抛错），受影响用户 `has=true` 但上游走共享通道。
  3. 通知受影响用户重新绑定（其 `GET /status` 在下次 need-login 探测后置 `needsRebind:true`），或安排离线重加密脚本（用旧 key 解密 → 新 key 加密，两 key 同时在场，脚本不落盘明文）。
  4. 确认无旧密文残留后销毁旧 key。

## 6. 重绑流程（失效探测 → 标记 → 重绑）

1. 探测：`NeteaseApiService.isNeedLoginPayload(body)` 判定上游 need-login —— 顶层 `code` 为 `301`/`-101`，或 `message` 含“需要登录”/“need login”（大小写不敏感），或 `/login/status` 形 `{account:{anonymous:true}}`。**明确非信号**：`code=200 + data[0].url=null` 只是无版权，绝不标记。
2. 标记：调用方调 `UserService.markNeteaseCookieInvalid(userId, upstreamCode)` → `netease_cookie_valid=0`（幂等，日志仅 `userId + upstreamCode`）。
3. 提示：该用户 `GET /api/cookies/status` 返回 `needsRebind:true`，客户端引导重绑。
4. 重绑：用户重新 `PUT /api/cookies/netease` → `saveNeteaseCookie` 写入新密文 + `updatedAt=now` + `valid=NULL`（未知，等待下次探测）。
5. 解绑即清零：`clearNeteaseCookie` 将 4 列全置 NULL。

## 7. 缓存隔离声明

- per-user 搜索请求**绕过全部共享缓存的读与写**（`SongSearchService.search(..., userCookie)`，`perUser` 分支）：跳过 Redis 读、ES 读、单飞锁等待，直查上游；`doApiSearch` 中 `perUser` 时**不回写**任何共享键。VIP 结果绝不落入共享键，也绝不读取他人 / 匿名缓存。
- 取链（`SongPlayService`）按请求解析用户 Cookie，有则走带 `userCookie` 的重载直调网关（经 `X-Vibe-User-Cookie`），无则走旧路径。
- 推荐缓存与用户隔离：`RecommendService` 缓存键按 `user:{userId}` / 设备键分区（与匿名共享键分离）。
- 网关内存缓存（如有）同样只键入共享通道结果；per-user header 请求不污染共享缓存条目。

## 8. 客户端

- Web：`vibemusic-web/src/api/auth.js` 提供 `saveNeteaseCookie(cookie)` → `PUT /cookies/netease`、`deleteNeteaseCookie()` → `DELETE /cookies/netease`、`cookieStatus()` → `GET /cookies/status`。Cookie 只经内存 / 服务端持有，**前端不落地 `localStorage`**（单测显式断言，`src/api/__tests__/auth.test.js`）。
- App（原生仓 `vibemusic-native/`，包名 `com.cyk666.vibemusic`）：`VibeApi.kt` 有 `buildSaveCookieBody` / `parseCookieStatus` / `saveNeteaseCookie` / `deleteNeteaseCookie` / `cookieStatus()`（走 `authed{}` + `checkEnvelope`，401 经 `AuthException`）；`BeautyPhase10.kt` 的 `buildSettingsRows` 经可选 `cookieLabel` 插入"网易 Cookie"行 + `cookieRowSubtitle`（已绑定 / 已过期，请重新绑定 / 未绑定）；`MainActivity.kt` Settings 区有状态加载 + `CookiePasteDialog`（提示"粘贴你自己的网易 Cookie，不要填密码"，粘贴文本只活在对话框 `remember` 状态，关闭即销毁，本地零持久化；`AuthStore` 未动）。单测 `CookieBindTest.kt` 13 例（解析 5 形态 + 非 200/exception 路径 + subtitle + 行插入兼容）。注意：原生仓与本仓是两个仓库，`git status` 要分开看。
- 后端响应绝不回显 Cookie 本体（控制器单测断言“任何响应不得含 Cookie 子串”，`CookieControllerTest`）。

## 9. 测试矩阵

| 层 | 命令 | 门禁 / 覆盖 |
|---|---|---|
| 后端 | `cd vibeMusic-backend && mvn verify`（注意：`mvn test` 不触发门禁） | JaCoCo `LINE COVEREDRATIO ≥ 0.60`（BUNDLE 级，`pom.xml`）；BYOC 单测：`CookieCryptoServiceTest`、`CookieControllerTest`、`CookieControllerRebindTest`、`UserNeteaseCookieServiceTest`、`NeteaseUserCookieHeaderTest`、`NeteaseCookieInvalidationTest`、`SongSearchUserCookieIsolationTest`、`SongPlayUserCookieTest` |
| 网关 | `cd musicapi && node --test --test-force-exit test/netease-user-cookie.test.js`（纯单测，无需启动 server） | per-request header 合法/非法/缺席三态 + 回落断言 |
| Web | `cd vibemusic-web && npm test`（`vitest run`） | `src/api/__tests__/auth.test.js`：PUT/DELETE/GET 路径 + 不经 `localStorage` 断言 |
| App | `cd vibemusic-native && ./gradlew :app:testDebugUnitTest --no-daemon --tests "*CookieBind*"`（必须带 `--no-daemon`） | `CookieBindTest` 13 绿 + 相邻 `BeautyPhase10Test` / `ParseTest` 回归绿 |

## 10. Phase-2 Bilibili 交接

- 预留列：`users.bili_sessdata_enc TEXT NULL`（V7 第 5 个 ALTER，`byoc-handapply.sql` 第 5 行）。Phase-1 **零读零写**。
- 状态占位：`GET /api/cookies/status → data.bili = {reserved:true}`，前端据此渲染“即将支持”而非绑定入口。
- Phase-2 待做：`CookieController` 加 `PUT/DELETE /api/cookies/bili` + `UserService` save/clear/status/resolve 复用 `CookieCryptoService`（同 key 或独立 `BILI_ENC_KEY` 待定）+ 网关 B 站透传头 + 状态 `{has,valid,updatedAt,needsRebind}` 与网易侧对齐。列名已冻结，**勿改名**。
