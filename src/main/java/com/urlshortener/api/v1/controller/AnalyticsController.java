package com.urlshortener.api.v1.controller;

import com.urlshortener.api.v1.dto.response.AnalyticsResponse;
import com.urlshortener.api.v1.dto.response.DashboardAnalyticsResponse;
import com.urlshortener.application.usecase.AnalyticsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analytics endpoints.
 *
 * <p>Provides click analytics per URL and dashboard aggregations for the authenticated user.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Click analytics for shortened URLs")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

  private final AnalyticsUseCase analyticsUseCase;

  @GetMapping("/{shortCode}")
  @Operation(summary = "Get analytics for a specific short URL")
  public ResponseEntity<AnalyticsResponse> getUrlAnalytics(
      @PathVariable String shortCode,
      @RequestParam(defaultValue = "30") int days,
      @AuthenticationPrincipal String userId) {
    int safeDays = Math.min(Math.max(days, 1), 365);
    return ResponseEntity.ok(
        analyticsUseCase.getUrlAnalytics(shortCode, UUID.fromString(userId), safeDays));
  }

  @GetMapping("/dashboard")
  @Operation(summary = "Get aggregate analytics for the authenticated user")
  public ResponseEntity<DashboardAnalyticsResponse> getDashboard(
      @RequestParam(defaultValue = "30") int days, @AuthenticationPrincipal String userId) {
    int safeDays = Math.min(Math.max(days, 1), 365);
    return ResponseEntity.ok(analyticsUseCase.getDashboard(UUID.fromString(userId), safeDays));
  }
}
