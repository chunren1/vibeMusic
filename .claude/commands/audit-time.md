# /audit-time — 时间字段审计

扫描项目中所有 MyBatis-Plus Entity 类。

检查每个时间字段：
- 表名、字段名、注解配置（@TableField）
- 是否有 `insertStrategy = FieldStrategy.NEVER`？
- DB 是否有 `DEFAULT CURRENT_TIMESTAMP`？

输出 Markdown 表格：
```markdown
| 表 | 字段 | insertStrategy | DB默认值 | 风险 | 修复建议 |
```

对高风险项给出完整的修复方案（包括 Java 代码和 SQL Migration）。

❗ 不要修改任何代码，仅输出分析报告。
