package com.urlshortener.api.v1.dto.response;

import java.time.Instant;

/** Aggregate analytics dashboard for a user over a time window. */
public record DashboardAnalyticsResponse(
    long totalActiveUrls, long totalClicksInPeriod, Instant from, Instant to) {
  public DashboardAnalyticsResponse {}
}
