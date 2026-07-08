#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$SCRIPT_DIR/.."

# .env.docker 파일 읽어서 환경 변수로 export
ENV_FILE="$DOCKER_DIR/.env.docker"
export $(grep -v '^#' "$ENV_FILE" | xargs)

docker compose \
  --env-file "$ENV_FILE" \
  -f "$DOCKER_DIR/docker-compose.yaml" \
  up -d

echo "PostgreSQL started."

# 컨테이너 상태 확인
docker compose \
  --env-file "$ENV_FILE" \
  -f "$DOCKER_DIR/docker-compose.yaml" \
  ps

# PostgreSQL 준비 확인
echo "Waiting for PostgreSQL to become ready..."
until docker exec postgres pg_isready -U ${POSTGRES_USER} >/dev/null 2>&1; do
  sleep 1
done

echo "PostgreSQL container is ready."
