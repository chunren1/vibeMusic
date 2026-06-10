# vibeMusic 运维工具集

## 启动方式

```bash
# 业务启动
npm run dev                    # 启动 musicapi + 前端

# 内网穿透
npm run tunnel                 # 启动 cpolar http 5173

# 运维面板
npm run ops                    # 日志 / 状态 / Cookie检查
```

## 脚本目录

```
scripts/ops/
├── check-cookies.py         # Cookie 快速检测
├── cookie-monitor.py        # Cookie 定时巡检（配合 Windows 任务计划）
├── setup-scheduler.ps1      # Windows 任务计划配置
├── start-cloudflare-tunnel.bat   # Cloudflare 隧道（备用）
├── start-cloudflare-tunnel.ps1   # Cloudflare 隧道 PowerShell
└── requirements.txt         # Python 依赖
```

## 日志

所有日志统一写入 `musicapi/logs/`：

| 日志文件 | 内容 |
|----------|------|
| `access.log` | HTTP 请求记录 |
| `api-errors.log` | API 调用异常 |
| `cookie-monitor.log` | Cookie 存活检查 |
| `degradation.log` | 音质降级事件 |

查看方式：`npm run ops` → [1] 查看日志

## 依赖

```bash
pip install -r requirements.txt
```
