#!/usr/bin/env bash
# Runs on the VPS as the deploy user, fed over SSH by the pipeline.
# Usage: deploy.sh <image:tag>
set -euo pipefail

IMAGE="$1"
cd /opt/onebase

echo "BACKEND_IMAGE=${IMAGE}" > .env
docker compose pull
docker compose up -d --remove-orphans
docker image prune -f

# Wait for Spring Boot to report healthy; fail the deploy if it never does.
for _ in $(seq 1 30); do
  if curl -fsS http://localhost:8080/actuator/health 2>/dev/null; then
    echo
    echo "Deployed ${IMAGE}"
    exit 0
  fi
  sleep 2
done

echo "Backend did not become healthy. Last logs:"
docker compose logs --tail 100 backend
exit 1
