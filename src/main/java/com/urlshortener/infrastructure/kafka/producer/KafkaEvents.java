package com.urlshortener.infrastructure.kafka.producer;

import java.time.Instant;
import java.util.UUID;

/** Event DTOs published to Kafka. Immutable records — safe to serialize across threads. */
public final class KafkaEvents {

  private KafkaEvents() {}

  /**
   * Published on every redirect. Contains all data needed to reconstruct click analytics. The Kafka
   * consumer batch-inserts these into the click_events table.
   */
  public record ClickEvent(
      UUID eventId,
      UUID shortUrlId,
      String shortCode,
      String ipHash,
      String countryCode,
      String city,
      String userAgent,
      String referrer,
      String deviceType,
      String browser,
      String os,
      Instant occurredAt) {

    public static ClickEvent of(
        UUID shortUrlId,
        String shortCode,
        String ipHash,
        String countryCode,
        String city,
        String userAgent,
        String referrer,
        String deviceType,
        String browser,
        String os) {
      return new ClickEvent(
          UUID.randomUUID(),
          shortUrlId,
          shortCode,
          ipHash,
          countryCode,
          city,
          userAgent,
          referrer,
          deviceType,
          browser,
          os,
          Instant.now());
    }
  }

  /** Published for every significant domain mutation. Consumer writes to audit_logs table. */
  public record AuditEvent(
      UUID eventId,
      UUID actorId,
      String actorType,
      String action,
      String entityType,
      UUID entityId,
      String oldValue,
      String newValue,
      String ipAddress,
      String userAgent,
      Instant occurredAt) {
    public static AuditEvent of(
        UUID actorId,
        String actorType,
        String action,
        String entityType,
        UUID entityId,
        String oldValue,
        String newValue,
        String ipAddress,
        String userAgent) {
      return new AuditEvent(
          UUID.randomUUID(),
          actorId,
          actorType,
          action,
          entityType,
          entityId,
          oldValue,
          newValue,
          ipAddress,
          userAgent,
          Instant.now());
    }
  }

  /** Published when a URL is created, updated, deleted, or expired. */
  public record LifecycleEvent(
      UUID eventId,
      UUID shortUrlId,
      String shortCode,
      String longUrl,
      String eventType, // CREATED | UPDATED | DELETED | EXPIRED
      UUID ownerId,
      Instant occurredAt) {
    public static LifecycleEvent of(
        UUID shortUrlId, String shortCode, String longUrl, String eventType, UUID ownerId) {
      return new LifecycleEvent(
          UUID.randomUUID(), shortUrlId, shortCode, longUrl, eventType, ownerId, Instant.now());
    }
  }
}
