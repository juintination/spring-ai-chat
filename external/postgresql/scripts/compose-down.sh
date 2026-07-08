#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$SCRIPT_DIR/.."

echo "Stopping PostgreSQL container..."

docker compose \
  --env-file "$DOCKER_DIR/.env.docker" \
  -f "$DOCKER_DIR/docker-compose.yaml" \
  down

echo "PostgreSQL stopped."

# 컨테이너 상태 확인
RUNNING=$(docker compose \
  --env-file "$DOCKER_DIR/.env.docker" \
  -f "$DOCKER_DIR/docker-compose.yaml" \
  ps -q)

if [ -z "$RUNNING" ]; then
  echo "PostgreSQL container successfully stopped and removed."
else
  echo "Some PostgreSQL containers are still running:"
  docker compose \
    --env-file "$DOCKER_DIR/.env.docker" \
    -f "$DOCKER_DIR/docker-compose.yaml" \
    ps
  exit 1
fi
