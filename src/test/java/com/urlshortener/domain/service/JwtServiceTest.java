package com.urlshortener.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.infrastructure.security.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtService")
class JwtServiceTest {

  // 32-byte Base64-encoded secret (minimum for HS256)
  private static final String TEST_SECRET = "HV7r27IXkY4KYKV6ZlKb/FqO+9ekcCsBXY45p++1zhQ=";

  private JwtService jwtService;

  @BeforeEach
  void setUp() {
    jwtService = new JwtService(TEST_SECRET, 900L, 604800L, "url-shortener-test");
  }

  @Test
  @DisplayName("access token contains correct claims")
  void generateAccessToken_hasCorrectClaims() {
    UUID userId = UUID.randomUUID();
    String email = "test@example.com";
    String role = "USER";

    String token = jwtService.generateAccessToken(userId, email, role);

    assertThat(jwtService.extractUserId(token)).isEqualTo(userId.toString());
    assertThat(jwtService.extractRole(token)).isEqualTo(role);
    assertThat(jwtService.extractTokenType(token)).isEqualTo("access");
    assertThat(jwtService.extractJti(token)).isNotBlank();
  }

  @Test
  @DisplayName("refresh token has type 'refresh'")
  void generateRefreshToken_hasRefreshType() {
    UUID userId = UUID.randomUUID();
    String token = jwtService.generateRefreshToken(userId);
    assertThat(jwtService.extractTokenType(token)).isEqualTo("refresh");
    assertThat(jwtService.extractUserId(token)).isEqualTo(userId.toString());
  }

  @Test
  @DisplayName("isAccessToken returns true for access tokens only")
  void isAccessToken_discriminates() {
    UUID userId = UUID.randomUUID();
    String access = jwtService.generateAccessToken(userId, "test@test.com", "USER");
    String refresh = jwtService.generateRefreshToken(userId);

    assertThat(jwtService.isAccessToken(access)).isTrue();
    assertThat(jwtService.isAccessToken(refresh)).isFalse();
  }

  @Test
  @DisplayName("each token gets a unique jti")
  void generateAccessToken_uniqueJti() {
    UUID userId = UUID.randomUUID();
    String t1 = jwtService.generateAccessToken(userId, "a@b.com", "USER");
    String t2 = jwtService.generateAccessToken(userId, "a@b.com", "USER");
    assertThat(jwtService.extractJti(t1)).isNotEqualTo(jwtService.extractJti(t2));
  }

  @Test
  @DisplayName("invalid token throws InvalidTokenException")
  void extractClaims_invalidToken_throws() {
    assertThatThrownBy(() -> jwtService.extractUserId("not.a.jwt.token"))
        .isInstanceOf(Exceptions.InvalidTokenException.class);
  }

  @Test
  @DisplayName("tampered token signature throws InvalidTokenException")
  void extractClaims_tamperedToken_throws() {
    UUID userId = UUID.randomUUID();
    String token = jwtService.generateAccessToken(userId, "a@b.com", "USER");
    // Corrupt the signature (last segment)
    String[] parts = token.split("\\.");
    String tampered = parts[0] + "." + parts[1] + ".invalidsignature";
    assertThatThrownBy(() -> jwtService.extractUserId(tampered))
        .isInstanceOf(Exceptions.InvalidTokenException.class);
  }

  @Test
  @DisplayName("expired token throws TokenExpiredException")
  void extractClaims_expiredToken_throws() {
    // Token with 1-second expiry
    JwtService shortLivedService = new JwtService(TEST_SECRET, 1L, 1L, "test");
    UUID userId = UUID.randomUUID();
    String token = shortLivedService.generateAccessToken(userId, "a@b.com", "USER");

    // Wait for token to expire
    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThatThrownBy(() -> shortLivedService.extractUserId(token))
        .isInstanceOf(Exceptions.TokenExpiredException.class);
  }
}
