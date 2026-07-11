# Distributed URL Shortening Platform

A production-grade distributed URL shortening service built with Java 21, Spring Boot 3, PostgreSQL, Redis, and Kafka. Designed to handle 50,000 redirects/second with sub-10ms p99 latency on cache hits.

---

## Architecture Overview

```
Client → NGINX → Spring Boot API ──┬── PostgreSQL (primary + replica)
                                   ├── Redis (cache + rate limit + locks)
                                   └── Kafka ──→ Analytics Worker
                                                → Audit Persister
                                                → QR Generator
                                                → Metadata Scraper
                                   
Prometheus ← /actuator/prometheus
Grafana    ← Prometheus
Jaeger     ← OTLP traces (OpenTelemetry)
```

**Read path (redirect):** NGINX → API → Redis (cache hit ~2ms) → 302  
**Write path (create):** API → PostgreSQL → Redis (populate) → Kafka (async)  
**Analytics path:** Kafka consumer → batch insert → PostgreSQL partitioned table

---

## Technology Decisions

| Technology | Purpose | Why chosen |
|---|---|---|
| Java 21 Virtual Threads | Non-blocking I/O without reactive | Reactive-equivalent throughput, simpler code |
| Spring Boot 3.3 | Framework | Production-grade ecosystem, Virtual Thread support |
| PostgreSQL 16 | Primary store | JSONB, GIN indexes, declarative partitioning, ACID |
| Redis 7 | Cache + rate limit + distributed lock | Sub-millisecond reads, TTL, atomic operations |
| Kafka | Async click tracking + audit events | Replay semantics, 50k events/sec, consumer groups |
| Bucket4j | Distributed rate limiting | Token bucket algorithm backed by Redis |
| Resilience4j | Circuit breaker for metadata scraper | Prevents cascade from external URL failures |
| Flyway | Schema migrations | Version-controlled, validated on startup |
| ZXing | QR code generation | De facto standard Java library |
| Testcontainers | Integration tests | Production-parity test environment |
| ArchUnit | Architecture enforcement | Prevents layer violations at test time |

See `/docs/adr/` for full Architecture Decision Records.

---

## Prerequisites

- Java 21+
- Docker & Docker Compose
- Maven 3.9+ (or use `./mvnw`)

---

## Quick Start (Local)

```bash
# 1. Clone and build
git clone https://github.com/your-org/url-shortener
cd url-shortener

# 2. Configure environment
cp .env.example .env
# Edit .env if you need to change any defaults

# 3. Start all infrastructure
docker compose up -d postgres redis kafka

# 4. Run the application
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 5. API available at:
#    http://localhost:8080/swagger-ui.html  — API docs
#    http://localhost:8080/actuator/health  — health check
```

**Full stack with NGINX, Prometheus, Grafana, Jaeger:**
```bash
docker compose up -d
# App:       http://localhost:80
# Grafana:   http://localhost:3001  (admin/admin)
# Jaeger:    http://localhost:16686
# Prometheus: http://localhost:9090
```

---

## API Reference

### Authentication

```bash
# Register
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Password@123"}'

# Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Password@123"}'
# Response contains accessToken and refreshToken
```

### URL Operations

```bash
TOKEN="Bearer eyJ..."

# Create short URL
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"longUrl":"https://www.example.com"}'
# Returns: {"shortCode":"abc1234","shortUrl":"http://localhost:8080/r/abc1234",...}

# Create with custom alias
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"longUrl":"https://github.com","customAlias":"my-github"}'

# Redirect (no auth required)
curl -L http://localhost:8080/r/abc1234

# List your URLs
curl http://localhost:8080/api/v1/urls \
  -H "Authorization: $TOKEN"

# Search URLs
curl "http://localhost:8080/api/v1/urls?search=github" \
  -H "Authorization: $TOKEN"

# Get analytics
curl "http://localhost:8080/api/v1/analytics/abc1234?days=30" \
  -H "Authorization: $TOKEN"

# Delete (soft)
curl -X DELETE http://localhost:8080/api/v1/urls/abc1234 \
  -H "Authorization: $TOKEN"

# Bulk create
curl -X POST http://localhost:8080/api/v1/urls/bulk \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"urls":[{"longUrl":"https://a.com"},{"longUrl":"https://b.com"}]}'

# CSV import
curl -X POST http://localhost:8080/api/v1/csv/import \
  -H "Authorization: $TOKEN" \
  -F "file=@urls.csv"

# CSV export
curl http://localhost:8080/api/v1/csv/export \
  -H "Authorization: $TOKEN" \
  -o my-urls.csv

# QR code
curl http://localhost:8080/api/v1/urls/abc1234/qr \
  -H "Authorization: $TOKEN" \
  -o qr.png
```

### API Keys

```bash
# Create API key
curl -X POST http://localhost:8080/api/v1/api-keys \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"My App","scopes":["read","write"]}'
# Returns rawKey — shown once only

# Use API key
curl http://localhost:8080/api/v1/urls \
  -H "X-API-Key: sk_..."
```

---

## CSV Import Format

```csv
longUrl,customAlias,expiresAt,redirectType
https://example.com,my-link,,302
https://github.com,,,301
https://docs.example.com,docs,2025-12-31T23:59:59Z,302
```

---

## Configuration

Key environment variables:

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/urlshortener` |
| `DB_USERNAME` | Database username | `urlshortener` |
| `DB_PASSWORD` | Database password | `urlshortener` |
| `REDIS_HOST` | Redis hostname | `localhost` |
| `REDIS_PASSWORD` | Redis password | *(empty)* |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers | `localhost:9092` |
| `JWT_SECRET` | Base64 HMAC-SHA256 key (32+ bytes) | *(change in prod)* |
| `APP_BASE_URL` | Public base URL for short links | `http://localhost:8080` |
| `OTLP_ENDPOINT` | OpenTelemetry collector endpoint | `http://localhost:4318/v1/traces` |
| `ENVIRONMENT` | Environment label for metrics | `local` |

---

## Running Tests

```bash
# Unit tests only (fast, no Docker)
./mvnw test -Dtest="!*IntegrationTest,!*RepositoryTest,!ArchitectureTest"

# All tests including integration (requires Docker)
./mvnw verify

# Architecture rules only
./mvnw test -Dtest=ArchitectureTest

# With coverage report
./mvnw verify && open target/site/jacoco/index.html
```

---

## Load Testing

```bash
# Install k6: https://k6.io/docs/getting-started/installation/

# Run load test against local stack
k6 run --env BASE_URL=http://localhost:8080 scripts/load-test.js

# Run with custom VU count and duration
k6 run --vus 100 --duration 60s scripts/load-test.js
```

---

## Observability

### Metrics (Prometheus + Grafana)

Key metrics exposed at `/actuator/prometheus`:

| Metric | Description | SLO |
|---|---|---|
| `url_redirect_duration_seconds` | Redirect latency histogram | p99 < 50ms |
| `cache_hit_total` / `cache_miss_total` | Redis hit/miss | Hit ratio > 95% |
| `url_created_total` | URL creation rate | — |
| `rate_limit_exceeded_total` | Rate limit violations | < 0.1% of requests |
| `kafka_consumer_batch_failed_total` | Consumer failures | 0 |
| `hikaricp_connections_active` | DB pool usage | < 80% of max |

Grafana dashboard auto-provisioned at `http://localhost:3001`.

### Distributed Tracing (Jaeger)

Every request carries a `traceId` and `spanId` (MDC + OTLP). Traces visible at `http://localhost:16686`.

### Structured Logs

All logs are JSON in non-local profiles. Every log line includes:
- `traceId`, `spanId` (OpenTelemetry)
- `correlationId` (from `X-Correlation-ID` header or generated)
- `userId` (after authentication)
- `shortCode` (on redirect path)

---

## Kubernetes Deployment

```bash
# Apply namespace and base resources
kubectl apply -f k8s/base/namespace.yaml
kubectl apply -f k8s/base/configmap.yaml

# Edit secrets (never commit real values)
# Use External Secrets Operator in production
kubectl apply -f k8s/base/configmap.yaml   # after editing secrets

# Deploy application
kubectl apply -f k8s/base/deployment.yaml
kubectl apply -f k8s/base/service-ingress-hpa.yaml

# Check rollout
kubectl rollout status deployment/url-shortener-api -n url-shortener

# Rollback
kubectl rollout undo deployment/url-shortener-api -n url-shortener
```

---

## Database Schema

Tables:
- `users` — accounts and authentication
- `api_keys` — programmatic access tokens (hashed)
- `api_key_scopes` — scopes per API key (1:N)
- `short_urls` — URL mappings with metadata
- `click_events` — partitioned by month (range partitioning on `created_at`)
- `audit_logs` — append-only immutable audit trail

Migrations in `src/main/resources/db/migration/`:
- `V1__initial_schema.sql` — all tables, indexes, triggers
- `V2__seed_admin.sql` — initial admin user
- `V3__analytics_indexes.sql` — additional composite indexes

---

## Security

- **JWT (HS256):** 15-minute access tokens, 7-day refresh tokens with rotation
- **API Keys:** `sk_` prefix, SHA-256 hashed at rest, raw key shown once
- **RBAC:** `USER` and `ADMIN` roles enforced via Spring Security `@PreAuthorize`
- **Rate limiting:** Bucket4j + Redis — distributed token bucket per IP/user/API key
- **SSRF prevention:** URL validation blocks private IP ranges and `169.254.169.254`
- **SQL injection:** JPA parameterised queries only — no string concatenation in queries
- **CSV injection:** Output sanitisation (dangerous leading chars prefixed with tab)
- **Security headers:** `X-Frame-Options`, `CSP`, `HSTS`, `X-Content-Type-Options`
- **Token blacklisting:** Logout and refresh rotation blacklist JTI in Redis

---

## Project Structure

```
src/main/java/com/urlshortener/
├── api/v1/
│   ├── controller/         # REST controllers (HTTP boundary only)
│   ├── dto/                # Immutable request/response records
│   └── openapi/            # OpenAPI configuration
├── application/usecase/    # Business logic orchestrators
├── domain/
│   ├── model/              # JPA entities (zero Spring dependency)
│   ├── repository/         # Spring Data JPA interfaces
│   └── service/            # Pure domain services
├── infrastructure/
│   ├── cache/              # Redis cache service
│   ├── http/               # External HTTP clients (scraper, QR)
│   ├── kafka/              # Producers and consumers
│   ├── ratelimit/          # Bucket4j rate limiter
│   ├── scheduling/         # Spring @Scheduled jobs
│   └── security/           # JWT, API key, UserDetails
├── config/                 # Spring configuration classes
├── common/
│   ├── exception/          # Domain exceptions + global handler
│   ├── logging/            # Correlation ID MDC filter
│   └── util/               # Base62 encoder, short code generator
└── observability/
    ├── health/             # Custom Actuator health indicators
    └── metrics/            # Micrometer custom metrics
```

---

## Contributing

1. Fork and clone the repository
2. Create a feature branch: `git checkout -b feat/my-feature`
3. Run tests: `./mvnw verify`
4. Ensure `./mvnw spotless:check` and `./mvnw checkstyle:check` pass
5. Submit a pull request

---

## License

MIT
