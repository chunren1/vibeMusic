# 本机展示用监控栈（按需启动，非生产依赖）

> 定位：**可视化展示 / 排障用**，跑在**你自己电脑**上，不部署到云。
> 云上只保留 App 运行必需的 4 个容器（backend / musicapi / mysql / redis），
> 可用性告警由服务器自己的 cron 看门狗负责（`scripts/health-alert.sh` → Server酱）。

## 启动（需要看图表时）

```bash
cd ~/projects/vibeMusic
docker compose -f monitoring/docker-compose.yml up -d
# Prometheus http://127.0.0.1:9090 · Grafana http://127.0.0.1:3001
```

## 关闭（不看就关掉，不占内存）

```bash
docker compose -f monitoring/docker-compose.yml down
```

## 说明

- 三个服务均为 `restart: "no"`，**不会被 Docker/WSL 自动拉起**；停掉后不会偷偷跑。
- `blackbox-exporter` 用于外部可用性探测，`prometheus` 抓取云上指标依赖 SSH 隧道（见 `docs/cloud-source-of-truth.md`）。
- 采集目标全部指向 `127.0.0.1`，不对外暴露端口。
- 云上不跑 Prometheus/Alertmanager/Grafana；27 条规则仅在本地栈启动时才会被评估。
