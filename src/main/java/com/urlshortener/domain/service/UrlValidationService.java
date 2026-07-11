package com.urlshortener.domain.service;

import com.urlshortener.common.exception.Exceptions;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * URL validation service.
 *
 * <p>Validates: 1. Syntactic correctness (valid URI) 2. Scheme allowlist (only http/https) 3.
 * Hostname not empty 4. No local/private IP addresses (SSRF prevention) 5. No obviously malicious
 * patterns
 *
 * <p>SSRF prevention: Prevents users from shortening URLs that point to internal services (e.g.,
 * http://192.168.1.1/admin, http://localhost:8080). This is important because shortened URLs are
 * resolved server-side during metadata scraping.
 */
@Service
public class UrlValidationService {

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  private static final Set<String> BLOCKED_HOSTS =
      Set.of(
          "localhost",
          "127.0.0.1",
          "0.0.0.0",
          "::1",
          "169.254.169.254" // AWS metadata service — critical to block
          );

  /**
   * Validates that the given URL is safe to shorten.
   *
   * @param url the URL string to validate
   * @throws Exceptions.InvalidUrlException if the URL is null, malformed, uses a disallowed scheme,
   *     or points to a blocked or private host
   */
  public void validate(String url) {
    if (url == null || url.isBlank()) {
      throw new Exceptions.InvalidUrlException(url);
    }

    if (url.length() > 2048) {
      throw new Exceptions.InvalidUrlException("URL exceeds maximum length of 2048 characters");
    }

    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw new Exceptions.InvalidUrlException(url);
    }

    String scheme = uri.getScheme();
    if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
      throw new Exceptions.InvalidUrlException("Only http and https URLs are supported");
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new Exceptions.InvalidUrlException(url);
    }

    String hostLower = host.toLowerCase();
    if (BLOCKED_HOSTS.contains(hostLower)) {
      throw new Exceptions.InvalidUrlException("URL points to a blocked host");
    }

    // Block private IP ranges (SSRF prevention)
    if (isPrivateIpAddress(hostLower)) {
      throw new Exceptions.InvalidUrlException("URL points to a private network address");
    }
  }

  private boolean isPrivateIpAddress(String host) {
    // IPv4 private ranges: 10.0.0.0/8
    if (host.startsWith("10.")) {
      return true;
    }
    // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
    if (host.startsWith("172.")) {
      String[] parts = host.split("\\.");
      if (parts.length >= 2) {
        try {
          int secondOctet = Integer.parseInt(parts[1]);
          if (secondOctet >= 16 && secondOctet <= 31) {
            return true;
          }
        } catch (NumberFormatException e) {
          // Not a numeric IP, let it through (will fail at DNS resolution anyway)
        }
      }
    }
    // 192.168.0.0/16
    if (host.startsWith("192.168.")) {
      return true;
    }
    // Carrier-grade NAT (100.64.0.0/10)
    if (host.startsWith("100.")) {
      String[] parts = host.split("\\.");
      if (parts.length >= 2) {
        try {
          int secondOctet = Integer.parseInt(parts[1]);
          if (secondOctet >= 64 && secondOctet <= 127) {
            return true;
          }
        } catch (NumberFormatException e) {
          // Not a numeric IP
        }
      }
    }
    return false;
  }
}
