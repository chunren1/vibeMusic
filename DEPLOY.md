# vibeMusic 部署指南

## 开发环境

```bash
# 1. 启动基础设施（MySQL + Redis + MinIO）
npm run docker:dev

# 2. 启动后端（IntelliJ 运行 Spring Boot）
# 或命令行: cd vibeMusic-backend && mvn spring-boot:run

# 3. 启动前端 + BFF 网关
npm run dev
```

## Docker 全栈部署

```bash
# 1. 配置环境变量
cp .env.example .env
# 编辑 .env 修改密码和密钥

# 2. 构建前端
npm run build

# 3. 构建后端 JAR
cd vibeMusic-backend && mvn package -DskipTests && cd ..

# 4. 全栈启动（10 容器）
npm run docker:up

# 5. 查看状态
npm run docker:status
```

## 服务访问

| 服务 | 地址 | 说明 |
|------|------|------|
| Web | http://localhost | Nginx 入口 |
| Grafana | http://localhost:3001 | admin/admin |
| Prometheus | http://localhost:9090 | - |
| MinIO | http://localhost:9001 | rustfsadmin/ChangeMe456! |

## 监控启动

```bash
# 单独启动监控栈（MySQL/Redis 已运行的情况下）
npm run docker:monitor
```
