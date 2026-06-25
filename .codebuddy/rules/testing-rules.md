# vibeMusic 测试规范

## 后端测试 (JUnit 5 + MockMvc + H2)

### 单元测试 (Service 层)
- `@ExtendWith(MockitoExtension.class)`
- Mock 所有 Mapper/RedisTemplate/RestTemplate
- 必须覆盖：正常流程 + 异常降级 + null 安全 + 边界值
- 命名：`XxxServiceTest.java`

### 集成测试 (Controller 层)
- `@SpringBootTest` + `@AutoConfigureMockMvc`
- 使用 H2 内存数据库（BaseTest 基类）
- 必须覆盖：200 + 401 + 400 + 404 + 500
- 命名：`XxxControllerTest.java`

### 已知限制
- H2 不支持 `ON DUPLICATE KEY UPDATE` → `@Disabled` 跳过
- 需要 Docker 的测试（ES/MinIO）也 `@Disabled`

## 前端测试 (Vitest + jsdom)

### 组件测试
- 路径：`vibemusic-web/src/components/__tests__/`
- 必须 Mock：Audio/ResizeObserver/pinia store
- 全局 setup: `test-setup.js`

### Store 测试
- 路径：`vibemusic-web/src/stores/__tests__/`
- 必须覆盖：初始状态 + mutation + action + 边界

## 测试运行
```bash
npm test                    # 全量 (后端 + 前端)
npm run test:backend        # 仅后端
npm run test:frontend       # 仅前端
cd vibeMusic-backend && mvn test -Dtest=XxxTest  # 单个测试
```

## CI 门禁
- `test.yml`: push/PR → 自动跑后端 + 前端测试
- 编译失败或测试不通过 → 不允许合并
