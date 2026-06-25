# Skill: 为后端 Service/Controller 生成测试

## 触发条件
用户指定一个 Service 或 Controller 类，要求编写测试

## 执行流程

### 1. 分析被测类
- 读取源码，理解方法签名、依赖注入、返回值
- 识别需要 Mock 的依赖（Mapper, RedisTemplate, RestTemplate）

### 2. 生成单元测试（Service 层）
- 路径：`vibeMusic-backend/src/test/java/com/vibemusic/service/`
- 使用 `@ExtendWith(MockitoExtension.class)`
- Mock 所有 Mapper/外部服务
- 覆盖：正常流程 + 异常降级 + 边界值 + null 安全

### 3. 生成集成测试（Controller 层）
- 路径：`vibeMusic-backend/src/test/java/com/vibemusic/controller/`
- 使用 `BaseTest` 基类 + `MockMvc`
- 覆盖：200 成功 + 400/401/404/500 错误码

### 4. 测试数据
- 使用 H2 内存数据库（`@SpringBootTest` 自动切换）
- 注意事项：H2 不支持 `ON DUPLICATE KEY UPDATE`，需 `@Disabled` 跳过

### 5. 运行验证
```bash
cd vibeMusic-backend
mvn test -Dtest=xxxTest
```

## 示例请求
"为 PlayHistoryService.record() 方法写单元测试"
"为 SongController.search() 写集成测试，覆盖缓存命中、ES降级、API降级"
