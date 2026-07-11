package com.urlshortener.api.v1.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Public user details — no password hash or sensitive fields. */
public record UserResponse(UUID id, String email, String role, Instant createdAt) {
  public UserResponse {}
}
