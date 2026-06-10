# vibeMusic 运维工具集

## 目录结构

```
ops/
├── cookie-monitor.py           # Cookie 存活监控（Python/Server酱）
├── check-cookies.py            # Cookie 快速检测（Python）
├── cpolar-monitor.py           # cpolar 隧道存活监控+自动重启（Python）
├── start-all.bat               # 一键启动全部服务
├── start-cpolar-tunnel.bat     # cpolar 隧道自动重连
├── start-cpolar-monitor.bat    # cpolar 监控启动器
├── start-tunnel.bat            # 通用隧道启动
├── start-cloudflare-tunnel.bat # Cloudflare 隧道
├── start-cloudflare-tunnel.ps1 # Cloudflare 隧道 (PowerShell)
├── setup-scheduler.ps1         # Windows 任务计划设置
└── requirements.txt            # Python 依赖
```

## 日志

所有监控日志统一写入 `../musicapi/logs/`：

| 日志文件 | 来源 |
|----------|------|
| `cookie-monitor.log` | Cookie 存活监控 |
| `cpolar-monitor.log` | cpolar 隧道监控 |
| `api-errors.log` | musicapi 错误 |
| `access.log` | musicapi 请求记录 |

## 使用

```bash
# 一键启动
start-all.bat

# Cookie 快速检测
python check-cookies.py

# Cookie 持续监控（需 SCKEY 环境变量）
set SCKEY=你的SendKey
python cookie-monitor.py

# cpolar 隧道监控（需 SCKEY 环境变量）
set SCKEY=你的SendKey
start-cpolar-monitor.bat
```
