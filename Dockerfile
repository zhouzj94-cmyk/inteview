# 构建阶段
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# 先复制Maven wrapper和pom.xml，利用Docker缓存
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:goals -q 2>/dev/null || true

# 复制源码并构建
COPY src src
RUN ./mvnw package -DskipTests -q

# 运行阶段
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 从构建阶段复制jar包
COPY --from=builder /app/target/*.jar app.jar

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -q --spider http://localhost:8080/api/tickets/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
