# vibeMusic 编码规范

## 命名规范

### Java
- 类名：PascalCase，如 `SongSearchService`
- 方法名：camelCase，如 `searchByKeyword()`
- 常量：UPPER_SNAKE，如 `MAX_HISTORY`
- 包名：全小写，`com.vibemusic.controller`
- DTO 后缀：`DTO`，`Result`，`SearchResult`

### Vue
- 组件名：PascalCase，移动端加前缀 `M`，如 `MHomeView.vue`
- Composable：`use`前缀，如 `useAudioBackground.js`
- Store：`use`前缀，如 `usePlayerStore`
- CSS 变量：`--m-` 前缀用于移动端设计 token

### 数据库
- 表名：snake_case，如 `play_history`
- 列名：snake_case，如 `created_at`
- 索引：`idx_`前缀，如 `idx_user_created`

## 注释要求

### 必须添加注释的场景
- 所有 public 方法加 JavaDoc
- 复杂业务逻辑（如搜索三级缓存）
- 设计决策（为什么这样写而非那样写）
- @Deprecated 方法

### 禁止
- 无意义的注释如 `// set name`
- 注释掉的代码块（用 Git 历史追溯）
- TODO 超过一周未处理

## 错误处理

- Controller 层：抛出 → GlobalExceptionHandler 统一处理
- Service 层：抛 BusinessException(code, msg) 而非 RuntimeException
- 调用第三方 API：try-catch + 日志 + 降级兜底
- 数据库操作：事务内异常必须 rollbackFor = Exception.class

## 日志规范

- 搜索：`[CACHE-LAYER]` / `[ES-LAYER]` / `[API-LAYER]` 前缀
- 错误：`log.error("描述: {}", e.getMessage())`
- 关键业务操作：info 级别记录 userId + 操作内容
- 正式环境禁止 System.out.println

## Lock 管理
- 锁对象用完即清理（`hasQueuedThreads()==false` 时 remove）
- 锁内不做 I/O 操作，I/O 在事务外
