package com.urlshortener.domain.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * API key entity.
 *
 * <p>Security model: - Raw key is generated once and shown to the user, never stored again. - Only
 * SHA-256(key) is stored in key_hash. - On every API request: SHA-256(incoming) → lookup key_hash →
 * Redis cache (5min TTL) → DB. - This means a compromised DB exposes only hashes, not usable keys.
 *
 * <p>Scopes: granular permissions per key. A read-only key can only call GET endpoints.
 */
@Entity
@Table(
    name = "api_keys",
    indexes = {
      @Index(name = "idx_api_keys_hash", columnList = "key_hash", unique = true),
      @Index(name = "idx_api_keys_user", columnList = "user_id")
    })
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /** SHA-256 hash of the raw API key. Raw key is never stored. */
  @Column(name = "key_hash", nullable = false, unique = true, length = 64)
  private String keyHash;

  @Column(name = "name", length = 100)
  private String name;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "api_key_scopes", joinColumns = @JoinColumn(name = "api_key_id"))
  @Column(name = "scope")
  private List<String> scopes;

  /** Requests per hour limit for this key. */
  @Column(name = "rate_limit", nullable = false)
  private int rateLimit = 1000;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked", nullable = false)
  private boolean revoked = false;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * Factory method — prefer over constructor to enforce invariants.
   *
   * @param user the owning user
   * @param keyHash SHA-256 hex hash of the raw key
   * @param name human-readable name for the key
   * @param scopes the list of permission scopes
   * @return a new active, non-revoked ApiKey
   */
  public static ApiKey create(User user, String keyHash, String name, List<String> scopes) {
    ApiKey apiKey = new ApiKey();
    apiKey.user = user;
    apiKey.keyHash = keyHash;
    apiKey.name = name;
    apiKey.scopes = scopes;
    apiKey.revoked = false;
    return apiKey;
  }

  /** Returns true if this key is usable (not revoked and not expired). */
  public boolean isValid() {
    if (revoked) {
      return false;
    }
    if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
      return false;
    }
    return true;
  }

  /** Revokes this API key. */
  public void revoke() {
    this.revoked = true;
  }

  /** Updates the last-used timestamp to now. */
  public void recordUsage() {
    this.lastUsedAt = Instant.now();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ApiKey)) {
      return false;
    }
    ApiKey other = (ApiKey) o;
    return keyHash != null && keyHash.equals(other.keyHash);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
