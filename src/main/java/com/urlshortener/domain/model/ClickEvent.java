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
import lombok.Setter;

/**
 * Click event entity — time-series data partitioned by month.
 *
 * <p>Privacy: ip_hash stores SHA-256(IP + daily_salt). This means: - We can deduplicate within a
 * day (same hash = same IP) - We cannot recover the original IP - Salt rotates daily, preventing
 * cross-day correlation - Compliant with GDPR data minimisation principle
 *
 * <p>No FK constraint on short_url_id because PostgreSQL doesn't enforce FK across partition
 * boundaries without special setup. Referential integrity enforced at application layer.
 *
 * <p>Note: This entity is written via Kafka consumer, not via JPA directly in the redirect path.
 * The redirect path publishes to Kafka (non-blocking) and the consumer batch-inserts.
 */
@Entity
@Table(
    name = "click_events",
    indexes = {
      @Index(name = "idx_click_events_url", columnList = "short_url_id,created_at"),
      @Index(name = "idx_click_events_time", columnList = "created_at")
    })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClickEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(updatable = false, nullable = false)
  private UUID id;

  /** No @ManyToOne — see class Javadoc on partitioned table FK limitations. */
  @Column(name = "short_url_id", nullable = false)
  private UUID shortUrlId;

  /** SHA-256(IP + daily_salt) — privacy-preserving deduplication key. */
  @Column(name = "ip_hash", length = 64)
  private String ipHash;

  @Column(name = "country_code", length = 2)
  private String countryCode;

  @Column(name = "city", length = 100)
  private String city;

  @Column(name = "user_agent", columnDefinition = "TEXT")
  private String userAgent;

  @Column(name = "referrer", length = 2000)
  private String referrer;

  @Enumerated(EnumType.STRING)
  @Column(name = "device_type", length = 20)
  private DeviceType deviceType;

  @Column(name = "browser", length = 50)
  private String browser;

  @Column(name = "os", length = 50)
  private String os;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * Factory method for creating click events.
   *
   * @param shortUrlId the UUID of the shortened URL
   * @param ipHash SHA-256 hash of the visitor IP plus daily salt
   * @param countryCode ISO-3166 two-letter country code
   * @param city the city name if available
   * @param userAgent the raw User-Agent header value
   * @param referrer the HTTP Referer header value
   * @param deviceType the parsed device category
   * @param browser the detected browser name
   * @param os the detected operating system name
   * @return a new ClickEvent with the current timestamp
   */
  public static ClickEvent create(
      UUID shortUrlId,
      String ipHash,
      String countryCode,
      String city,
      String userAgent,
      String referrer,
      DeviceType deviceType,
      String browser,
      String os) {
    ClickEvent event = new ClickEvent();
    event.shortUrlId = shortUrlId;
    event.ipHash = ipHash;
    event.countryCode = countryCode;
    event.city = city;
    event.userAgent = userAgent;
    event.referrer = referrer;
    event.deviceType = deviceType;
    event.browser = browser;
    event.os = os;
    event.createdAt = Instant.now();
    return event;
  }

  /** Device categories for analytics grouping. */
  public enum DeviceType {
    MOBILE,
    DESKTOP,
    TABLET,
    BOT,
    UNKNOWN
  }
}
