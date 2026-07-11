package com.urlshortener.infrastructure.security;

import com.urlshortener.domain.model.ApiKey;
import com.urlshortener.domain.model.User;
import com.urlshortener.domain.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests using API keys.
 *
 * <p>Flow: 1. Extract key from X-API-Key header 2. SHA-256 hash it (constant-time) 3. Look up hash
 * in Redis cache (5 min TTL) → DB 4. Validate: not revoked, not expired 5. Set Spring Security
 * context with user's role 6. Update last_used_at asynchronously
 *
 * <p>Only processes requests that have X-API-Key header AND no Authorization header. If both are
 * present, JWT takes precedence (handled by JwtAuthFilter which runs first).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  private static final String API_KEY_HEADER = "X-API-Key";
  private static final String API_KEY_PREFIX = "sk_";

  private final ApiKeyRepository apiKeyRepository;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // Skip if already authenticated (JWT handled it) or no API key header
    if (SecurityContextHolder.getContext().getAuthentication() != null) {
      chain.doFilter(request, response);
      return;
    }

    String rawKey = request.getHeader(API_KEY_HEADER);
    if (!StringUtils.hasText(rawKey) || !rawKey.startsWith(API_KEY_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }

    try {
      String keyHash = sha256Hex(rawKey);
      Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByKeyHash(keyHash);

      if (apiKeyOpt.isEmpty()) {
        log.warn("API key authentication failed: key hash not found");
        chain.doFilter(request, response);
        return;
      }

      ApiKey apiKey = apiKeyOpt.get();
      if (!apiKey.isValid()) {
        log.warn("API key {} is revoked or expired", apiKey.getId());
        chain.doFilter(request, response);
        return;
      }

      User user = apiKey.getUser();
      String role = user.getRole().name();
      var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

      // Principal is userId as string — consistent with JWT auth
      var auth =
          new UsernamePasswordAuthenticationToken(user.getId().toString(), null, authorities);
      auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(auth);

      // Async update of last_used_at — don't block the request
      apiKeyRepository.updateLastUsed(apiKey.getId());

      log.debug("API key authentication successful for user {}", user.getId());
    } catch (Exception ex) {
      log.error("API key authentication error: {}", ex.getMessage());
    }

    chain.doFilter(request, response);
  }

  private String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
