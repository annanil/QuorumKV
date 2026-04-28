#!/bin/bash
set -euo pipefail

# Usage:
#   ./deploy.sh [write_quorum] [read_quorum]              # leader-follower
#   ./deploy.sh [write_quorum] [read_quorum] --leaderless # leaderless

WRITE_QUORUM="${1:-3}"
READ_QUORUM="${2:-3}"
LEADERLESS="${3:-}"

IMAGE="lin1annaaa/quorum-kv:latest"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "==> Building Docker image for linux/amd64..."
docker buildx build --platform linux/amd64 -t "$IMAGE" "$PROJECT_ROOT" --load

echo "==> Pushing to Docker Hub..."
docker push "$IMAGE"

cd "$PROJECT_ROOT/terraform"

if [ "$LEADERLESS" = "--leaderless" ]; then
  echo "==> Deploying leaderless cluster (W=${WRITE_QUORUM}, R=${READ_QUORUM})..."
  terraform apply -auto-approve \
    -var="write_quorum_size=${WRITE_QUORUM}" \
    -var="read_quorum_size=${READ_QUORUM}" \
    -var="leaderless=true"

  echo "==> Done. Waiting ~2 minutes for instances to boot..."
  sleep 120
  echo "==> ALB DNS: $(terraform output -raw leaderless_alb_dns_name)"
else
  echo "==> Deploying leader-follower cluster (W=${WRITE_QUORUM}, R=${READ_QUORUM})..."
  terraform apply -auto-approve \
    -var="write_quorum_size=${WRITE_QUORUM}" \
    -var="read_quorum_size=${READ_QUORUM}"

  echo "==> Done. Waiting ~2 minutes for instances to boot..."
  sleep 120
  echo "==> Leader public IP: $(terraform output -raw leader_public_ip)"
fi
