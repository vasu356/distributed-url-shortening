package com.urlshortener.application.dto.response;

import java.time.Instant;

/** Aggregate analytics dashboard for a user over a time window. */
public record DashboardAnalyticsResult(
    long totalActiveUrls, long totalClicksInPeriod, Instant from, Instant to) {
  public DashboardAnalyticsResult {}
}
