package com.urlshortener.application.usecase;

import com.urlshortener.application.dto.request.ApiKeyCommands;
import com.urlshortener.application.dto.response.ApiKeyCreatedResult;
import com.urlshortener.application.dto.response.ApiKeyResult;
import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.domain.model.ApiKey;
import com.urlshortener.domain.model.User;
import com.urlshortener.domain.repository.ApiKeyRepository;
import com.urlshortener.domain.repository.UserRepository;
import com.urlshortener.infrastructure.kafka.producer.AuditJsonBuilder;
import com.urlshortener.infrastructure.kafka.producer.EventProducer;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API key management use case.
 *
 * <p>Handles creation, listing, and revocation of API keys. Raw keys are shown once at creation and
 * never stored; only SHA-256 hashes are persisted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyUseCase {

  private static final int MAX_API_KEYS_PER_USER = 10;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final ApiKeyRepository apiKeyRepository;
  private final UserRepository userRepository;
  private final EventProducer eventProducer;
  private final AuditJsonBuilder auditJson;

  /**
   * Creates a new API key for the given user.
   *
   * @param command the creation command including name and optional scopes
   * @param userId the owning user's UUID
   * @return the created key result including the raw key (shown only once)
   */
  @Transactional
  public ApiKeyCreatedResult createApiKey(ApiKeyCommands.CreateApiKeyCommand command, UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new Exceptions.UserNotFoundException(userId.toString()));

    long existingKeys = apiKeyRepository.countByUserIdAndRevokedFalse(userId);
    if (existingKeys >= MAX_API_KEYS_PER_USER) {
      throw new Exceptions.BulkImportException(
          "Maximum " + MAX_API_KEYS_PER_USER + " active API keys allowed per user");
    }

    // Generate raw key: "sk_" + Base64(32 random bytes)
    byte[] keyBytes = new byte[32];
    SECURE_RANDOM.nextBytes(keyBytes);
    String rawKey = "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);

    String keyHash = sha256Hex(rawKey);

    List<String> scopes =
        (command.scopes() == null || command.scopes().isEmpty())
            ? List.of("read", "write", "analytics")
            : command.scopes();

    ApiKey apiKey = ApiKey.create(user, keyHash, command.name(), scopes);
    if (command.expiresAt() != null) {
      apiKey.setExpiresAt(command.expiresAt());
    }
    if (command.rateLimit() != null) {
      apiKey.setRateLimit(command.rateLimit());
    }

    apiKey = apiKeyRepository.save(apiKey);

    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            userId,
            "USER",
            "API_KEY_CREATED",
            "ApiKey",
            apiKey.getId(),
            null,
            auditJson.build("name", command.name(), "scopes", scopes),
            null,
            null));

    log.info("API key created for userId={} name={}", userId, command.name());

    return new ApiKeyCreatedResult(
        apiKey.getId(),
        apiKey.getName(),
        rawKey,
        scopes,
        apiKey.getRateLimit(),
        apiKey.getExpiresAt(),
        apiKey.getCreatedAt());
  }

  /**
   * Returns all API keys (active and revoked) for the given user.
   *
   * @param userId the user's UUID
   * @return list of API key result DTOs (raw key hash is masked)
   */
  @Transactional(readOnly = true)
  public List<ApiKeyResult> listApiKeys(UUID userId) {
    return apiKeyRepository.findByUserId(userId).stream().map(this::toResult).toList();
  }

  /**
   * Revokes an API key owned by the given user.
   *
   * @param apiKeyId the API key's UUID
   * @param userId the requesting user's UUID (must be the owner)
   */
  @Transactional
  public void revokeApiKey(UUID apiKeyId, UUID userId) {
    ApiKey apiKey =
        apiKeyRepository
            .findById(apiKeyId)
            .orElseThrow(() -> new Exceptions.ApiKeyNotFoundException());

    if (!apiKey.getUser().getId().equals(userId)) {
      throw new Exceptions.UnauthorizedAccessException();
    }

    apiKey.revoke();
    apiKeyRepository.save(apiKey);

    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            userId, "USER", "API_KEY_REVOKED", "ApiKey", apiKeyId, null, null, null, null));

    log.info("API key revoked: apiKeyId={} userId={}", apiKeyId, userId);
  }

  private ApiKeyResult toResult(ApiKey key) {
    return new ApiKeyResult(
        key.getId(),
        key.getName(),
        key.getKeyHash().substring(0, 8) + "...", // show prefix only
        key.getScopes(),
        key.getRateLimit(),
        key.getExpiresAt(),
        key.getLastUsedAt(),
        key.getCreatedAt());
  }

  private String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
