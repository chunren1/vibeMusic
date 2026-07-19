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

## 文件写入工具约束

### `write_to_file` 静默失败
对 `.vue`、`.java`、`.md` 文件，当目标文件已存在时，`write_to_file` 会静默失败（显示写入成功但文件内容为空）。

**回避方案**：对以上扩展名文件，先 `delete_file` 再 `write_to_file`；或直接用 PowerShell：
```powershell
[System.IO.File]::WriteAllText("path", $content, [System.Text.UTF8Encoding]::new($false))
```
创建新文件时不受影响，仅覆盖已有文件时会触发此 bug。

### 调用已有函数/方法前必须验证
调用项目中已存在的函数、方法、API 前，**必须先用 search_content 或 grep 搜索确认名称正确**，不允许凭直觉命名。
反例：以为有 `playNext()`，实际只有 `next()`，导致静默报错+功能不生效。
