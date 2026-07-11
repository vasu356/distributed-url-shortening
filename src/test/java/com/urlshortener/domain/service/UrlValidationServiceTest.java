package com.urlshortener.domain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.exception.Exceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("UrlValidationService")
class UrlValidationServiceTest {

  private UrlValidationService service;

  @BeforeEach
  void setUp() {
    service = new UrlValidationService();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://www.google.com",
        "http://example.com/path?query=1",
        "https://github.com/user/repo",
        "https://subdomain.example.co.uk/path/to/resource",
        "http://example.com:8080/api",
      })
  @DisplayName("accepts valid public URLs")
  void validate_acceptsValidUrls(String url) {
    assertThatCode(() -> service.validate(url)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://example.com",
        "javascript:alert(1)",
        "file:///etc/passwd",
        "data:text/html,<script>alert(1)</script>",
      })
  @DisplayName("rejects disallowed schemes")
  void validate_rejectsBadSchemes(String url) {
    assertThatThrownBy(() -> service.validate(url))
        .isInstanceOf(Exceptions.InvalidUrlException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost",
        "http://localhost:8080/admin",
        "http://127.0.0.1",
        "http://0.0.0.0",
        "http://192.168.1.1",
        "http://10.0.0.1",
        "http://172.16.0.1",
        "http://169.254.169.254", // AWS metadata — critical
        "http://169.254.169.254/latest/meta-data/",
      })
  @DisplayName("blocks SSRF targets (private/local addresses)")
  void validate_blocksSSRFTargets(String url) {
    assertThatThrownBy(() -> service.validate(url))
        .isInstanceOf(Exceptions.InvalidUrlException.class);
  }

  @Test
  @DisplayName("rejects null URL")
  void validate_rejectsNull() {
    assertThatThrownBy(() -> service.validate(null))
        .isInstanceOf(Exceptions.InvalidUrlException.class);
  }

  @Test
  @DisplayName("rejects blank URL")
  void validate_rejectsBlank() {
    assertThatThrownBy(() -> service.validate("  "))
        .isInstanceOf(Exceptions.InvalidUrlException.class);
  }

  @Test
  @DisplayName("rejects URL exceeding 2048 characters")
  void validate_rejectsTooLong() {
    String longUrl = "https://example.com/" + "a".repeat(2048);
    assertThatThrownBy(() -> service.validate(longUrl))
        .isInstanceOf(Exceptions.InvalidUrlException.class);
  }

  @Test
  @DisplayName("rejects URL with no host")
  void validate_rejectsNoHost() {
    assertThatThrownBy(() -> service.validate("https:///path"))
        .isInstanceOf(Exceptions.InvalidUrlException.class);
  }
}
