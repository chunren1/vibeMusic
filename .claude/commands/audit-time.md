# /audit-time — 时间字段审计

扫描 `vibeMusic-backend/src/main/java/com/vibemusic/entity/` 下所有 MyBatis-Plus Entity 类。

## 审计维度

对每个时间字段检查：

| 检查项 | 说明 |
|--------|------|
| `@TableField` 配置 | 是否有 `insertStrategy = FieldStrategy.NEVER`？ |
| DB 默认值 | 对应列是否有 `DEFAULT CURRENT_TIMESTAMP`？ |
| 自动填充 | 是否被 `MetaObjectHandler` 覆盖？ |

## 输出格式

```markdown
## 时间字段审计报告

| 表名 | 字段 | insertStrategy | DB 默认值 | 自动填充 | 风险 | 修复建议 |
|------|------|---------------|-----------|---------|------|---------|
| t_xxx | created_at | 缺失 | CURRENT_TIMESTAMP | 否 | 🔴 高 | 加 FieldStrategy.NEVER |
| t_xxx | updated_at | NEVER | CURRENT_TIMESTAMP | 否 | 🟢 低 | 无 |
```

## 风险等级

- 🔴 **高**：前端/客户端可能传入时间字段值，覆盖 DB 默认值
- 🟡 **中**：配置不完整，特定场景下可能出错
- 🟢 **低**：配置正确，无需修复

## 输出内容

- 完整的审计表格
- 对每个 🔴/🟡 风险项给出：Java 修复代码 + SQL Migration（如需要）
- 已知已修复项列表（参考 CLAUDE.md Notes 段）

❗ 仅输出分析报告，不修改代码。
