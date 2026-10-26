# ==========================
# vibeMusic 后端 Dockerfile
# ==========================
FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048

# 创建非 root 用户
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# 安装 curl 用于健康检查（apk 官方源国内常超时，切阿里云镜像）
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache curl

# 复制构建产物（需先执行 mvn package -DskipTests）
COPY target/*.jar app.jar

# 健康检查端口
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# 切换到非 root 用户
USER app

# JVM 参数可被 JAVA_OPTS 覆盖（compose/云环境注入）；exec 使 java 成为 PID 1，正确接收 SIGTERM
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# 时区：必须是 Asia/Shanghai，与 MySQL 容器（CST）一致。
# 原因（2026-10-26 线上真实事故）：Java 用 LocalDateTime.now() 写 play_history.played_at /
# updated_at，而 created_at 走 DB 的 DEFAULT CURRENT_TIMESTAMP。JVM 若是 UTC 而 MySQL 是 CST，
# 同一行里两种时间差 8 小时，App 原样显示 → 「最近播放」时间全错。
# 镜像自带 tzdata（/usr/share/zoneinfo/Asia/Shanghai），故只需声明 TZ。
# 注意：alpine 镜像若缺 tzdata，TZ 会被静默忽略（musicapi 就踩过，仍显示 UTC）。
ENV TZ=Asia/Shanghai
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
