package com.urlshortener.api.v1.dto.response;

import java.time.Instant;

/** Application health check response. */
public record HealthResponse(String status, String version, Instant timestamp) {
  public HealthResponse {}
}
