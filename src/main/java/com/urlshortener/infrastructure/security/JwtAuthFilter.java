package com.urlshortener.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String AUTH_HEADER = "Authorization";
  private static final String REFRESH_COOKIE = "refreshToken";

  private final JwtService jwtService;
  private final TokenBlacklistService tokenBlacklistService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String token = extractToken(request);
    if (token == null) {
      chain.doFilter(request, response);
      return;
    }

    try {
      if (!jwtService.isAccessToken(token)) {
        chain.doFilter(request, response);
        return;
      }

      String jti = jwtService.extractJti(token);
      if (tokenBlacklistService.isBlacklisted(jti)) {
        log.warn("Attempt to use blacklisted token jti={}", jti);
        chain.doFilter(request, response);
        return;
      }

      String userId = jwtService.extractUserId(token);
      String role = jwtService.extractRole(token);

      var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
      var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
      auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(auth);

      // Enrich MDC for log correlation
      MDC.put("userId", userId);

    } catch (Exception ex) {
      // Don't block the request — let Spring Security decide based on endpoint permissions
      log.debug("JWT validation failed: {}", ex.getMessage());
    }

    chain.doFilter(request, response);
  }

  private String extractToken(HttpServletRequest request) {
    String header = request.getHeader(AUTH_HEADER);
    if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}
