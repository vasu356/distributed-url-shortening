package com.urlshortener.api.v1.controller;

import com.urlshortener.application.usecase.UrlUseCase;
import com.urlshortener.application.usecase.UrlUseCase.ResolvedUrl;
import com.urlshortener.infrastructure.kafka.producer.EventProducer;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import com.urlshortener.infrastructure.ratelimit.RateLimiterService;
import io.micrometer.core.annotation.Timed;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redirect controller — the highest-traffic endpoint in the system.
 *
 * <p>Hot-path design goals: - Sub-10ms p99 on cache hit - Zero blocking I/O on the critical
 * response path - Click tracking is fully async (Kafka fire-and-forget, never delays response) - IP
 * rate limiting at this layer; authenticated rate limiting in UrlUseCase
 *
 * <p>Note: shortUrlId is not resolved here intentionally. The Kafka consumer resolves it from
 * shortCode during async processing, keeping this path as fast as possible. The shortCode is the
 * durable, immutable identifier.
 */
@RestController
@RequestMapping("/r")
@RequiredArgsConstructor
@Slf4j
public class RedirectController {

  private final UrlUseCase urlUseCase;
  private final EventProducer eventProducer;
  private final RateLimiterService rateLimiterService;

  @GetMapping("/{shortCode}")
  @Timed(value = "url.redirect", description = "Redirect resolution time")
  public ResponseEntity<Void> redirect(@PathVariable String shortCode, HttpServletRequest request) {

    String clientIp = extractClientIp(request);
    rateLimiterService.checkIpRateLimit(clientIp);

    // Resolves from Redis (cache hit) or PostgreSQL (cache miss)
    ResolvedUrl resolved = urlUseCase.resolveForRedirect(shortCode);

    // Fire-and-forget: never blocks the response
    publishClickAsync(shortCode, clientIp, request);

    MDC.put("shortCode", shortCode);
    log.debug("Redirect shortCode={}", shortCode);

    return ResponseEntity.status(HttpStatus.valueOf(resolved.redirectType()))
        .header(HttpHeaders.LOCATION, resolved.longUrl())
        .header("Cache-Control", "no-store")
        .header("X-Short-Code", shortCode)
        .build();
  }

  private void publishClickAsync(String shortCode, String clientIp, HttpServletRequest request) {
    try {
      String userAgent = truncate(request.getHeader("User-Agent"), 512);
      String referrer = truncate(request.getHeader("Referer"), 500);
      String ipHash = hashIp(clientIp);
      String device = detectDevice(userAgent);

      // shortUrlId is null here — the Kafka consumer resolves it by shortCode.
      // This keeps the redirect path at O(1) cache lookup without a second DB call.
      eventProducer.publishClickEvent(
          KafkaEvents.ClickEvent.of(
              null, shortCode, ipHash, null, null, userAgent, referrer, device, null, null));
    } catch (Exception ex) {
      // Analytics failure must never fail a redirect
      log.warn("Click event publish failed shortCode={}: {}", shortCode, ex.getMessage());
    }
  }

  private String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    String realIp = request.getHeader("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) {
      return realIp.trim();
    }
    return request.getRemoteAddr();
  }

  /** SHA-256(ip + daily_salt) — privacy-preserving, GDPR-compliant. */
  private String hashIp(String ip) {
    try {
      String salt = String.valueOf(Instant.now().getEpochSecond() / 86400L);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest((ip + salt).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return "unknown";
    }
  }

  private String detectDevice(String ua) {
    if (ua == null) {
      return "UNKNOWN";
    }
    String lower = ua.toLowerCase();
    if (lower.contains("bot") || lower.contains("crawler") || lower.contains("spider")) {
      return "BOT";
    }
    if (lower.contains("mobile") || lower.contains("android") || lower.contains("iphone")) {
      return "MOBILE";
    }
    if (lower.contains("tablet") || lower.contains("ipad")) {
      return "TABLET";
    }
    return "DESKTOP";
  }

  private String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() > max ? s.substring(0, max) : s;
  }
}
