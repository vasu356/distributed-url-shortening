package com.urlshortener.api.v1.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Analytics data for a single short URL over a time window. */
public record AnalyticsResponse(
    UUID shortUrlId,
    String shortCode,
    long totalClicks,
    long uniqueClicks,
    List<ClickByDate> clicksByDate,
    List<ClickByCountry> clicksByCountry,
    List<ClickByReferrer> clicksByReferrer,
    List<ClickByDevice> clicksByDevice,
    Instant from,
    Instant to) {
  public AnalyticsResponse {}
}
