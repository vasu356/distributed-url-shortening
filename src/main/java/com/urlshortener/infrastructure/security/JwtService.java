package com.urlshortener.infrastructure.security;

import com.urlshortener.common.exception.Exceptions;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT token service using HS256 (HMAC-SHA256).
 *
 * <p>Token structure: - Access token: 15-minute TTL, contains userId, email, role, jti - Refresh
 * token: 7-day TTL, contains only userId and jti (minimal claims for security)
 *
 * <p>jti (JWT ID): unique per token. Stored in Redis on logout/revocation. On each authenticated
 * request, jti is checked against the revocation set — enables stateless JWT with revocation
 * support without full session storage.
 *
 * <p>HS256 vs RS256: HS256 chosen for simplicity (single key, no key distribution). RS256 would be
 * required if tokens need to be verified by a separate service (e.g., a microservice that doesn't
 * share the signing key). Currently all verification is in this service — HS256 is appropriate.
 */
@Service
@Slf4j
public class JwtService {

  private final SecretKey signingKey;
  private final long accessTokenExpiry;
  private final long refreshTokenExpiry;
  private final String issuer;

  public JwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.access-token-expiry:900}") long accessTokenExpiry,
      @Value("${app.jwt.refresh-token-expiry:604800}") long refreshTokenExpiry,
      @Value("${app.jwt.issuer:url-shortener}") String issuer) {
    this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    this.accessTokenExpiry = accessTokenExpiry;
    this.refreshTokenExpiry = refreshTokenExpiry;
    this.issuer = issuer;
  }

  public String generateAccessToken(UUID userId, String email, String role) {
    Instant now = Instant.now();
    return Jwts.builder()
        .id(UUID.randomUUID().toString()) // jti for revocation
        .issuer(issuer)
        .subject(userId.toString())
        .claim("email", email)
        .claim("role", role)
        .claim("type", "access")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(accessTokenExpiry)))
        .signWith(signingKey)
        .compact();
  }

  public String generateRefreshToken(UUID userId) {
    Instant now = Instant.now();
    return Jwts.builder()
        .id(UUID.randomUUID().toString())
        .issuer(issuer)
        .subject(userId.toString())
        .claim("type", "refresh")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(refreshTokenExpiry)))
        .signWith(signingKey)
        .compact();
  }

  public Claims extractAllClaims(String token) {
    try {
      return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    } catch (ExpiredJwtException ex) {
      throw new Exceptions.TokenExpiredException();
    } catch (JwtException ex) {
      log.debug("Invalid JWT token: {}", ex.getMessage());
      throw new Exceptions.InvalidTokenException();
    }
  }

  public String extractUserId(String token) {
    return extractAllClaims(token).getSubject();
  }

  public String extractJti(String token) {
    return extractAllClaims(token).getId();
  }

  public String extractRole(String token) {
    return extractAllClaims(token).get("role", String.class);
  }

  public String extractTokenType(String token) {
    return extractAllClaims(token).get("type", String.class);
  }

  public boolean isTokenExpired(String token) {
    try {
      return extractAllClaims(token).getExpiration().before(new Date());
    } catch (Exceptions.TokenExpiredException e) {
      return true;
    }
  }

  public boolean isAccessToken(String token) {
    return "access".equals(extractTokenType(token));
  }

  public long getAccessTokenExpiry() {
    return accessTokenExpiry;
  }

  public long getRefreshTokenExpiry() {
    return refreshTokenExpiry;
  }
}
