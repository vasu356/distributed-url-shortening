package com.urlshortener.application.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Full details of a shortened URL, returned after creation and on lookup. */
public record UrlResult(
    UUID id,
    String shortCode,
    String shortUrl,
    String longUrl,
    String title,
    String faviconUrl,
    boolean active,
    boolean custom,
    Instant expiresAt,
    long clickCount,
    Long maxClicks,
    int redirectType,
    Instant createdAt,
    Instant updatedAt) {
  public UrlResult {}
}
