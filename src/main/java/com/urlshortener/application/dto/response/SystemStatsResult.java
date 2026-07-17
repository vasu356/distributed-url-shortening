package com.urlshortener.application.dto.response;

import java.time.Instant;

/** System-wide statistics for the admin dashboard. */
public record SystemStatsResult(
    long totalUsers,
    long activeUsers,
    long totalUrls,
    long totalClicksLast30Days,
    Instant generatedAt) {
  public SystemStatsResult {}
}
