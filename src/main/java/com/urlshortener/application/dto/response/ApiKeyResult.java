package com.urlshortener.application.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Safe API key representation — raw key is masked; only prefix shown. */
public record ApiKeyResult(
    UUID id,
    String name,
    String keyPrefix,
    List<String> scopes,
    int rateLimit,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant createdAt) {
  public ApiKeyResult {}
}
