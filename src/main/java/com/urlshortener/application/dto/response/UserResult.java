package com.urlshortener.application.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Public user details — no password hash or sensitive fields. */
public record UserResult(UUID id, String email, String role, Instant createdAt) {
  public UserResult {}
}
