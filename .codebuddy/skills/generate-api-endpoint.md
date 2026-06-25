# Skill: 根据 Spec 生成 API 端点

## 触发条件
用户提供接口契约（端点路径、请求/响应格式、业务规则）

## 执行流程

### 1. 创建 Controller 方法
- 路径：`vibeMusic-backend/src/main/java/com/vibemusic/controller/`
- 注解：`@Operation(summary = "...")`
- 参数校验：`@RequestParam` 或 `@RequestBody` + null 检查
- 权限：`UserService.getCurrentUserId()` 获取当前用户

### 2. 创建 Service 方法
- 路径：`vibeMusic-backend/src/main/java/com/vibemusic/service/`
- 事务：`@Transactional(rollbackFor = Exception.class)`
- 日志：操作前后 info 日志
- 返回：明确返回值类型

### 3. 创建测试
- 路径：`vibeMusic-backend/src/test/java/com/vibemusic/controller/`
- 框架：JUnit 5 + MockMvc + H2
- 覆盖：正常流程 + 异常流程 + 边界值

### 4. 注册端点
- `SecurityConfig.java` 中配置权限（permitAll / 需要登录）

### 5. 前端 API
- 路径：`vibemusic-web/src/api/song.js`（或新文件）
- 格式：`export function xxxApi(params) { return request.method('/path', ...) }`

## 示例 Spec 格式
```markdown
POST /api/favorites/remove-batch
Request: { "sourceIds": ["id1", "id2"] }
Response: { "code": 200, "data": 2 }
权限: 需登录
```

## 注意事项
- 返回值统一使用 `Result<T>` 包装
- 批量操作使用批量 SQL 而非循环
- 时间字段用 `insertStrategy = FieldStrategy.NEVER`
