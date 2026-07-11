# ADR-002: Kafka for Event-Driven Analytics

**Status:** Accepted  
**Date:** 2025-01-15

## Problem

Click tracking at 50,000 redirects/sec cannot write synchronously to PostgreSQL — each redirect would stall waiting for a DB write. We need an async buffer between the redirect path and persistent storage.

## Decision

**Apache Kafka** as the event bus for click events, audit events, and URL lifecycle events.

## Alternatives Rejected

**RabbitMQ:** Messages are deleted after consumption — no replay capability. Analytics recalculation (e.g., fixing a bug in geo-IP parsing) requires re-processing historical events, which is impossible if events are gone. Kafka's persistent log enables replay.

**PostgreSQL LISTEN/NOTIFY or outbox pattern:** Suitable for low-volume events but not 50k/sec. Would add write load to the primary DB.

**AWS SQS/SNS:** Vendor lock-in. Higher operational complexity for local development. No replay.

## Trade-offs

- Operational complexity of Kafka (ZooKeeper, replication config) is justified at this scale
- Local dev uses single-broker Kafka via Docker Compose — no ZooKeeper complexity exposed to developers
- Kafka's consumer group model allows scaling analytics workers independently of the API tier

---

# ADR-003: PostgreSQL as Primary Datastore

**Status:** Accepted  
**Date:** 2025-01-15

## Problem

Choose a primary datastore for URL metadata, user accounts, and analytics aggregates.

## Decision

**PostgreSQL 16** with declarative table partitioning for `click_events`.

## Alternatives Rejected

**MongoDB:** Document model doesn't naturally represent relational data (User → ShortUrl → ClickEvent). Multi-document ACID transactions are complex. No native support for GIN full-text search as a first-class feature. Joins are expensive. Rejected for a domain that is fundamentally relational.

**MySQL/MariaDB:** Lacks native JSONB support (needed for audit log `old_value`/`new_value`). Declarative partitioning is less mature. PostgreSQL's query planner and extension ecosystem are superior.

**CockroachDB:** Distributed SQL would give horizontal write scalability, but adds operational overhead, higher latency for short transactions, and is unnecessary at our current scale. Revisit if single-region PostgreSQL becomes a bottleneck.

## Trade-offs

- Vertical scaling ceiling exists — mitigated by: read replicas, connection pooling, aggressive caching (Redis)
- Partitioning strategy for `click_events` (monthly range) keeps query performance consistent as data grows
- At 10 TB+: consider TimescaleDB extension for click_events, or move to a dedicated analytics store (Redshift, BigQuery)

---

# ADR-004: Virtual Threads (Java 21) on the Redirect Path

**Status:** Accepted  
**Date:** 2025-01-15

## Problem

The redirect path is I/O-bound: Redis lookup (~0.5ms) + optional DB lookup (~5ms) + Kafka publish (async). With platform threads (default), each blocked I/O call parks a thread, limiting throughput to `thread_pool_size / average_latency`.

## Decision

**Enable Virtual Threads** via `spring.threads.virtual.enabled=true` (Spring Boot 3.2+).

With virtual threads, thread-blocking I/O (Redis, PostgreSQL JDBC, Kafka) is handled by JVM carrier thread parking — the virtual thread is suspended without consuming a platform thread, allowing millions of concurrent virtual threads.

## Alternatives Rejected

**Spring WebFlux (reactive):** Reactive programming achieves the same I/O throughput but requires a fully non-blocking stack (R2DBC instead of JDBC, reactive Kafka, reactive Redis). Migrating JPA/JDBC to reactive adds significant complexity and loses many familiar Spring Data abstractions. Virtual threads achieve equivalent throughput with the familiar blocking programming model.

**Larger thread pool:** Increasing the Tomcat thread pool to 1000+ would increase throughput at the cost of memory (~1MB per platform thread × 1000 = 1GB). Virtual threads use ~few KB per thread and scale to millions.

## Trade-offs

- Virtual threads must not use `synchronized` blocks in hot paths (causes carrier thread pinning). Audited all third-party libraries on the redirect path — Lettuce uses `java.util.concurrent.locks.ReentrantLock` (safe); Hibernate uses synchronized in some paths (not on our redirect path).
- Performance improvement is significant for I/O-bound workloads, negligible for CPU-bound workloads
- Requires Java 21+ — locks in the minimum JVM version

---

# ADR-005: Redis Cache Architecture

**Status:** Accepted  
**Date:** 2025-01-15

## Problem

The redirect path must serve 50,000 req/sec with p99 < 10ms on cache hits. PostgreSQL cannot serve 50k queries/sec without a cache layer.

## Decision

**Two-level cache:**
- **L1 (Redis, distributed):** URL objects cached for 1 hour. Shared across all replicas — consistent view.
- **L2 (Caffeine, in-process, optional):** For extreme hot URLs (top 0.1%), a 100ms in-process cache eliminates even the Redis RTT. Not yet implemented; added when Redis RTT becomes a bottleneck.

**Cache-aside pattern:** Application reads cache first; on miss, loads from DB and populates cache. On URL update or delete, cache entry is invalidated (DEL), not updated. This avoids stale-write races at the cost of one cache miss after every write.

## Alternatives Rejected

**Write-through:** Updates DB and cache atomically. Problem: requires distributed transaction between Redis and PostgreSQL, or accept a window where cache and DB are inconsistent on crash. Cache-aside with TTL is simpler and sufficient.

**TTL-only invalidation (no explicit DEL):** Simple but means deleted/deactivated URLs remain redirectable for up to 1 hour. Unacceptable for security (user deletes a URL expecting immediate effect).

## Trade-offs

- Cache invalidation on write adds one extra Redis `DEL` per mutation — negligible cost
- Cache cold-start (new replica or Redis restart): first redirect for each URL is a DB miss — acceptable
- Cache stampede (many concurrent misses for the same key after expiry): mitigated by Bloom filter confirming URL existence before DB query
