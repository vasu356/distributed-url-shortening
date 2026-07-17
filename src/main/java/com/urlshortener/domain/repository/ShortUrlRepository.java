package com.urlshortener.domain.repository;

import com.urlshortener.domain.model.ShortUrl;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for ShortUrl entities.
 *
 * <p>Key query decisions: - findByShortCode: uses UNIQUE index — O(1) lookup. -
 * incrementClickCount: native SQL UPDATE with atomic increment avoids read-modify-write race
 * condition. - findExpiredUrls: uses partial index on expires_at where is_active = true. -
 * JpaSpecificationExecutor: enables dynamic search/filter without N query methods.
 */
@Repository
public interface ShortUrlRepository
    extends JpaRepository<ShortUrl, UUID>, JpaSpecificationExecutor<ShortUrl> {

  Optional<ShortUrl> findByShortCode(String shortCode);

  Optional<ShortUrl> findByShortCodeAndActiveTrue(String shortCode);

  boolean existsByShortCode(String shortCode);

  Page<ShortUrl> findByUserIdAndDeletedAtIsNull(UUID userId, Pageable pageable);

  Page<ShortUrl> findByUserIdAndDeletedAtIsNotNull(UUID userId, Pageable pageable);

  /**
   * Full-text search over long_url and title. Uses GIN index on tsvector. More performant than LIKE
   * '%query%' which can't use indexes.
   */
  @Query(
      value =
          """
SELECT * FROM short_urls
WHERE user_id = :userId
  AND deleted_at IS NULL
  AND to_tsvector('english', long_url || ' ' || COALESCE(title, '')) @@ plainto_tsquery('english', :query)
""",
      countQuery =
          """
SELECT COUNT(*) FROM short_urls
WHERE user_id = :userId
  AND deleted_at IS NULL
  AND to_tsvector('english', long_url || ' ' || COALESCE(title, '')) @@ plainto_tsquery('english', :query)
""",
      nativeQuery = true)
  Page<ShortUrl> searchByUserAndQuery(
      @Param("userId") UUID userId, @Param("query") String query, Pageable pageable);

  /**
   * Atomic click count increment. Avoids optimistic locking overhead on the hot redirect path. Uses
   * direct UPDATE rather than read-modify-write.
   */
  @Modifying
  @Query(
      "UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1 WHERE s.id = :id AND s.active = true")
  int incrementClickCount(@Param("id") UUID id);

  /**
   * Batch increment click count for multiple URLs. More efficient than individual updates. Uses
   * native SQL for proper increment with count parameter.
   */
  @Modifying
  @Query(
      value =
          "UPDATE short_urls SET click_count = click_count + :increment WHERE id IN :ids AND"
              + " is_active = true",
      nativeQuery = true)
  int incrementClickCountsBatch(@Param("ids") List<UUID> ids, @Param("increment") int increment);

  /** Find URLs past their expiry date that are still marked active. Used by cleanup scheduler. */
  @Query(
      "SELECT s FROM ShortUrl s WHERE s.active = true AND s.expiresAt IS NOT NULL AND s.expiresAt <"
          + " :now")
  List<ShortUrl> findExpiredUrls(@Param("now") Instant now);

  /**
   * Find URLs deleted before the tombstone period. Used by hard-delete scheduler (after 30 days,
   * short codes can be reclaimed — but only after tombstone period to avoid 301 cache poisoning).
   */
  @Query("SELECT s FROM ShortUrl s WHERE s.deletedAt IS NOT NULL AND s.deletedAt < :before")
  List<ShortUrl> findUrlsReadyForHardDelete(@Param("before") Instant before);

  /** Count active URLs per user for quota enforcement. */
  List<ShortUrl> findAllByShortCodeIn(List<String> shortCodes);

  long countByUserIdAndActiveTrue(UUID userId);

  /** Find URLs with no clicks in the last N days — candidates for eviction from cache. */
  @Query(
      value =
          """
          SELECT s.id FROM short_urls s
          LEFT JOIN click_events c ON c.short_url_id = s.id AND c.created_at > :since
          WHERE s.is_active = true AND c.id IS NULL
          LIMIT :limit
          """,
      nativeQuery = true)
  List<UUID> findInactiveUrlIds(@Param("since") Instant since, @Param("limit") int limit);
}
