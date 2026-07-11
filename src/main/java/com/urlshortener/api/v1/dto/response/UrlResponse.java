package com.urlshortener.api.v1.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Full details of a shortened URL, returned after creation and on lookup. */
public record UrlResponse(
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
  public UrlResponse {}
}
