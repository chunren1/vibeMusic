# vibeMusic 运维工具集

## 启动方式

```bash
# 运维面板（跨平台，推荐）
cd ..\..              # 返回项目根目录
npm run ops

# 或直接启动 cpolar 监控
start-cpolar-monitor.bat
```

## 脚本目录

```
scripts/ops/
├── cpolar-monitor.py             # cpolar 隧道存活监控 + 自动重启（核心）
├── cookie-monitor.py             # Cookie 定时巡检（配合 Windows 任务计划）
├── check-cookies.py              # Cookie 快速检测
├── start-cpolar-monitor.bat      # cpolar 监控 Windows 启动器
├── start-cloudflare-tunnel.bat   # Cloudflare 隧道（备用）
├── start-cloudflare-tunnel.ps1   # Cloudflare 隧道 PowerShell
├── setup-scheduler.ps1           # Windows 任务计划配置
└── requirements.txt              # Python 依赖
```

## 日志

所有监控日志统一写入 `musicapi/logs/`：

| 日志文件 | 内容 | 来源 |
|----------|------|------|
| `access.log` | HTTP 请求记录 | musicapi |
| `api-errors.log` | API 调用异常 | musicapi |
| `cookie-monitor.log` | Cookie 存活检查 | musicapi 内置 + Python 脚本 |
| `cpolar-monitor.log` | cpolar 隧道健康检查 + 重启 | cpolar-monitor.py |
| `degradation.log` | 音质降级事件 | musicapi |

查看方式：
- IDE 中直接打开 `musicapi/logs/`
- 或 `npm run ops` → [2] 查看日志

## 监控能力

| 能力 | 实现 | 频率 | 通知 |
|------|------|------|------|
| Cookie 存活 | musicapi 内置 | 每小时 | 无 |
| Cookie 巡检 | cookie-monitor.py | 每2小时（计划任务） | Server酱微信 |
| cpolar 隧道 | cpolar-monitor.py | 每3~5分钟 | Server酱微信 |

## 依赖

```bash
pip install -r requirements.txt
```
