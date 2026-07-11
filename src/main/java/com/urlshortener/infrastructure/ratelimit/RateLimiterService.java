package com.urlshortener.infrastructure.ratelimit;

import com.urlshortener.common.exception.Exceptions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Distributed rate limiting using Redis INCR + EXPIRE (sliding window counter).
 *
 * <p>Simple, reliable, no external library beyond spring-data-redis. Each key holds a counter.
 * First request sets TTL. Subsequent requests increment. When counter exceeds limit, request is
 * rejected.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

  private final RedisTemplate<String, Object> redisTemplate;

  @Value("${app.rate-limit.anonymous.capacity:10}")
  private long anonymousCapacity;

  @Value("${app.rate-limit.anonymous.refill-duration-seconds:60}")
  private long anonymousRefillSeconds;

  @Value("${app.rate-limit.authenticated.capacity:1000}")
  private long authenticatedCapacity;

  /**
   * Enforces rate limiting based on the caller's IP address.
   *
   * @param ipAddress the client IP address
   * @throws Exceptions.RateLimitExceededException if the IP has exceeded the anonymous limit
   */
  public void checkIpRateLimit(String ipAddress) {
    checkLimit(
        "rate-limit:ip:" + ipAddress,
        anonymousCapacity,
        Duration.ofSeconds(anonymousRefillSeconds),
        "IP");
  }

  /**
   * Enforces rate limiting per authenticated user.
   *
   * @param userId the user's UUID string
   * @throws Exceptions.RateLimitExceededException if the user has exceeded their hourly limit
   */
  public void checkUserRateLimit(String userId) {
    checkLimit("rate-limit:user:" + userId, authenticatedCapacity, Duration.ofHours(1), "user");
  }

  /**
   * Enforces rate limiting per API key.
   *
   * @param apiKeyId the API key's UUID string
   * @param limitPerHour the per-key limit configured on the key entity
   * @throws Exceptions.RateLimitExceededException if the key has exceeded its hourly limit
   */
  public void checkApiKeyRateLimit(String apiKeyId, int limitPerHour) {
    checkLimit("rate-limit:apikey:" + apiKeyId, limitPerHour, Duration.ofHours(1), "apiKey");
  }

  private void checkLimit(String key, long limit, Duration window, String type) {
    try {
      Long count = redisTemplate.opsForValue().increment(key);
      if (count == null) {
        return; // Redis unavailable - fail open (don't block request)
      }
      if (count == 1L) {
        // First request in this window - set expiry
        redisTemplate.expire(key, window.getSeconds(), TimeUnit.SECONDS);
      }
      if (count > limit) {
        log.warn("Rate limit exceeded type={} key={} count={} limit={}", type, key, count, limit);
        throw new Exceptions.RateLimitExceededException(type);
      }
    } catch (Exceptions.RateLimitExceededException e) {
      throw e;
    } catch (Exception e) {
      // Redis error - fail open (don't block requests when Redis is unavailable)
      log.warn("Rate limit check failed for {}: {} - failing open", key, e.getMessage());
    }
  }
}
