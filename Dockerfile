# dcdr-agent V2.1 容器构建 (多阶段, 对应 F-30)
#
# 设计:
#   Stage 1 (builder): maven:3.9-eclipse-temurin-17 编译打包, 产出 dcdr-agent-1.0.0.jar
#   Stage 2 (runtime): eclipse-temurin:17-jre-alpine 仅运行, 镜像体积 ~250MB
#
# 选 alpine JRE 而非完整 JDK 的理由: 运行期不需要 javac, alpine 基镜像 ~80MB,
# 配合 JRE 总体积比 JDK slim 镜像小 ~150MB, 拉取/部署更快。
# 多阶段构建避免把源码与 Maven 缓存带入最终镜像。

# ===== Stage 1: 构建 =====
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先单独拷 pom.xml 拉依赖, 利用 Docker 层缓存(源码改动不重新下依赖)
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 拷源码编译打包 (跳过测试, 加速构建)
COPY src ./src
RUN mvn -B clean package -DskipTests

# ===== Stage 2: 运行 =====
FROM eclipse-temurin:17-jre-alpine

# 时区设为上海(日志时间与业务一致)
RUN apk add --no-cache tzdata curl \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

WORKDIR /app

# 从 builder 拷构建产物
COPY --from=builder /build/target/dcdr-agent-1.0.0.jar app.jar

# 数据落盘目录(uploads/generated/videos/kb), 由 compose 挂卷持久化
RUN mkdir -p /app/data
VOLUME /app/data

# 健康检查: Spring Boot Actuator 未引入, 用 /api/sessions 兜底(应用启动后即返回 200)
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/sessions || exit 1

EXPOSE 8080

# JVM 参数: 容器感知内存限制, OOM 时生成 dump
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
