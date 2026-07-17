package com.urlshortener.application.usecase;

import com.urlshortener.application.dto.request.AuthCommands;
import com.urlshortener.application.dto.response.AuthResult;
import com.urlshortener.application.dto.response.UserResult;
import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.domain.model.User;
import com.urlshortener.domain.repository.UserRepository;
import com.urlshortener.infrastructure.kafka.producer.AuditJsonBuilder;
import com.urlshortener.infrastructure.kafka.producer.EventProducer;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import com.urlshortener.infrastructure.security.JwtService;
import com.urlshortener.infrastructure.security.TokenBlacklistService;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication use case.
 *
 * <p>Handles user registration, login, token refresh, and logout. JWT tokens are issued and
 * validated here; revocation is managed via Redis blacklist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthUseCase {

  private final UserRepository userRepository;
  private final JwtService jwtService;
  private final PasswordEncoder passwordEncoder;
  private final TokenBlacklistService tokenBlacklistService;
  private final EventProducer eventProducer;
  private final AuditJsonBuilder auditJson;

  /**
   * Registers a new user and returns auth tokens.
   *
   * @param command the registration command containing email and password
   * @return auth result with access and refresh tokens
   */
  @Transactional
  public AuthResult register(AuthCommands.RegisterCommand command) {
    String email = command.email().toLowerCase().trim();
    if (userRepository.existsByEmail(email)) {
      throw new Exceptions.UserAlreadyExistsException(email);
    }
    String passwordHash = passwordEncoder.encode(command.password());
    User user = User.create(email, passwordHash);
    user = userRepository.save(user);
    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            user.getId(),
            "USER",
            "USER_REGISTERED",
            "User",
            user.getId(),
            null,
            auditJson.build("email", email, "role", user.getRole().name(), "userId", user.getId()),
            null,
            null));
    log.info("New user registered: email={}", email);
    return buildAuthResult(user);
  }

  /**
   * Authenticates a user and returns auth tokens.
   *
   * @param command the login command
   * @return auth result with access and refresh tokens
   */
  @Transactional(readOnly = true)
  public AuthResult login(AuthCommands.LoginCommand command) {
    String email = command.email().toLowerCase().trim();
    User user =
        userRepository.findByEmail(email).orElseThrow(Exceptions.InvalidCredentialsException::new);
    if (!user.isActive()) {
      throw new Exceptions.AccountDeactivatedException();
    }
    if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
      eventProducer.publishAuditEvent(
          KafkaEvents.AuditEvent.of(
              user.getId(),
              "USER",
              "USER_LOGIN_FAILED",
              "User",
              user.getId(),
              null,
              auditJson.build("email", email, "userId", user.getId()),
              null,
              null));
      throw new Exceptions.InvalidCredentialsException();
    }
    eventProducer.publishAuditEvent(
        KafkaEvents.AuditEvent.of(
            user.getId(),
            "USER",
            "USER_LOGIN",
            "User",
            user.getId(),
            null,
            auditJson.build("email", email, "userId", user.getId()),
            null,
            null));
    log.info("User login: email={}", email);
    return buildAuthResult(user);
  }

  /**
   * Exchanges a valid refresh token for new access and refresh tokens.
   *
   * @param refreshToken the refresh token from a prior login
   * @return new auth result
   */
  @Transactional(readOnly = true)
  public AuthResult refresh(String refreshToken) {
    if (!"refresh".equals(jwtService.extractTokenType(refreshToken))) {
      throw new Exceptions.InvalidTokenException();
    }
    String userId = jwtService.extractUserId(refreshToken);
    String jti = jwtService.extractJti(refreshToken);
    if (tokenBlacklistService.isBlacklisted(jti)) {
      throw new Exceptions.InvalidTokenException();
    }
    User user =
        userRepository
            .findById(UUID.fromString(userId))
            .orElseThrow(() -> new Exceptions.UserNotFoundException(userId));
    if (!user.isActive()) {
      throw new Exceptions.AccountDeactivatedException();
    }
    tokenBlacklistService.blacklist(jti, Duration.ofSeconds(jwtService.getRefreshTokenExpiry()));
    return buildAuthResult(user);
  }

  /**
   * Invalidates the given access token by adding its jti to the Redis blacklist.
   *
   * @param accessToken the raw access token string
   */
  public void logout(String accessToken) {
    try {
      String jti = jwtService.extractJti(accessToken);
      tokenBlacklistService.blacklist(jti, Duration.ofSeconds(jwtService.getAccessTokenExpiry()));
      log.debug("Token blacklisted on logout jti={}", jti);
    } catch (Exception ex) {
      // Token already expired — logout is a no-op
    }
  }

  /**
   * Returns the current authenticated user's profile.
   *
   * @param userId the authenticated user's UUID
   * @return the user result DTO
   */
  @Transactional(readOnly = true)
  public UserResult getCurrentUser(UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new Exceptions.UserNotFoundException(userId.toString()));
    return new UserResult(
        user.getId(), user.getEmail(), user.getRole().name(), user.getCreatedAt());
  }

  private AuthResult buildAuthResult(User user) {
    String accessToken =
        jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
    String refreshToken = jwtService.generateRefreshToken(user.getId());
    return new AuthResult(
        accessToken,
        "Bearer",
        jwtService.getAccessTokenExpiry(),
        refreshToken,
        new UserResult(user.getId(), user.getEmail(), user.getRole().name(), user.getCreatedAt()));
  }
}
