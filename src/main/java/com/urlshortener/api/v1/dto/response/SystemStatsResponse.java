package com.urlshortener.api.v1.dto.response;

import java.time.Instant;

/** System-wide statistics for the admin dashboard. */
public record SystemStatsResponse(
    long totalUsers,
    long activeUsers,
    long totalUrls,
    long totalClicksLast30Days,
    Instant generatedAt) {
  public SystemStatsResponse {}
}
