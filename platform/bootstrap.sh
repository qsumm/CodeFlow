#!/usr/bin/env bash
# 一键拉起平台全栈:依赖(MySQL/Redis/MinIO)→ 应用 → MinIO 桶 → 健康检查。
# 幂等:依赖容器/卷已存在则跳过;应用容器以 --env-file 生成的密钥启动。
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$(dirname "$0")"

ENV_FILE="runtime.env"
if [ ! -f "$ENV_FILE" ]; then
  echo "==> 生成平台密钥与配置($ENV_FILE,chmod 600,勿入库)"
  MK=$(openssl rand -base64 32 | tr -d '\n')
  JS=$(openssl rand -base64 48 | tr -d '\n')
  cat > "$ENV_FILE" <<EOF
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/ainative?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
SPRING_DATASOURCE_USERNAME=ainative
SPRING_DATASOURCE_PASSWORD=change-me
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=
AINATIVE_SECRET_MASTER_KEY=$MK
AINATIVE_JWT_SECRET=$JS
AINATIVE_PUBLIC_BASE_URL=http://localhost:7001
AINATIVE_VERSION=1.0.0
OSS_ENABLED=false
S3_ENABLED=true
S3_ENDPOINT=http://minio:9000
S3_PUBLIC_ENDPOINT=http://localhost:9000
S3_REGION=us-east-1
S3_ACCESS_KEY_ID=minioadmin
S3_ACCESS_KEY_SECRET=minioadmin
S3_FORCE_PATH_STYLE=true
OSS_BUCKET=ainative
AINATIVE_SLS_ENABLED=false
AINATIVE_SIGAR_ENABLED=false
AINATIVE_COMMUNITY_EDITION=true
AINATIVE_SCHEDULED_TASK_ENABLED=true
EOF
  chmod 600 "$ENV_FILE"
fi

echo "==> [1/4] 依赖服务(compose,首次自动导入 schema+模板种子)"
docker compose up -d --wait

echo "==> [2/4] MinIO 桶"
BUCKET_SCRIPT="$REPO_ROOT/scripts/create_bucket.py"
if [ -f "$BUCKET_SCRIPT" ]; then
  S3_ENDPOINT="${S3_ENDPOINT:-http://localhost:9000}" python3 "$BUCKET_SCRIPT" ainative || \
    echo "(MinIO 桶创建未确认,如已存在可忽略)"
else
  echo "(未找到 scripts/create_bucket.py,跳过建桶)"
fi

echo "==> [3/4] 应用容器"
docker rm -f ainative-community 2>/dev/null || true
IMAGE="${BOOTSTRAP_IMAGE:-ainative-community:local}"
# 低配主机(≤2G)把堆调小,如 BOOTSTRAP_JAVA_OPTS="-Xmx700m -Xms256m"
docker run -d --name ainative-community \
  --network ainative-community \
  --env-file "$ENV_FILE" \
  -e JAVA_OPTS="${BOOTSTRAP_JAVA_OPTS:--Xmx2g -Xms512m}" \
  -p 7001:7001 \
  "$IMAGE"

echo "==> [4/4] 健康检查"
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:7001/checkpreload.htm || true)
  [ "$code" = "200" ] && break
  sleep 3
done
[ "$code" = "200" ] || { echo "健康检查失败,查看: docker logs ainative-community"; exit 1; }
echo "平台就绪: http://localhost:7001/register (注册首个用户并设为平台管理员)"
