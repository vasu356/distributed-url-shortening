package com.urlshortener.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlCacheService")
class UrlCacheServiceTest {

  @Mock private RedisTemplate<String, CachedUrl> cachedUrlRedisTemplate;
  @Mock private ValueOperations<String, CachedUrl> valueOps;

  private UrlCacheService cacheService;

  @BeforeEach
  void setUp() {
    // Constructor does not call opsForValue() — stub only in tests that exercise put/get.
    cacheService = new UrlCacheService(cachedUrlRedisTemplate);
  }

  @Test
  @DisplayName("put stores CachedUrl under url:{code} with 1-hour TTL")
  void put_storesWithCorrectKeyAndTtl() {
    when(cachedUrlRedisTemplate.opsForValue()).thenReturn(valueOps);
    var cached = new CachedUrl("https://example.com", true, null, null, 0L, 302);
    cacheService.put("abc1234", cached);
    verify(valueOps).set(eq("url:abc1234"), eq(cached), eq(Duration.ofHours(1)));
  }

  @Test
  @DisplayName("get returns present Optional when key exists")
  void get_returnsValue_whenExists() {
    when(cachedUrlRedisTemplate.opsForValue()).thenReturn(valueOps);
    var cached = new CachedUrl("https://example.com", true, null, null, 0L, 302);
    when(valueOps.get("url:abc1234")).thenReturn(cached);

    Optional<CachedUrl> result = cacheService.get("abc1234");

    assertThat(result).isPresent();
    assertThat(result.get().longUrl()).isEqualTo("https://example.com");
  }

  @Test
  @DisplayName("get returns empty Optional when key absent")
  void get_returnsEmpty_whenAbsent() {
    when(cachedUrlRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("url:absent")).thenReturn(null);
    assertThat(cacheService.get("absent")).isEmpty();
  }

  @Test
  @DisplayName("evict deletes the key")
  void evict_deletesKey() {
    cacheService.evict("abc1234");
    verify(cachedUrlRedisTemplate).delete("url:abc1234");
  }

  @Test
  @DisplayName("CachedUrl.isExpired returns true for past epoch")
  void cachedUrl_isExpired_past() {
    var expired = new CachedUrl("https://example.com", true, 1L, null, 0L, 302);
    assertThat(expired.isExpired()).isTrue();
  }

  @Test
  @DisplayName("CachedUrl.isExpired returns false for future epoch")
  void cachedUrl_isExpired_future() {
    long future = System.currentTimeMillis() / 1000L + 3600L;
    var notExpired = new CachedUrl("https://example.com", true, future, null, 0L, 302);
    assertThat(notExpired.isExpired()).isFalse();
  }

  @Test
  @DisplayName("CachedUrl.isExpired returns false when no expiry")
  void cachedUrl_isExpired_noExpiry() {
    var noExpiry = new CachedUrl("https://example.com", true, null, null, 0L, 302);
    assertThat(noExpiry.isExpired()).isFalse();
  }

  @Test
  @DisplayName("CachedUrl.hasReachedClickCap returns true when count >= max")
  void cachedUrl_clickCap_reached() {
    var capped = new CachedUrl("https://example.com", true, null, 100L, 100L, 302);
    assertThat(capped.hasReachedClickCap()).isTrue();
  }

  @Test
  @DisplayName("CachedUrl.isRedirectable returns false for inactive")
  void cachedUrl_notRedirectable_inactive() {
    var inactive = new CachedUrl("https://example.com", false, null, null, 0L, 302);
    assertThat(inactive.isRedirectable()).isFalse();
  }
}
