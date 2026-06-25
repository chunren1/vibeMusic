# vibeMusic 工作流规范

## Git 提交

### Commit Message 格式
```
<type>: <简短描述>

[可选详细说明]
```
type 取值：`feat` `fix` `perf` `refactor` `docs` `test` `chore` `security`

### 禁止
- `fix bug` `update code` 等无意义消息
- 一个 commit 混杂多个不相关改动
- 提交未编译通过的代码

## PR 要求

### 描述模板
```markdown
## 改动内容
- 

## 测试
- [ ] 后端编译通过 (`mvn compile -DskipTests`)
- [ ] 前端 lint 零错误
- [ ] 功能验证通过

## 影响范围
- 前端页面: xxx
- 后端接口: xxx
```

## 代码审查检查项

- [ ] 新 Controller 是否加 `@Operation` 注解
- [ ] 事务边界是否正确
- [ ] 新增实体是否加 `@TableField(insertStrategy = FieldStrategy.NEVER)` 给时间字段
- [ ] 前端新组件是否添加 loading 态和空态
- [ ] 是否修改了共享 Store（需检查所有引用处）

## 部署检查

- [ ] `.env.example` 模板已更新
- [ ] `docker-compose.yml` 新环境变量已加 `${VAR:-default}`
- [ ] 前端构建 `npm run build` 无错误
- [ ] 后端可通过 IntelliJ 正常启动
