# vibeMusic 运维手册

## 快速启动（推荐方式）

在项目根目录**双击 `vibeMusic.bat`**，选择操作：

```
[1] 启动全部服务 (musicapi + 前端)
[2] 启动内网穿透监控 (cpolar)
[3] 启动全部 + 监控 (推荐)
[4] 查看日志
[5] 检查服务状态
[6] 停止所有服务
```

---

## 服务架构

```
vibeMusic.bat              ← 统一入口（项目根目录）
  ├── musicapi (3000)      ← Node.js，含Cookie自动监控
  ├── 前端 vite (5173)      ← Vue 开发服务器
  ├── cpolar 监控            ← Python，每3~5分钟检测隧道
  └── 后端 (8080)           ← Spring Boot（需手动启动）
```

---

## 日志体系

所有日志统一在 `musicapi/logs/`：

| 日志文件 | 内容 | 写入者 |
|----------|------|--------|
| `access.log` | 每个 HTTP 请求的方法和路径 | musicapi 中间件 |
| `api-errors.log` | API 调用异常（网易云/QQ接口报错） | musicapi 全局错误处理 |
| `cookie-monitor.log` | Cookie 存活检查记录 | musicapi 内置监控 + Python 脚本 |
| `cpolar-monitor.log` | cpolar 隧道健康检查 + 重启记录 | cpolar-monitor.py |
| `degradation.log` | 音质逐级降级事件 | musicapi |

查看方式：控制台菜单选 [4]，或在 IDE 中直接打开 `musicapi/logs/`。

---

## 监控能力一览

| 能力 | 实现方式 | 触发频率 | 通知 |
|------|---------|---------|------|
| Cookie 存活检查 | musicapi 内置（随服务启动） | 启动1次 + 每1小时 | 无 |
| Cookie 定时巡检 | `cookie-monitor.py` + Windows 任务计划 | 每2小时 | Server酱微信 |
| cpolar 隧道检测 | `cpolar-monitor.py` | 每3~5分钟 | Server酱微信 |
| cpolar 自动重启 | 连续2次不可达 → kill + 重启 | 自动 | Server酱微信 |
| 音质降级统计 | musicapi `SongService` | 实时 | 写入日志 |

---

## 脚本目录

```
scripts/ops/
├── cpolar-monitor.py            # cpolar 存活监控（核心）
├── cookie-monitor.py            # Cookie 定时巡检
├── check-cookies.py             # Cookie 快速检测
├── start-all.bat                # 旧版一键启动（建议用根目录 vibeMusic.bat）
├── start-cpolar-monitor.bat     # cpolar 监控启动器
├── setup-scheduler.ps1          # Windows 任务计划配置
└── requirements.txt             # Python 依赖
```

---

## 依赖安装

```bash
pip install -r scripts/ops/requirements.txt
```
