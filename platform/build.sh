#!/usr/bin/env bash
# 构建平台应用镜像(源码:platform/ainative,基线见其 UPSTREAM.md)。
#
# 两种模式:
#   默认(推荐):使用随库分发的 frontend/dist 预构建产物,零 npm 依赖,约 3 分钟
#   FULL_BUILD=1:从源码经 npm 重新构建前端(需要容器能访问 npmmirror;约 15–25 分钟)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${1:-ainative-community:local}"
APP_DIR="ainative"
SETTINGS="$APP_DIR/../settings.xml"

cat > "$SETTINGS" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>central</id>
      <mirrorOf>central</mirrorOf>
      <url>https://repo.maven.apache.org/maven2</url>
    </mirror>
  </mirrors>
</settings>
XML

# -Dmaven.test.skip=true:连测试类的编译都跳过(674 个测试源文件在 1.8G 小内存机上
# 会把同机 MySQL 挤到 OOM——阿里云 ECS 实测坑 #2)
MVN_ARGS=(-B -ntp -DskipGitCommitId=true -DskipTests -Dmaven.test.skip=true)
if [ "${FULL_BUILD:-0}" = "1" ]; then
  echo "==> 模式:FULL_BUILD(源码构建前端)"
  docker run --rm \
    -v "$PWD/$APP_DIR":/workspace -w /workspace \
    -v ainative-m2-cache:/root/.m2 \
    -v "$PWD/$SETTINGS":/root/.m2/settings.xml:ro \
    -e MAVEN_OPTS=-Xmx900m \
    -e npm_config_registry=https://registry.npmmirror.com \
    -e npm_config_cache=/workspace/frontend/.npm-cache \
    -v ainative-npm-cache:/workspace/frontend/.npm-cache \
    -e NODE_OPTIONS=--max-old-space-size=1536 \
    maven:3.9.9-eclipse-temurin-21 \
    mvn "${MVN_ARGS[@]}" -DnpmDownloadRoot=https://registry.npmmirror.com/npm/-/ package
else
  echo "==> 模式:REUSE_DIST(随库 dist,零 npm 依赖)"
  if [ ! -f "$APP_DIR/frontend/dist/index.html" ]; then
    echo "缺少 frontend/dist(见 platform/ainative/UPSTREAM.md);或 FULL_BUILD=1 从源码构建"
    exit 1
  fi
  # skipFrontend 会同时跳过上游的 dist 拷贝步骤,故两段式:先编译,再手工放入 static,后打包
  docker run --rm \
    -v "$PWD/$APP_DIR":/workspace -w /workspace \
    -v ainative-m2-cache:/root/.m2 \
    -v "$PWD/$SETTINGS":/root/.m2/settings.xml:ro \
    maven:3.9.9-eclipse-temurin-21 \
    mvn "${MVN_ARGS[@]}" -DskipFrontend=true compile
  docker run --rm \
    -v "$PWD/$APP_DIR":/workspace -w /workspace \
    -v ainative-m2-cache:/root/.m2 \
    -v "$PWD/$SETTINGS":/root/.m2/settings.xml:ro \
    maven:3.9.9-eclipse-temurin-21 \
    bash -c "mkdir -p target/classes/static && cp -R frontend/dist/. target/classes/static/ && \
             mvn -B -ntp -DskipGitCommitId=true -DskipFrontend=true -DskipTests package"
fi

echo "==> 打运行镜像 $IMAGE"
docker build -f "$APP_DIR/../Dockerfile.platform" -t "$IMAGE" "$APP_DIR/.."
echo "完成:$IMAGE"
