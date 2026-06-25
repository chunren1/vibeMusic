# vibeMusic 安全规范

## 认证与授权

### JWT
- 密钥强制环境变量 `${JWT_SECRET}`，禁止硬编码
- Token 24h 过期，生产建议 access 15min + refresh 7d
- 优先读 Authorization Header → 降级 Cookie

### Cookie
- HttpOnly = true（JS 不可读）
- SameSite = Lax（防 CSRF）
- Secure = true（仅 HTTPS 传输）
- 使用 `response.addHeader("Set-Cookie", ...)` 而非 `cookie.setAttribute()`（跨容器兼容）

## 数据保护

### 密码
- BCrypt `$2b$10$` 加密存储
- 最短 8 位
- 明文永不落盘

### API Key
- `.env` 文件管理真实密钥，`.env.example` 为占位符模板
- `.gitignore` 排除 `.env`、`.env.docker`、`musicapi/config.js`
- `docker-compose.yml` 使用 `${VAR:-default}` 引用，不写死

### Pre/Post Handler
- 文件上传限制 2MB（`max-file-size: 50MB` → 改为头像专用端点限制）

## SQL 注入防护

- MyBatis `#{}` 预编译，禁止 `${}` 拼接用户输入
- LIMIT/ORDER BY 参数加 `Math.max(1, Math.min(count, MAX))` 安全边界

## 幂等性

- 写操作（POST/PUT/DELETE）前端自动附 `X-Request-Id: UUIDv4`
- 后端 `IdempotentGuard` + Redis 5min 去重

## API 限流

- AI 对话：`RateLimitService` Redis INCR 滑动窗口，每用户每分钟限 10 次
- 搜索/下载：建议后续接入 Bucket4j 或 Nginx limit_req

## 敏感信息泄露

- 生产环境关闭 Swagger（仅 `dev` profile 可用）
- Actuator 仅暴露 `/health`，禁用 `/env`、`/configprops`
- 日志中不打印 Token、密码、Cookie
