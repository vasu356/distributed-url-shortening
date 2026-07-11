package com.urlshortener.infrastructure.security;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * JWT revocation via Redis blacklist.
 *
 * <p>On logout or token invalidation, the token's jti is stored in Redis with TTL = remaining token
 * lifetime. This means: - Storage cost is bounded: only live tokens that were explicitly revoked
 * are stored - After expiry, tokens are naturally invalid regardless of the blacklist - Check cost:
 * single Redis GET on every authenticated request (~0.1ms at localhost, ~1ms in prod)
 *
 * <p>Alternative considered: database-backed revocation list. Rejected: higher latency (DB
 * roundtrip vs Redis), no TTL support requiring separate cleanup jobs, unnecessary load on primary
 * DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistService {

  private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

  private final RedisTemplate<String, Object> redisTemplate;

  public void blacklist(String jti, Duration ttl) {
    String key = BLACKLIST_PREFIX + jti;
    redisTemplate.opsForValue().set(key, "1", ttl);
    log.debug("Blacklisted token jti={} with TTL={}", jti, ttl);
  }

  public boolean isBlacklisted(String jti) {
    String key = BLACKLIST_PREFIX + jti;
    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
  }
}
