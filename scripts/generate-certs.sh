#!/bin/sh
# vibeMusic 生产环境 TLS 证书生成
# 用法:
#   自签证书（内网/Docker 内部）: sh scripts/generate-certs.sh
#   真实证书（公网）:         替换 certs/ 下的 fullchain.pem / privkey.pem

CERT_DIR="nginx/certs"
DAYS=3650
KEY_SIZE=2048

mkdir -p "$CERT_DIR"

if [ ! -f "$CERT_DIR/privkey.pem" ]; then
  echo "生成自签证书（$DAYS 天有效期）..."

  openssl req -x509 -nodes -days $DAYS -newkey rsa:$KEY_SIZE \
    -keyout "$CERT_DIR/privkey.pem" \
    -out "$CERT_DIR/fullchain.pem" \
    -subj "/C=CN/ST=Guangdong/L=Shenzhen/O=vibeMusic/CN=localhost" \
    2>/dev/null

  chmod 600 "$CERT_DIR/privkey.pem"

  echo "✅ 自签证书生成完成: $CERT_DIR/"
  echo "⚠️  浏览器会提示不安全，生产环境请替换为正式 CA 签名证书"
  echo ""
  echo "正式证书方案:"
  echo "  Cloudflare Tunnel: 无需替换（Tunnel 自带 HTTPS）"
  echo "  Let's Encrypt:     certbot certonly --standalone -d your-domain.com"
  echo "  Cloudflare Origin:  在 Cloudflare SSL/TLS → Origin Server 生成"
else
  echo "✅ 证书已存在，跳过生成"
fi
