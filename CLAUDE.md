# vibeMusic — Claude Code 项目上下文

> 自建全栈音乐平台，支持网易云 + QQ 音乐双源聚合搜索与高品质播放。

## 技术栈
- 前端：Vue 3 + Vite + Pinia（桌面端 11 页 + 移动端 12 页）
- 后端：Spring Boot 4 + MyBatis-Plus + JWT
- 网关：Express BFF（musicapi，端口 3000）
- 存储：MySQL 8 + Redis 7 + ES 8.18 + MinIO
- 部署：Docker Compose 7 容器

## 项目结构
```
vibeMusic/
├── vibemusic-web/          # Vue 3 前端 (端口 5173)
│   └── src/
│       ├── views/          # 桌面端 + views/mobile/ 移动端
│       ├── stores/         # Pinia: player, auth, favorite
│       ├── api/            # Axios 封装
│       └── composables/    # useAudioBackground, useIsMobile
├── vibeMusic-backend/      # Spring Boot (端口 8080)
│   └── src/main/java/com/vibemusic/
│       ├── controller/     # 7 个 Controller
│       ├── service/        # 14 个 Service
│       └── entity/         # 6 个实体
├── musicapi/               # Express 网关 (端口 3000)
│   ├── server.js           # 聚合搜索 + 评分去重 + Cookie管理
│   └── config.js           # Cookie 配置
└── docker-compose.yml      # 7 容器编排
```

## 开发命令
```bash
# 后端编译
cd vibeMusic-backend && mvn compile -DskipTests -q

# 前端启动
npm run dev     # 同时启动 musicapi + 前端

# 全栈测试
npm run test                    # 全量测试
npm run test:backend            # 仅后端
npm run test:frontend           # 仅前端
```

## 关键规则（详见 .codebuddy/rules/）
- 修改 Java 文件后必须提醒用户重启后端
- 实体类时间字段必须加 `insertStrategy = FieldStrategy.NEVER`
- 修改 `search()` 返回类型时须检查所有调用处
- 新增 Controller 必须加 `@Operation` 注解
- 前端新页面需要同时创建桌面端和移动端版本
- 安全：JWT httpOnly Cookie + BCrypt + 幂等防护

## 已知问题
- `UserFavorite.createdAt` 已修复 (FieldStrategy.NEVER)
- `PlayHistory.playedAt` 已修复
- `BaseEntity.createdAt/updatedAt` 已修复
- QQ 搜索需要 `t:0` 参数指定单曲类型
