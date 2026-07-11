package com.urlshortener.application.usecase;

import com.urlshortener.api.v1.dto.response.AnalyticsResponse;
import com.urlshortener.api.v1.dto.response.ClickByCountry;
import com.urlshortener.api.v1.dto.response.ClickByDate;
import com.urlshortener.api.v1.dto.response.ClickByDevice;
import com.urlshortener.api.v1.dto.response.ClickByReferrer;
import com.urlshortener.api.v1.dto.response.DashboardAnalyticsResponse;
import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.domain.model.ShortUrl;
import com.urlshortener.domain.repository.ClickEventRepository;
import com.urlshortener.domain.repository.ShortUrlRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analytics use case.
 *
 * <p>Provides click analytics for individual URLs and dashboard aggregations for a user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsUseCase {

  private final ShortUrlRepository shortUrlRepository;
  private final ClickEventRepository clickEventRepository;

  /**
   * Returns click analytics for a single short URL over the given number of days.
   *
   * @param shortCode the short code to query
   * @param userId the requesting user's UUID (must be the owner)
   * @param days the lookback window in days
   * @return the analytics response
   */
  @Transactional(readOnly = true)
  public AnalyticsResponse getUrlAnalytics(String shortCode, UUID userId, int days) {
    ShortUrl url =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new Exceptions.UrlNotFoundException(shortCode));
    if (!url.getUser().getId().equals(userId)) {
      throw new Exceptions.UnauthorizedAccessException();
    }
    Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
    Instant to = Instant.now();

    long totalClicks = clickEventRepository.countByShortUrlId(url.getId());
    long recentClicks = clickEventRepository.countByShortUrlIdAndCreatedAtAfter(url.getId(), from);
    long uniqueClicks = clickEventRepository.countDistinctIpHashByShortUrlId(url.getId());

    List<ClickByDate> byDate =
        clickEventRepository.findClicksByDate(url.getId(), from, to).stream()
            .map(r -> new ClickByDate(r[0].toString(), ((Number) r[1]).longValue()))
            .toList();
    List<ClickByCountry> byCountry =
        clickEventRepository.findClicksByCountry(url.getId(), from).stream()
            .map(
                r ->
                    new ClickByCountry(
                        r[0] != null ? r[0].toString() : "Unknown", ((Number) r[1]).longValue()))
            .toList();
    List<ClickByReferrer> byReferrer =
        clickEventRepository.findClicksByReferrer(url.getId(), from).stream()
            .map(
                r ->
                    new ClickByReferrer(
                        r[0] != null ? r[0].toString() : "direct", ((Number) r[1]).longValue()))
            .toList();
    List<ClickByDevice> byDevice =
        clickEventRepository.findClicksByDevice(url.getId(), from).stream()
            .map(
                r ->
                    new ClickByDevice(
                        r[0] != null ? r[0].toString() : "UNKNOWN", ((Number) r[1]).longValue()))
            .toList();

    return new AnalyticsResponse(
        url.getId(),
        shortCode,
        totalClicks,
        uniqueClicks,
        byDate,
        byCountry,
        byReferrer,
        byDevice,
        from,
        to);
  }

  /**
   * Returns an aggregated analytics dashboard for all URLs owned by the user.
   *
   * @param userId the user's UUID
   * @param days the lookback window in days
   * @return the dashboard analytics response
   */
  @Transactional(readOnly = true)
  public DashboardAnalyticsResponse getDashboard(UUID userId, int days) {
    Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
    long totalUrls = shortUrlRepository.countByUserIdAndActiveTrue(userId);
    long totalClicks = clickEventRepository.countClicksForUser(userId, from);
    return new DashboardAnalyticsResponse(totalUrls, totalClicks, from, Instant.now());
  }
}
