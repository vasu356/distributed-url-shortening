package com.urlshortener.application.usecase;

import com.urlshortener.application.dto.response.AuditLogResult;
import com.urlshortener.application.dto.response.PagedResult;
import com.urlshortener.application.dto.response.SystemStatsResult;
import com.urlshortener.application.dto.response.UrlResult;
import com.urlshortener.application.dto.response.UserResult;
import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.domain.model.AuditLog;
import com.urlshortener.domain.model.ShortUrl;
import com.urlshortener.domain.model.User;
import com.urlshortener.domain.repository.AuditLogRepository;
import com.urlshortener.domain.repository.ClickEventRepository;
import com.urlshortener.domain.repository.ShortUrlRepository;
import com.urlshortener.domain.repository.UserRepository;
import com.urlshortener.infrastructure.cache.UrlCacheService;
import com.urlshortener.infrastructure.kafka.producer.AuditJsonBuilder;
import com.urlshortener.infrastructure.kafka.producer.EventProducer;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin use case — privileged operations on users, URLs, and audit logs.
 *
 * <p>All methods here require {@code ROLE_ADMIN} enforced at the controller layer via Spring
 * Security method security.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUseCase {

  private final UserRepository userRepository;
  private final ShortUrlRepository shortUrlRepository;
  private final AuditLogRepository auditLogRepository;
  private final ClickEventRepository clickEventRepository;
  private final UrlCacheService urlCacheService;
  private final EventProducer eventProducer;
  private final AuditJsonBuilder auditJson;
  private final UrlUseCase urlUseCase;

  // ----------------------------------------------------------------
  // User management
  // ----------------------------------------------------------------

  /**
   * Lists all users with optional email search, paginated.
   *
   * @param page zero-based page index
   * @param size page size (capped at 100)
   * @param search optional email search string
   * @return paged user result
   */
  @Transactional(readOnly = true)
  public PagedResult<UserResult> listUsers(int page, int size, String search) {
    PageRequest pageable =
        PageRequest.of(page, Math.min(size, 100), Sort.by("createdAt").descending());
    Page<User> users =
        (search != null && !search.isBlank())
            ? userRepository.searchByEmail(search, pageable)
            : userRepository.findAll(pageable);
    return PagedResult.of(users.map(this::toUserResult));
  }

  /**
   * Returns a single user by UUID.
   *
   * @param userId the user's UUID
   * @return the user result
   */
  @Transactional(readOnly = true)
  public UserResult getUser(UUID userId) {
    return toUserResult(
        userRepository
            .findById(userId)
            .orElseThrow(() -> new Exceptions.UserNotFoundException(userId.toString())));
  }

  /**
   * Deactivates a user account.
   *
   * @param userId the user to deactivate
   * @param actorId the admin performing the action
   */
  @Transactional
  public void deactivateUser(UUID userId, UUID actorId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new Exceptions.UserNotFoundException(userId.toString()));
    user.deactivate();
    userRepository.save(user);
    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            actorId,
            "ADMIN",
            AuditLog.Action.USER_DEACTIVATED,
            "User",
            userId,
            null,
            null,
            null,
            null));
    log.info("Admin deactivated user {}", userId);
  }

  /**
   * Promotes a user to the ADMIN role.
   *
   * @param userId the user to promote
   * @return the updated user result
   */
  @Transactional
  public UserResult promoteToAdmin(UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new Exceptions.UserNotFoundException(userId.toString()));
    user.promoteToAdmin();
    userRepository.save(user);
    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            null,
            "ADMIN",
            AuditLog.Action.ADMIN_USER_PROMOTED,
            "User",
            userId,
            null,
            null,
            null,
            null));
    log.info("User {} promoted to ADMIN", userId);
    return toUserResult(user);
  }

  // ----------------------------------------------------------------
  // URL management
  // ----------------------------------------------------------------

  /**
   * Lists all URLs with optional user filter, paginated.
   *
   * @param page zero-based page index
   * @param size page size (capped at 100)
   * @param userIdStr optional user UUID string filter
   * @param active optional active flag filter
   * @return paged URL results
   */
  @Transactional(readOnly = true)
  public PagedResult<UrlResult> listAllUrls(int page, int size, String userIdStr, Boolean active) {
    PageRequest pageable =
        PageRequest.of(page, Math.min(size, 100), Sort.by("createdAt").descending());

    Page<ShortUrl> result;
    if (userIdStr != null) {
      UUID userId = UUID.fromString(userIdStr);
      result = shortUrlRepository.findByUserIdAndDeletedAtIsNull(userId, pageable);
    } else {
      result = shortUrlRepository.findAll(pageable);
    }
    return PagedResult.of(result.map(urlUseCase::toResult));
  }

  /**
   * Admin force-deletes a URL by soft-deleting it and evicting it from cache.
   *
   * @param shortCode the short code to delete
   * @param actorId the admin performing the action
   */
  @Transactional
  public void forceDeleteUrl(String shortCode, UUID actorId) {
    ShortUrl url =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new Exceptions.UrlNotFoundException(shortCode));
    url.softDelete();
    shortUrlRepository.save(url);
    urlCacheService.evict(shortCode);
    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            actorId,
            "ADMIN",
            AuditLog.Action.URL_DELETED,
            "ShortUrl",
            url.getId(),
            auditJson.build("shortCode", shortCode, "longUrl", url.getLongUrl()),
            null,
            null,
            null));
    log.info("Admin force-deleted URL shortCode={}", shortCode);
  }

  // ----------------------------------------------------------------
  // Audit logs
  // ----------------------------------------------------------------

  /**
   * Returns audit logs with optional filters, paginated.
   *
   * @param actorId optional actor UUID filter
   * @param action optional action string filter
   * @param entityType optional entity type filter
   * @param page zero-based page index
   * @param size page size (capped at 200)
   * @return paged audit log results
   */
  @Transactional(readOnly = true)
  public PagedResult<AuditLogResult> getAuditLogs(
      UUID actorId, String action, String entityType, int page, int size) {
    PageRequest pageable =
        PageRequest.of(page, Math.min(size, 200), Sort.by("createdAt").descending());
    Instant from = Instant.now().minus(90, ChronoUnit.DAYS);
    Instant to = Instant.now();
    Page<AuditLog> logs =
        auditLogRepository.findWithFilters(actorId, action, entityType, from, to, pageable);
    return PagedResult.of(logs.map(this::toAuditLogResult));
  }

  // ----------------------------------------------------------------
  // System stats
  // ----------------------------------------------------------------

  /**
   * Returns system-wide statistics for the admin dashboard.
   *
   * @return system stats snapshot
   */
  @Transactional(readOnly = true)
  public SystemStatsResult getSystemStats() {
    long totalUsers = userRepository.count();
    long activeUsers = userRepository.countByActiveTrue();
    long totalUrls = shortUrlRepository.count();
    long totalClicksLast30d = 0L;

    return new SystemStatsResult(
        totalUsers, activeUsers, totalUrls, totalClicksLast30d, Instant.now());
  }

  // ----------------------------------------------------------------
  // Mappers
  // ----------------------------------------------------------------

  private UserResult toUserResult(User u) {
    return new UserResult(u.getId(), u.getEmail(), u.getRole().name(), u.getCreatedAt());
  }

  private AuditLogResult toAuditLogResult(AuditLog a) {
    return new AuditLogResult(
        a.getId(),
        a.getActorId(),
        a.getActorType() != null ? a.getActorType().name() : null,
        a.getAction(),
        a.getEntityType(),
        a.getEntityId(),
        a.getOldValue(),
        a.getNewValue(),
        a.getCreatedAt());
  }
}
