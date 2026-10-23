# ==========================
# musicapi — 网易云 + QQ 音乐统一 API 代理
# ==========================
FROM node:24-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53

WORKDIR /app

# 复制依赖描述文件
COPY package.json package-lock.json ./

# 安装依赖（生产模式，跳过 devDependencies）
RUN npm ci --omit=dev

# 复制源码（config.js 为 gitignore 文件，不入库也不进镜像；Cookie 由环境变量注入）
COPY server.js ./
COPY src ./src

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD node -e "require('http').get('http://localhost:3000/health',r=>{process.exit(r.statusCode===200?0:1)})"

EXPOSE 3000

# 切换到内置 node 用户（非 root）
RUN mkdir -p /app/logs && chown -R node:node /app/logs
USER node

CMD ["node", "server.js"]
