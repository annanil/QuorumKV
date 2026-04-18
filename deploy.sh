#!/bin/bash
set -euo pipefail

# Usage:
#   ./deploy.sh [write_quorum] [read_quorum]              # leader-follower
#   ./deploy.sh [write_quorum] [read_quorum] --leaderless # leaderless (wires peer URLs)

WRITE_QUORUM="${1:-1}"
READ_QUORUM="${2:-1}"
LEADERLESS="${3:-}"

IMAGE="lin1annaaa/assignment4-kv:latest"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "==> Building Docker image for linux/amd64..."
docker buildx build --platform linux/amd64 -t "$IMAGE" "$PROJECT_ROOT" --load

echo "==> Pushing to Docker Hub..."
docker push "$IMAGE"

cd "$PROJECT_ROOT/terraform"

echo "==> Phase 1: provisioning instances (W=${WRITE_QUORUM}, R=${READ_QUORUM})..."
terraform apply -auto-approve \
  -var="write_quorum_size=${WRITE_QUORUM}" \
  -var="read_quorum_size=${READ_QUORUM}"

if [ "$LEADERLESS" = "--leaderless" ]; then
  echo "==> Leaderless mode: static private IPs — single-phase deploy..."
  terraform apply -auto-approve \
    -var="write_quorum_size=${WRITE_QUORUM}" \
    -var="read_quorum_size=${READ_QUORUM}" \
    -var="leaderless=true" \
    -replace=aws_instance.leader \
    -replace='aws_instance.followers[0]' \
    -replace='aws_instance.followers[1]' \
    -replace='aws_instance.followers[2]' \
    -replace='aws_instance.followers[3]'

  echo "==> Done. Waiting ~2 minutes for instances to boot..."
  sleep 120
  echo "==> ALB DNS: $(terraform output -raw leaderless_alb_dns_name)"
else
  echo "==> Leader-follower mode: deploying cluster..."
  terraform apply -auto-approve \
    -var="write_quorum_size=${WRITE_QUORUM}" \
    -var="read_quorum_size=${READ_QUORUM}" \
    -replace=aws_instance.leader \
    -replace='aws_instance.followers[0]' \
    -replace='aws_instance.followers[1]' \
    -replace='aws_instance.followers[2]' \
    -replace='aws_instance.followers[3]'

  echo "==> Done. Waiting ~2 minutes for instances to boot..."
  sleep 120
  echo "==> Leader public IP: $(terraform output -raw leader_public_ip)"
fi
