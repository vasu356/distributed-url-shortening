package com.urlshortener.api.v1.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Audit log entry response. */
public record AuditLogResponse(
    UUID id,
    UUID actorId,
    String actorType,
    String action,
    String entityType,
    UUID entityId,
    String oldValue,
    String newValue,
    Instant createdAt) {
  public AuditLogResponse {}
}
