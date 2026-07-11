# ADR-001: Short Code Generation Strategy

**Status:** Accepted  
**Date:** 2025-01-15  
**Authors:** Platform Team

---

## Problem

We need to generate short codes (7 characters) that are:
- Globally unique across all URLs in the system
- Unpredictable (cannot be guessed or enumerated)
- Fast to generate under high write concurrency (500 URLs/sec)
- Collision-safe under distributed deployment (multiple app replicas)

---

## Options Considered

### Option 1: Auto-increment ID → Base62
Each URL gets a sequential integer ID from PostgreSQL, encoded as Base62.

**Pros:** Guaranteed unique, no collision checks needed, deterministic.  
**Cons:** Reveals system volume (code length grows with usage), sequential codes are guessable, single point of sequence (DB sequence lock under high concurrency), codes like `1`, `2`, `3` are too short.

**Decision:** Rejected.

---

### Option 2: UUID → Base62 truncated
Generate a UUID and take the first 7 characters of its Base62 encoding.

**Pros:** No coordination required, truly random.  
**Cons:** Truncation dramatically increases collision probability. At 1 billion URLs, probability of collision on next insert ≈ 13% per attempt. Repeated regeneration degrades latency under load.

**Decision:** Rejected.

---

### Option 3: Random 7-char Base62 with Bloom filter + DB constraint (chosen)
Generate a cryptographically random 7-character Base62 string. Use a Guava Bloom filter (in-memory, per-replica) to pre-check for likely collisions before hitting the DB. Fall back to a DB `SELECT EXISTS` on Bloom false positives. Use the DB's `UNIQUE` constraint as the final safety net.

**Pros:**
- `SecureRandom` makes codes unpredictable — cannot be enumerated
- 62⁷ ≈ 3.5 trillion unique codes — sufficient for decades at 500 URLs/sec
- Bloom filter eliminates ~99.9% of DB lookups for collision checks
- Stateless generation — no coordination between replicas
- DB `UNIQUE` constraint is the authoritative safety net

**Cons:**
- Bloom filter is in-process, not shared across replicas — a code confirmed absent on one replica could already be inserted by another. Mitigated by: the DB constraint catches this as a `DataIntegrityViolationException`, caught and retried at the use-case layer.
- Bloom filter consumes ~14MB JVM heap (10M entries at 0.1% FPP). Acceptable.

**Decision:** Accepted.

---

### Option 4: Snowflake ID → Base62
Distributed time-based ID (timestamp + datacenter ID + sequence) encoded as Base62.

**Pros:** Ordered, globally unique without coordination, embed creation time.  
**Cons:** Exposes creation timestamp in the short code, requires datacenter/worker ID assignment (coordination), codes are longer (up to 13 chars for 64-bit Snowflake), more complex to implement.

**Decision:** Rejected for now. Reconsidered if collision rate becomes a problem at >10B URLs.

---

### Option 5: Pre-generated pool
Background job pre-generates thousands of unique codes and stores them in a Redis queue. Writers pop a code from the queue.

**Pros:** Zero latency at write time.  
**Cons:** Complex to operate (queue depth monitoring, refill logic, handling pool exhaustion), Redis becomes a write bottleneck if queue is small, harder to guarantee codes are never reused after soft-delete tombstone expiry.

**Decision:** Rejected. Premature optimisation; revisit if write latency SLO is not met.

---

## Decision

**Option 3: Random Base62 with Bloom filter + DB UNIQUE constraint.**

---

## Trade-offs

| Property | Value |
|---|---|
| Collision probability per insert at 1B URLs | ~0.0003% (negligible) |
| Bloom filter memory | ~14MB |
| DB lookup on cache miss | ~1ms |
| Throughput ceiling | Limited by DB write speed, not code generation |
| Security | Codes are cryptographically unpredictable |

---

## Future

- At 10B+ active URLs: consider Redis-backed Bloom filter (RedisBloom module) for cross-replica consistency
- At 100k+ writes/sec: consider pre-generated pool with monitoring
- If code reuse after tombstone expiry is required: add `tombstoned_codes` table and check it in Bloom filter seed on startup
