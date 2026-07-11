package com.urlshortener.application.usecase;

import com.urlshortener.api.v1.dto.request.UrlDtos;
import com.urlshortener.api.v1.dto.response.BulkCreateResponse;
import com.urlshortener.api.v1.dto.response.BulkError;
import com.urlshortener.api.v1.dto.response.PagedResponse;
import com.urlshortener.api.v1.dto.response.UrlResponse;
import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.common.util.ShortCodeGenerator;
import com.urlshortener.domain.model.ShortUrl;
import com.urlshortener.domain.model.User;
import com.urlshortener.domain.repository.ShortUrlRepository;
import com.urlshortener.domain.repository.UserRepository;
import com.urlshortener.domain.service.UrlValidationService;
import com.urlshortener.infrastructure.cache.CachedUrl;
import com.urlshortener.infrastructure.cache.UrlCacheService;
import com.urlshortener.infrastructure.kafka.producer.EventProducer;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core URL shortening use case.
 *
 * <p>Handles creation, retrieval, update, soft-delete, and bulk operations. The redirect hot-path
 * ({@link #resolveForRedirect}) is kept as lean as possible: Redis cache-aside with a DB fallback,
 * no writes on the critical path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlUseCase {

  private final ShortUrlRepository shortUrlRepository;
  private final UserRepository userRepository;
  private final ShortCodeGenerator shortCodeGenerator;
  private final UrlValidationService urlValidationService;
  private final UrlCacheService urlCacheService;
  private final EventProducer eventProducer;
  private final PasswordEncoder passwordEncoder;
  private final MeterRegistry meterRegistry;

  @Value("${app.base-url}")
  private String baseUrl;

  // ================================================================
  // CREATE
  // ================================================================

  /**
   * Creates a new short URL for the given user.
   *
   * @param request the creation request
   * @param userId the authenticated user's UUID
   * @return the created URL response
   */
  @Transactional
  public UrlResponse createUrl(UrlDtos.CreateUrlRequest request, UUID userId) {
    urlValidationService.validate(request.longUrl());

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new Exceptions.UserNotFoundException(userId.toString()));

    String shortCode = resolveShortCode(request.customAlias(), request.longUrl());
    boolean isCustom = request.customAlias() != null && !request.customAlias().isBlank();

    ShortUrl shortUrl = ShortUrl.create(user, shortCode, request.longUrl(), isCustom);

    if (request.expiresAt() != null) {
      shortUrl.setExpiresAt(request.expiresAt());
    }
    if (request.redirectType() != null
        && (request.redirectType() == 301 || request.redirectType() == 302)) {
      shortUrl.setRedirectType(request.redirectType());
    }
    if (request.maxClicks() != null && request.maxClicks() > 0) {
      shortUrl.setMaxClicks(request.maxClicks());
    }
    if (request.password() != null && !request.password().isBlank()) {
      shortUrl.setPasswordHash(passwordEncoder.encode(request.password()));
    }

    try {
      shortUrl = shortUrlRepository.save(shortUrl);
    } catch (DataIntegrityViolationException ex) {
      // Rare race condition: another request inserted the same code between check and insert
      throw new Exceptions.AliasAlreadyExistsException(shortCode);
    }

    // Register in Bloom filter after successful DB insert
    shortCodeGenerator.register(shortCode);

    // Populate cache immediately — avoid cache miss on first redirect
    cacheUrl(shortUrl);

    // Async: publish lifecycle event (triggers QR gen + metadata scraping)
    eventProducer.publishLifecycleEvent(
        KafkaEvents.LifecycleEvent.of(
            shortUrl.getId(), shortCode, request.longUrl(), "CREATED", userId));

    // Async: audit log
    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            userId,
            "USER",
            "URL_CREATED",
            "ShortUrl",
            shortUrl.getId(),
            null,
            shortUrl.getLongUrl(),
            null,
            null));

    meterRegistry.counter("url.created", "custom", String.valueOf(isCustom)).increment();
    log.info("Created short URL: shortCode={} userId={}", shortCode, userId);

    return toResponse(shortUrl);
  }

  private String resolveShortCode(String customAlias, String longUrl) {
    if (customAlias != null && !customAlias.isBlank()) {
      String alias = customAlias.trim();
      if (shortUrlRepository.existsByShortCode(alias)) {
        throw new Exceptions.AliasAlreadyExistsException(alias);
      }
      return alias;
    }
    return shortCodeGenerator.generate();
  }

  // ================================================================
  // READ
  // ================================================================

  /**
   * Returns full URL details for the given short code.
   *
   * @param shortCode the short code to look up
   * @param requestingUserId the authenticated user requesting the details
   * @return the URL response
   */
  @Transactional(readOnly = true)
  public UrlResponse getUrl(String shortCode, UUID requestingUserId) {
    ShortUrl url =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new Exceptions.UrlNotFoundException(shortCode));

    // Only owner or admin can view details of deleted URLs
    if (url.getDeletedAt() != null && !url.getUser().getId().equals(requestingUserId)) {
      throw new Exceptions.UrlNotFoundException(shortCode);
    }

    return toResponse(url);
  }

  /**
   * Returns a page of active URLs owned by the given user.
   *
   * @param userId the user's UUID
   * @param pageable pagination parameters
   * @return paged URL responses
   */
  @Transactional(readOnly = true)
  public PagedResponse<UrlResponse> getUserUrls(UUID userId, Pageable pageable) {
    Page<ShortUrl> page = shortUrlRepository.findByUserIdAndDeletedAtIsNull(userId, pageable);
    return PagedResponse.of(page.map(this::toResponse));
  }

  /**
   * Full-text searches the user's URLs by long URL and title.
   *
   * @param userId the user's UUID
   * @param query the search query
   * @param pageable pagination parameters
   * @return paged URL responses matching the query
   */
  @Transactional(readOnly = true)
  public PagedResponse<UrlResponse> searchUserUrls(UUID userId, String query, Pageable pageable) {
    Page<ShortUrl> page = shortUrlRepository.searchByUserAndQuery(userId, query, pageable);
    return PagedResponse.of(page.map(this::toResponse));
  }

  // ================================================================
  // REDIRECT — hot path, called 50k/sec
  // ================================================================

  /**
   * Resolves a short code to its target URL for redirect. Cache-aside: Redis first, then DB on
   * miss. Does NOT write to DB (click count increment is done via Kafka consumer).
   *
   * @param shortCode the short code to resolve
   * @return the long URL to redirect to
   */
  @Transactional(readOnly = true)
  public String resolveForRedirect(String shortCode) {
    Timer.Sample timer = Timer.start(meterRegistry);

    // L1: Redis cache
    var cached = urlCacheService.get(shortCode);
    if (cached.isPresent()) {
      CachedUrl cachedUrl = cached.get();
      timer.stop(meterRegistry.timer("url.redirect.duration", "cache", "hit"));

      if (!cachedUrl.isRedirectable()) {
        if (cachedUrl.isExpired()) {
          throw new Exceptions.UrlExpiredException(shortCode);
        }
        if (cachedUrl.hasReachedClickCap()) {
          throw new Exceptions.UrlClickCapReachedException(shortCode);
        }
        throw new Exceptions.UrlInactiveException(shortCode);
      }
      return cachedUrl.longUrl();
    }

    // L2: Database
    ShortUrl url =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new Exceptions.UrlNotFoundException(shortCode));

    timer.stop(meterRegistry.timer("url.redirect.duration", "cache", "miss"));

    // Populate cache for future requests
    cacheUrl(url);

    if (!url.isRedirectable()) {
      if (url.isExpired()) {
        throw new Exceptions.UrlExpiredException(shortCode);
      }
      if (url.hasReachedClickCap()) {
        throw new Exceptions.UrlClickCapReachedException(shortCode);
      }
      throw new Exceptions.UrlInactiveException(shortCode);
    }

    return url.getLongUrl();
  }

  // ================================================================
  // UPDATE
  // ================================================================

  /**
   * Updates a short URL owned by the given user.
   *
   * @param shortCode the short code to update
   * @param request the update request
   * @param userId the authenticated user's UUID (must be owner)
   * @return the updated URL response
   */
  @Transactional
  public UrlResponse updateUrl(String shortCode, UrlDtos.UpdateUrlRequest request, UUID userId) {
    ShortUrl url =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new Exceptions.UrlNotFoundException(shortCode));

    assertOwnership(url, userId);

    String oldLongUrl = url.getLongUrl();

    if (request.longUrl() != null) {
      urlValidationService.validate(request.longUrl());
      url.setLongUrl(request.longUrl());
    }
    if (request.expiresAt() != null) {
      url.setExpiresAt(request.expiresAt());
    }
    if (request.active() != null) {
      url.setActive(request.active());
    }
    if (request.redirectType() != null) {
      url.setRedirectType(request.redirectType());
    }
    if (request.maxClicks() != null) {
      url.setMaxClicks(request.maxClicks());
    }

    shortUrlRepository.save(url);

    // Invalidate cache — force fresh load on next redirect
    urlCacheService.evict(shortCode);

    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            userId,
            "USER",
            "URL_UPDATED",
            "ShortUrl",
            url.getId(),
            oldLongUrl,
            url.getLongUrl(),
            null,
            null));

    log.info("Updated short URL: shortCode={} userId={}", shortCode, userId);
    return toResponse(url);
  }

  // ================================================================
  // DELETE (soft)
  // ================================================================

  /**
   * Soft-deletes a short URL owned by the given user.
   *
   * @param shortCode the short code to delete
   * @param userId the authenticated user's UUID (must be owner)
   */
  @Transactional
  public void deleteUrl(String shortCode, UUID userId) {
    ShortUrl url =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new Exceptions.UrlNotFoundException(shortCode));

    assertOwnership(url, userId);

    url.softDelete();
    shortUrlRepository.save(url);

    // Immediate cache eviction — deleted URLs must not redirect
    urlCacheService.evict(shortCode);

    eventProducer.publishLifecycleEvent(
        KafkaEvents.LifecycleEvent.of(url.getId(), shortCode, url.getLongUrl(), "DELETED", userId));
    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            userId,
            "USER",
            "URL_DELETED",
            "ShortUrl",
            url.getId(),
            url.getLongUrl(),
            null,
            null,
            null));

    log.info("Soft-deleted short URL: shortCode={} userId={}", shortCode, userId);
  }

  // ================================================================
  // BULK CREATE
  // ================================================================

  /**
   * Creates multiple short URLs in a single operation.
   *
   * @param request the bulk creation request (max 1000 URLs)
   * @param userId the authenticated user's UUID
   * @return a summary of created URLs and any errors
   */
  @Transactional
  public BulkCreateResponse bulkCreate(UrlDtos.BulkCreateRequest request, UUID userId) {
    userRepository
        .findById(userId)
        .orElseThrow(() -> new Exceptions.UserNotFoundException(userId.toString()));

    List<UrlResponse> created = new ArrayList<>();
    List<BulkError> errors = new ArrayList<>();

    int index = 0;
    for (UrlDtos.CreateUrlRequest urlRequest : request.urls()) {
      try {
        UrlResponse response = createUrl(urlRequest, userId);
        created.add(response);
      } catch (Exception ex) {
        errors.add(new BulkError(index, urlRequest.longUrl(), ex.getMessage()));
        log.debug("Bulk create error at index {}: {}", index, ex.getMessage());
      }
      index++;
    }

    return new BulkCreateResponse(
        request.urls().size(), created.size(), errors.size(), created, errors);
  }

  // ================================================================
  // HELPERS
  // ================================================================

  private void assertOwnership(ShortUrl url, UUID userId) {
    if (!url.getUser().getId().equals(userId)) {
      throw new Exceptions.UnauthorizedAccessException();
    }
  }

  private void cacheUrl(ShortUrl url) {
    urlCacheService.put(
        url.getShortCode(),
        new CachedUrl(
            url.getLongUrl(),
            url.isActive(),
            url.getExpiresAt() != null ? url.getExpiresAt().getEpochSecond() : null,
            url.getMaxClicks(),
            url.getClickCount(),
            url.getRedirectType()));
  }

  /**
   * Maps a ShortUrl entity to its API response DTO.
   *
   * @param url the entity to map
   * @return the response DTO
   */
  public UrlResponse toResponse(ShortUrl url) {
    return new UrlResponse(
        url.getId(),
        url.getShortCode(),
        baseUrl + "/r/" + url.getShortCode(),
        url.getLongUrl(),
        url.getTitle(),
        url.getFaviconUrl(),
        url.isActive(),
        url.isCustom(),
        url.getExpiresAt(),
        url.getClickCount(),
        url.getMaxClicks(),
        url.getRedirectType(),
        url.getCreatedAt(),
        url.getUpdatedAt());
  }
}
