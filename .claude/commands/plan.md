# /plan — 规划模式

阅读 Spec 文件：`$ARGUMENTS`

## 流程

1. 分析当前代码库，定位所有需要修改的文件
2. 按依赖关系排列实施步骤
3. 输出计划，等待用户确认 —— **绝对不要修改任何代码**

## 输出格式

```markdown
## 实施计划：[功能名称]

### 前置检查
- [ ] 是否需要数据库迁移？
- [ ] 是否影响已有搜索索引？
- [ ] 是否涉及 API 兼容性变更？

### 子任务 1：[标题]
- **文件**：`path/to/File.java`
- **修改**：方法签名 / 字段变更 / 逻辑调整
- **风险**：[数据库 / 搜索 / 兼容性 / 无]
- **验证**：`cd vibeMusic-backend && mvn compile -DskipTests -q`

### 子任务 2：[标题]
...
```

## 联动检查清单

若修改涉及以下模块，必须在计划中标注所有受影响方：

| 改动点 | 需检查的联动模块 |
|--------|-----------------|
| `search()` 返回类型 | RecommendService / AssistantController / getRandomSongs |
| Entity 时间字段 | 对应的 DB Migration / MetaObjectHandler |
| SecurityConfig | 所有新增 Controller 端点 |
| 前端页面 | 对应的 views/mobile/ 移动端版本 |

## 验证规则

- 每完成一步：运行 `cd vibeMusic-backend && mvn compile -DskipTests -q`
- 修改涉及多模块：列出受影响模块并逐一验证
