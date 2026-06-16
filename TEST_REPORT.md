# vibeMusic 全链路测试报告

> 生成时间：2026-06-16

---

## 一、测试基础设施

| 维度 | 技术栈 | 状态 |
|------|--------|------|
| 后端单元测试 | JUnit 5 + AssertJ + H2 内存数据库 | ✅ |
| 后端集成测试 | MockMvc + SpringBootTest | ✅ |
| 前端单元测试 | Vitest + jsdom + Pinia | ✅ |
| 数据隔离 | @Transactional + DELETE before INSERT | ✅ |
| 覆盖率 | 42 后端测试 + 21 前端测试 | ✅ |

---

## 二、后端测试：42/42 通过

### 单元测试

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|----------|
| UserServiceTest | 10 | 注册、登录、查找、修改密码、更新资料 |
| SongServiceTest | 5 | 歌曲入库/更新、按ID/sourceId查询 |
| FavoriteServiceTest | 6 | 收藏/取消、列表查询、数量限制、收藏集 |
| PlayHistoryServiceTest | 4 | 播放记录、去重、上限、空列表 |
| PlayHistoryCleanupServiceTest | 2 | 7天前清理、近期保留 |

### 集成测试 (MockMvc)

| 测试类 | 用例数 | 覆盖端点 |
|--------|--------|----------|
| AuthControllerTest | 4 | POST /register, GET /me |
| SongControllerTest | 11 | search, banner, play, stream, lyric, history, random, es-health |

---

## 三、前端测试：21/21 通过

| 测试套件 | 用例数 | 覆盖内容 |
|----------|--------|----------|
| PlayerStore | 21 | 初始化、队列操作、切歌、播放模式、静音、格式化、持久化 |

---

## 四、命令速查

```powershell
# 全部测试（后端 + 前端）
npm test

# 单独后端
npm run test:backend

# 单独前端
npm run test:frontend

# 前端测试（监听模式，开发时实时反馈）
cd vibemusic-web && npm run test:watch
```

### CI 自动化

推送到 main/master 分支或创建 PR 时，GitHub Actions 自动运行：

- **Backend**: `mvn test` on ubuntu-latest + Java 17
- **Frontend**: `vitest run` on ubuntu-latest + Node 20
- 测试报告自动存档 7 天

配置文件：`.github/workflows/test.yml`

---

## 五、验收清单 (Acceptance Checklist)

### 基础设施
- [x] MySQL 容器启动 (healthy)
- [x] Redis 容器启动 (healthy)
- [x] Elasticsearch 容器启动 (healthy)
- [x] RustFS/MinIO 容器启动 (healthy)
- [x] Nginx 容器启动 (healthy)
- [x] 所有 Docker healthcheck 通过

### 后端
- [x] Maven 编译成功
- [x] UserService 完整单元测试
- [x] SongService 完整单元测试
- [x] FavoriteService 完整单元测试
- [x] PlayHistoryService 完整单元测试
- [x] PlayHistoryCleanupService 完整单元测试
- [x] AuthController MockMvc 集成测试
- [x] SongController MockMvc 集成测试

### 前端
- [x] npm install 成功
- [x] PlayerStore 单元测试 (21 cases)
- [x] Vite 构建成功

### 缺陷与改进
- [x] `Result.error(String)` 返回 500 → 已改为 400（23处调用全部为业务校验）
- [x] `MissingServletRequestParameterException` 未处理 → 已添加处理器返回 400
- [ ] 前端缺少组件级测试（PlayerBar、HomeView 等）
