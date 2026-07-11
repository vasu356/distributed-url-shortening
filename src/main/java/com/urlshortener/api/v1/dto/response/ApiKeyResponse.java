package com.urlshortener.api.v1.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Safe API key representation — raw key is masked; only prefix shown. */
public record ApiKeyResponse(
    UUID id,
    String name,
    String keyPrefix,
    List<String> scopes,
    int rateLimit,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant createdAt) {
  public ApiKeyResponse {}
}
