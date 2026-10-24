# Образ сервера TSDB. Собирать из корня репозитория:
#   docker build -f docker/Dockerfile -t tsdb-server .

# Этап 1: JDK + Gradle собирают tsdb-server/build/install/tsdb-server (bin/ + lib/)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# при сборке с Windows бит исполняемости у gradlew может не доехать — не полагаемся на него;
# кэш ~/.gradle переживает пересборки образа, зависимости не качаются каждый раз заново
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew :tsdb-server:installDist --no-daemon

# Этап 2: только JRE и результат сборки, без исходников и Gradle
FROM eclipse-temurin:21-jre

# архитектуру (amd64/arm64) подставляет docker buildx
ARG TARGETARCH
ADD --chmod=755 https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.57/grpc_health_probe-linux-${TARGETARCH} /bin/grpc_health_probe

# сервер работает не от root; /data создаём заранее, чтобы том получил того же владельца
RUN groupadd --system tsdb \
    && useradd --system --gid tsdb tsdb \
    && mkdir /data \
    && chown tsdb:tsdb /data

WORKDIR /app
COPY --from=build /src/tsdb-server/build/install/tsdb-server /app

ENV TSDB_PORT=2222 \
    TSDB_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 2222

USER tsdb
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD grpc_health_probe -addr=localhost:${TSDB_PORT} || exit 1

# exec-форма: скрипт installDist делает exec java, и SIGTERM от docker stop доходит до JVM
ENTRYPOINT ["/app/bin/tsdb-server"]
