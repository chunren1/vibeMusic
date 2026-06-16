# ==========================
# musicapi — 网易云 + QQ 音乐统一 API 代理
# ==========================
FROM node:20-alpine

WORKDIR /app

# 复制依赖描述文件
COPY package.json package-lock.json ./

# 安装依赖（生产模式，跳过 devDependencies）
RUN npm ci --omit=dev

# 复制源码和配置
COPY server.js config.js ./

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1

EXPOSE 3000

CMD ["node", "server.js"]
