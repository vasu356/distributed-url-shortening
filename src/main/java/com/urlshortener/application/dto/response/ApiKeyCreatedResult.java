package com.urlshortener.application.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response returned once on API key creation — contains the raw key (never retrievable again). */
public record ApiKeyCreatedResult(
    UUID id,
    String name,
    String rawKey,
    List<String> scopes,
    int rateLimit,
    Instant expiresAt,
    Instant createdAt) {
  public ApiKeyCreatedResult {}
}
