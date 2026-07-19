---
name: restart-check
description: >
  Know exactly which services need restart after which type of change.
  Trigger when user says "need to restart", "生效了吗", or after modifying
  backend/config files.
---

# Skill: 服务重启依赖检查

## 免重启（热更新）
| 变更类型 | 生效方式 |
|---|---|
| Vue 文件 (.vue) | Vite HMR，保存即生效 |
| JS 文件 (.js，前端) | Vite HMR |
| CSS 文件 | Vite HMR |
| static assets | 即时生效 |

## 需重启对应服务
| 变更文件 | 需重启的服务 | 原因 |
|---|---|---|
| **Java 后端文件** | 后端 (8080) | Spring Boot 需要重新编译+启动 |
| SecurityConfig.java | 后端 | 安全配置在启动时加载 |
| application*.yml | 后端 | @Value 在启动时注入 |
| .env | musicapi (3000) + 后端 | 环境变量在进程启动时读取 |
| pom.xml | 后端（Maven 重载后） | 依赖变更 |
| musicapi/server.js | musicapi | Node.js 无热重载 |
| musicapi/config.js | musicapi | Cookie 在启动时解析 |
| nginx/*.conf | nginx 容器 | 配置在启动时加载 |

## 特殊注意
- **Windows 环境变量优先级 > yml**：改了 yml 但系统有同名 env var → 不生效
  → 检查：`[System.Environment]::GetEnvironmentVariable("KEY", "User")`
- **Docker 容器**：修改 docker-compose.yml 后需要 `docker-compose up -d` 重建
- **Maven 依赖**：改 pom.xml 后 IDEA 需 Reload Maven，然后重启后端
- **DevTools**：已安装，方法体修改可热重载（2-3秒），新增类/签名字段仍需手动重启

## 快速验证
```bash
# 后端是否在运行
curl http://localhost:8080/actuator/health

# musicapi 是否在运行
curl http://localhost:3000/cookie-status

# 前端是否在运行
curl http://localhost:5173
```