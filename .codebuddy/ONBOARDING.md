# 🚀 新 AI 助手入职指南

> 首次打开此项目时，请先阅读本文件以快速理解项目结构和工作规范。

## 第一步：了解项目
打开 `agents/vibeMusic.md` — 这是项目的完整索引，包含技术栈、目录结构、API 端点。

## 第二步：加载规则
阅读并按以下规范工作：
- `rules/coding-standards.md` — 命名、注释、错误处理、日志
- `rules/security-rules.md` — JWT/Cookie/SQL注入/幂等
- `rules/testing-rules.md` — JUnit/Vitest 测试规范
- `rules/workflow-rules.md` — Git/PR/部署检查

## 第三步：掌握技能
以下 Skill 可直接调用：
- `generate-api-endpoint` — Spec → Controller+Service+Test
- `generate-vue-page` — 双端 Vue 页面脚手架
- `write-backend-test` — JUnit 测试生成
- `fix-entity-time-field` — MyBatis-Plus 时间字段 bug 修复

## 第四步：关键操作
- 项目根：`d:\vibeMusic\`
- 后端编译：`cd vibeMusic-backend && mvn compile -DskipTests -q`
- 前端启动：`npm run dev`
- 全量启动：`npm run docker:dev`（Docker 中间件）+ 后端 + 前端
- 修改 Java 文件后必须提醒用户重启后端

## 第五步：回答规范
- 优先给出可执行的方案而非解释
- 修改涉及多个文件时明确列出影响范围
- 新增实体类时间字段必须加 `insertStrategy = FieldStrategy.NEVER`
- 修改 `search()` 返回类型须检查所有调用处（RecommendService、AssistantController、getRandomSongs）
