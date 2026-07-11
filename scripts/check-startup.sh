#!/bin/bash
echo "=== Pre-startup checklist ==="

echo "1. Checking Docker daemon..."
docker info > /dev/null 2>&1 && echo "   OK: Docker running" || { echo "   FAIL: Run: sudo service docker start"; exit 1; }

echo "2. Checking containers..."
for svc in url-shortener-postgres url-shortener-redis url-shortener-zookeeper url-shortener-kafka; do
  status=$(docker inspect --format='{{.State.Status}}' "$svc" 2>/dev/null)
  if [ "$status" = "running" ]; then
    echo "   OK: $svc running"
  else
    echo "   FAIL: $svc not running (status: $status)"
    echo "   Run: docker compose up -d postgres redis zookeeper kafka"
    exit 1
  fi
done

echo "3. Checking PostgreSQL..."
docker exec url-shortener-postgres pg_isready -U urlshortener > /dev/null 2>&1 && \
  echo "   OK: PostgreSQL accepting connections" || echo "   WARN: PostgreSQL not ready yet"

echo "4. Checking Redis..."
docker exec url-shortener-redis redis-cli ping > /dev/null 2>&1 && \
  echo "   OK: Redis responding" || echo "   WARN: Redis not ready yet"

echo ""
echo "=== All checks passed. Run: ./mvnw spring-boot:run -Dspring-boot.run.profiles=local ==="
