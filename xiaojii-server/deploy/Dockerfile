# 校集后端生产镜像
# 构建方式（在 xiaojii-server 目录执行）：
#   ./mvnw -DskipTests package
#   docker build -f deploy/Dockerfile -t xiaojii-server:1.0.0 .
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S app && adduser -S app -G app

# 构建上下文是 xiaojii-server 根目录，所以这里从 target 拷贝
COPY target/xiaojii-server-1.0.0.jar app.jar

RUN mkdir -p /app/uploads /app/logs && chown -R app:app /app

USER app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC"

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Duser.timezone=Asia/Shanghai -jar /app/app.jar"]
