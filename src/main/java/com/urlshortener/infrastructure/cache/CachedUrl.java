package com.urlshortener.infrastructure.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lightweight DTO stored in Redis for the redirect hot path.
 *
 * <p>Only the fields needed for redirect resolution — avoids deserialising the full ShortUrl entity
 * with lazy-loaded relationships on every cache hit.
 *
 * <p>JsonIgnoreProperties(ignoreUnknown = true) provides backward compatibility with older cached
 * JSON that may contain extra fields like 'redirectable', 'expired'.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CachedUrl(
    String longUrl,
    boolean active,
    Long expiresAtEpoch, // null = no expiry; stored as Unix epoch seconds
    Long maxClicks, // null = no cap
    long clickCount,
    int redirectType) {

  public CachedUrl {
    // Compact constructor validations if needed
  }

  public boolean isExpired() {
    return expiresAtEpoch != null && System.currentTimeMillis() / 1000L > expiresAtEpoch;
  }

  public boolean hasReachedClickCap() {
    return maxClicks != null && clickCount >= maxClicks;
  }

  public boolean isRedirectable() {
    return active && !isExpired() && !hasReachedClickCap();
  }
}
