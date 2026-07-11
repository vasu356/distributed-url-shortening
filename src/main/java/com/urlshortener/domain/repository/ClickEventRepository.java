package com.urlshortener.domain.repository;

import com.urlshortener.domain.model.ClickEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, UUID> {

  long countByShortUrlId(UUID shortUrlId);

  long countByShortUrlIdAndCreatedAtAfter(UUID shortUrlId, Instant after);

  /** Distinct clicks by privacy-preserving ip_hash — used for unique-visitor analytics. */
  @Query(
      value =
          """
          SELECT COUNT(DISTINCT ip_hash)
          FROM click_events
          WHERE short_url_id = :urlId
          """,
      nativeQuery = true)
  long countDistinctIpHashByShortUrlId(UUID urlId);

  /** Clicks aggregated by date for time-series chart. */
  @Query(
      value =
          """
          SELECT DATE(created_at) as date, COUNT(*) as count
          FROM click_events
          WHERE short_url_id = :urlId
            AND created_at >= :from
            AND created_at <= :to
          GROUP BY DATE(created_at)
          ORDER BY DATE(created_at)
          """,
      nativeQuery = true)
  List<Object[]> findClicksByDate(
      @Param("urlId") UUID urlId, @Param("from") Instant from, @Param("to") Instant to);

  /** Geographic distribution. */
  @Query(
      value =
          """
          SELECT country_code, COUNT(*) as count
          FROM click_events
          WHERE short_url_id = :urlId
            AND created_at >= :from
            AND country_code IS NOT NULL
          GROUP BY country_code
          ORDER BY count DESC
          LIMIT 20
          """,
      nativeQuery = true)
  List<Object[]> findClicksByCountry(@Param("urlId") UUID urlId, @Param("from") Instant from);

  /** Referrer distribution. */
  @Query(
      value =
          """
          SELECT COALESCE(referrer, 'direct') as referrer, COUNT(*) as count
          FROM click_events
          WHERE short_url_id = :urlId
            AND created_at >= :from
          GROUP BY COALESCE(referrer, 'direct')
          ORDER BY count DESC
          LIMIT 10
          """,
      nativeQuery = true)
  List<Object[]> findClicksByReferrer(@Param("urlId") UUID urlId, @Param("from") Instant from);

  /** Device type breakdown. */
  @Query(
      value =
          """
          SELECT device_type, COUNT(*) as count
          FROM click_events
          WHERE short_url_id = :urlId
            AND created_at >= :from
          GROUP BY device_type
          ORDER BY count DESC
          """,
      nativeQuery = true)
  List<Object[]> findClicksByDevice(@Param("urlId") UUID urlId, @Param("from") Instant from);

  /** Total clicks for all URLs owned by a user. */
  @Query(
      value =
          """
          SELECT COUNT(ce.id)
          FROM click_events ce
          JOIN short_urls su ON su.id = ce.short_url_id
          WHERE su.user_id = :userId
            AND ce.created_at >= :from
          """,
      nativeQuery = true)
  long countClicksForUser(@Param("userId") UUID userId, @Param("from") Instant from);

  /** Batch save for Kafka consumer — saves network round trips. */
  // Inherited from JpaRepository: saveAll()
}
