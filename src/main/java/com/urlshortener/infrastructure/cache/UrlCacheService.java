package com.urlshortener.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis cache for the URL redirect hot path.
 *
 * <p>Cache-aside pattern: - get() → check Redis; return value or empty - put() → store after DB
 * load or URL creation - evict() → delete on URL update/delete/expiry
 *
 * <p>Direct RedisTemplate (not @Cacheable) — avoids proxy overhead on the 50 k req/s redirect path
 * and gives explicit control over the key format.
 */
@Service
@Slf4j
public class UrlCacheService {

  private static final String PREFIX = "url:";
  private static final Duration TTL = Duration.ofHours(1);

  private final RedisTemplate<String, CachedUrl> redisTemplate;

  /** Constructs the service using the typed {@code cachedUrlRedisTemplate} bean. */
  public UrlCacheService(
      @Qualifier("cachedUrlRedisTemplate") RedisTemplate<String, CachedUrl> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /**
   * Stores a URL in the cache with a 1-hour TTL.
   *
   * @param shortCode the short code key
   * @param cachedUrl the URL data to store
   */
  public void put(String shortCode, CachedUrl cachedUrl) {
    redisTemplate.opsForValue().set(PREFIX + shortCode, cachedUrl, TTL);
    log.debug("Cached URL shortCode={}", shortCode);
  }

  /**
   * Retrieves a cached URL by short code.
   *
   * @param shortCode the short code key
   * @return an Optional containing the cached URL, or empty if not cached
   */
  public Optional<CachedUrl> get(String shortCode) {
    CachedUrl cached = redisTemplate.opsForValue().get(PREFIX + shortCode);
    return Optional.ofNullable(cached);
  }

  /**
   * Removes a URL from the cache.
   *
   * @param shortCode the short code key to evict
   */
  public void evict(String shortCode) {
    redisTemplate.delete(PREFIX + shortCode);
    log.debug("Evicted URL shortCode={}", shortCode);
  }

  /**
   * Returns true if the short code has a cache entry.
   *
   * @param shortCode the short code key
   * @return true if cached, false otherwise
   */
  public boolean exists(String shortCode) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + shortCode));
  }
}
