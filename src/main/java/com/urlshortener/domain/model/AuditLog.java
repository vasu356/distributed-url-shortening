package com.urlshortener.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Audit log — append-only, never updated or deleted.
 *
 * <p>Records every significant mutation in the system. Used for: - Security forensics - Compliance
 * - Admin oversight - Debugging production incidents
 *
 * <p>old_value/new_value stored as JSONB for flexible schema — auditing different entity types
 * without multiple tables. Alternative (dedicated audit columns per entity) was rejected for
 * complexity.
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = {
      @Index(name = "idx_audit_actor", columnList = "actor_id,created_at"),
      @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id,created_at"),
      @Index(name = "idx_audit_action", columnList = "action,created_at")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "actor_id")
  private UUID actorId;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", length = 20)
  private ActorType actorType;

  @Column(name = "action", nullable = false, length = 100)
  private String action;

  @Column(name = "entity_type", length = 50)
  private String entityType;

  @Column(name = "entity_id")
  private UUID entityId;

  @Column(name = "old_value", columnDefinition = "jsonb")
  private String oldValue;

  @Column(name = "new_value", columnDefinition = "jsonb")
  private String newValue;

  /** Supports IPv6 (max 45 chars). */
  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "user_agent", columnDefinition = "TEXT")
  private String userAgent;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * Factory method for creating audit log entries.
   *
   * @param actorId UUID of the user or system performing the action
   * @param actorType the type of actor
   * @param action the action constant (see {@link Action})
   * @param entityType the entity type name (e.g., "ShortUrl", "User")
   * @param entityId the entity's UUID
   * @param oldValue the previous value as JSON
   * @param newValue the new value as JSON
   * @param ipAddress the actor's IP address
   * @param userAgent the actor's user agent
   * @return a new AuditLog entry with the current timestamp
   */
  public static AuditLog create(
      UUID actorId,
      ActorType actorType,
      String action,
      String entityType,
      UUID entityId,
      String oldValue,
      String newValue,
      String ipAddress,
      String userAgent) {
    AuditLog log = new AuditLog();
    log.actorId = actorId;
    log.actorType = actorType;
    log.action = action;
    log.entityType = entityType;
    log.entityId = entityId;
    log.oldValue = oldValue;
    log.newValue = newValue;
    log.ipAddress = ipAddress;
    log.userAgent = userAgent;
    log.createdAt = Instant.now();
    return log;
  }

  /** Actor categories for audit classification. */
  public enum ActorType {
    USER,
    ADMIN,
    SYSTEM,
    API_KEY
  }

  /** Audit action constants — prevents string typos across the codebase. */
  public static final class Action {

    /** User self-registered. */
    public static final String USER_REGISTERED = "USER_REGISTERED";

    /** Successful login. */
    public static final String USER_LOGIN = "USER_LOGIN";

    /** Failed login attempt. */
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";

    /** Admin deactivated a user. */
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";

    /** A short URL was created. */
    public static final String URL_CREATED = "URL_CREATED";

    /** A short URL was updated. */
    public static final String URL_UPDATED = "URL_UPDATED";

    /** A short URL was soft-deleted. */
    public static final String URL_DELETED = "URL_DELETED";

    /** A soft-deleted URL was restored. */
    public static final String URL_RESTORED = "URL_RESTORED";

    /** A short URL was expired by the scheduler. */
    public static final String URL_EXPIRED = "URL_EXPIRED";

    /** An API key was created. */
    public static final String API_KEY_CREATED = "API_KEY_CREATED";

    /** An API key was revoked. */
    public static final String API_KEY_REVOKED = "API_KEY_REVOKED";

    /** A bulk CSV import was performed. */
    public static final String BULK_IMPORT = "BULK_IMPORT";

    /** A user was promoted to ADMIN by an admin. */
    public static final String ADMIN_USER_PROMOTED = "ADMIN_USER_PROMOTED";

    private Action() {}
  }
}
