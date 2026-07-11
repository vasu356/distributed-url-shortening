package com.urlshortener.domain.model;

import jakarta.persistence.Column;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Core URL mapping entity.
 *
 * <p>Index strategy: - idx_short_code: UNIQUE — the hot-path lookup key - idx_short_urls_user:
 * composite (user_id, created_at DESC) — user URL list with date ordering - idx_short_urls_expiry:
 * partial index on expires_at WHERE active — scheduler finds expirables - Full-text search index
 * defined in Flyway migration (GIN index on tsvector)
 *
 * <p>click_count is denormalized here for fast display without aggregating click_events.
 * Incremented atomically via UPDATE ... SET click_count = click_count + 1. The source of truth for
 * analytics remains the click_events table.
 */
@Entity
@Table(
    name = "short_urls",
    indexes = {
      @Index(name = "idx_short_code", columnList = "short_code", unique = true),
      @Index(name = "idx_short_urls_user", columnList = "user_id,created_at"),
      @Index(name = "idx_short_urls_expiry", columnList = "expires_at")
    })
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShortUrl {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "short_code", nullable = false, unique = true, length = 20)
  private String shortCode;

  @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
  private String longUrl;

  /** Metadata fields populated asynchronously by metadata scraper. */
  @Column(length = 500)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "favicon_url", length = 500)
  private String faviconUrl;

  @Column(name = "is_custom", nullable = false)
  private boolean custom = false;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  /** Optional password protection — BCrypt hash of the password. */
  @Column(name = "password_hash", length = 255)
  private String passwordHash;

  /**
   * Denormalized counter — fast reads for dashboards. Analytics queries use click_events table for
   * accuracy.
   */
  @Column(name = "click_count", nullable = false)
  private long clickCount = 0L;

  /** Optional cap on total clicks. */
  @Column(name = "max_clicks")
  private Long maxClicks;

  /**
   * 301: permanent (browser caches — use for stable URLs, harder to change). 302: temporary
   * (browser doesn't cache — analytics-friendly default).
   */
  @Column(name = "redirect_type", nullable = false)
  private int redirectType = 302;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  // ================================================================
  // FACTORY METHODS
  // ================================================================

  /**
   * Factory method — prefer over constructor to enforce invariants.
   *
   * @param user the owning user
   * @param shortCode the generated or custom short code
   * @param longUrl the target URL
   * @param isCustom whether a custom alias was requested
   * @return a new ShortUrl instance
   */
  public static ShortUrl create(User user, String shortCode, String longUrl, boolean isCustom) {
    ShortUrl url = new ShortUrl();
    url.user = user;
    url.shortCode = shortCode;
    url.longUrl = longUrl;
    url.custom = isCustom;
    url.active = true;
    url.clickCount = 0;
    url.redirectType = 302;
    return url;
  }

  // ================================================================
  // DOMAIN METHODS — business logic lives on the entity
  // ================================================================

  /** Returns true if the URL's expiry date has passed. */
  public boolean isExpired() {
    return expiresAt != null && Instant.now().isAfter(expiresAt);
  }

  /** Returns true if this URL has hit its configured click cap. */
  public boolean hasReachedClickCap() {
    return maxClicks != null && clickCount >= maxClicks;
  }

  /** Returns true if this URL can serve redirects right now. */
  public boolean isRedirectable() {
    return active && !isExpired() && !hasReachedClickCap() && deletedAt == null;
  }

  /** Soft-deletes this URL by marking it inactive and recording the deletion timestamp. */
  public void softDelete() {
    this.active = false;
    this.deletedAt = Instant.now();
  }

  /** Restores a previously soft-deleted URL. */
  public void restore() {
    this.active = true;
    this.deletedAt = null;
  }

  /** Increments the in-memory click counter (used before batch flush). */
  public void incrementClickCount() {
    this.clickCount++;
  }

  /** Marks this URL as expired by deactivating it and setting expiry to now. */
  public void expire() {
    this.active = false;
    this.expiresAt = Instant.now();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ShortUrl)) {
      return false;
    }
    ShortUrl other = (ShortUrl) o;
    return shortCode != null && shortCode.equals(other.shortCode);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
