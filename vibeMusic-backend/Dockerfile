# ==========================
# vibeMusic 后端 Dockerfile
# ==========================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 复制构建产物（需先执行 mvn package -DskipTests）
COPY target/*.jar app.jar

# 健康检查端口
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
