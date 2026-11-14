# syntax=docker/dockerfile:1
# CodeFlow v2 后端(server-v2,原创重写)+ 同 jar 内嵌前端 SPA。
# 数据库:MySQL 8(或兼容协议的 OceanBase seekdb),schema 见 docs/seekdb-schema-v1-*.sql

# ── 阶段 1:前端构建(vite,产物进后端 resources 一起打包)──
FROM node:20-bookworm AS frontend
WORKDIR /fe
COPY frontend ./
RUN npm ci --no-audit --no-fund \
 && npm run build \
 && rm -rf dist/browser-tests dist/node_modules

# ── 阶段 2:后端构建(Maven,server-v2)──
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY server-v2/pom.xml ./pom.xml
COPY server-v2/src ./src
COPY --from=frontend /fe/dist ./src/main/resources/frontend
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# ── 阶段 3:运行 ──
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system ainative && \
    useradd --system --gid ainative --home /app ainative && \
    install -d -o ainative -g ainative /app/artifacts /app/logs

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar

ENV TZ=Asia/Shanghai \
    SERVER_PORT=7001 \
    DB_HOST=seekdb \
    DB_PORT=2881 \
    DB_NAME=ainative_v2 \
    DB_USER=ainative \
    DB_PASSWORD=change-me \
    AINATIVE_JWT_SECRET=please-change-this-secret-at-least-32-chars \
    AINATIVE_PUBLIC_BASE_URL_V2=http://localhost:7001 \
    AINATIVE_DOWNLOAD_SECRET=please-change-download-secret

USER ainative
EXPOSE 7001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
