package com.urlshortener.application.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Analytics data for a single short URL over a time window. */
public record AnalyticsResult(
    UUID shortUrlId,
    String shortCode,
    long totalClicks,
    long uniqueClicks,
    List<ClickByDateResult> clicksByDate,
    List<ClickByCountryResult> clicksByCountry,
    List<ClickByReferrerResult> clicksByReferrer,
    List<ClickByDeviceResult> clicksByDevice,
    Instant from,
    Instant to) {
  public AnalyticsResult {}
}
