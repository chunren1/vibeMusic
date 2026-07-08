# vibeMusic TLS 证书目录

## 快速生成

```bash
# 1. 打开 Git Bash 或 WSL
# 2. 生成自签证书（Docker 内部用）
openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
  -keyout nginx/certs/privkey.pem \
  -out nginx/certs/fullchain.pem \
  -subj "/CN=localhost"
```

## 正式部署

- **Cloudflare Tunnel**: 无需替换，Tunnel 自带 HTTPS
- **Let's Encrypt**: `certbot certonly --standalone -d your-domain.com`
- **Cloudflare Origin CA**: Cloudflare 控制台 → SSL/TLS → Origin Server 生成

将此目录下的 `fullchain.pem` 和 `privkey.pem` 替换为正式证书文件即可。
