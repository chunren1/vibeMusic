# Skill: 修复实体时间字段（MyBatis-Plus 自动填充 bug）

## 触发条件
- 用户说"XX表的时间字段为空/不自动填"
- 新增实体类时

## 根因
MyBatis-Plus 的 `@Builder` + `@AllArgsConstructor` 会将未设字段初始化为 null，
随 INSERT SQL 显式传入 null 覆盖 MySQL `DEFAULT CURRENT_TIMESTAMP`。

## 修复步骤

### 1. 实体类修改
将时间字段注解改为：
```java
// ❌ 错误写法
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createdAt;

// ✅ 正确写法
@TableField(fill = FieldFill.INSERT, insertStrategy = FieldStrategy.NEVER)
private LocalDateTime createdAt;
```

### 2. Service 层显式赋值（兜底）
```java
// 不用 Builder 设时间字段，改用 new + set
XxxEntity entity = new XxxEntity();
entity.setUserId(userId);
entity.setCreatedAt(LocalDateTime.now()); // 显式赋值
mapper.insert(entity);
```

### 3. MyBatisPlusConfig 检查
确保 `insertFill()` 和 `updateFill()` 覆盖该字段：
```java
this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
this.strictUpdateFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
```

### 4. 编译验证
```bash
cd vibeMusic-backend && mvn compile -DskipTests -q
```

## 已知历史问题
- `UserFavorite.createdAt` — 已修复 (2026-06-26)
- `PlayHistory.playedAt` — 已修复 (2026-06-26)
- `BaseEntity.createdAt/updatedAt` — 已修复 (2026-06-26)
- `Playlist.createdAt` — 原本就正确
- `PlaylistSong.addedAt` — 原本就正确
