package com.urlshortener.application.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Audit log entry result. */
public record AuditLogResult(
    UUID id,
    UUID actorId,
    String actorType,
    String action,
    String entityType,
    UUID entityId,
    String oldValue,
    String newValue,
    Instant createdAt) {
  public AuditLogResult {}
}
