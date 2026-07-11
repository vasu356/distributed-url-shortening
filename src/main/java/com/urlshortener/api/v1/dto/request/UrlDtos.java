package com.urlshortener.api.v1.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.hibernate.validator.constraints.URL;

/** Request DTOs for URL shortening endpoints — immutable records with validation annotations. */
public final class UrlDtos {

  private UrlDtos() {}

  /** Request to create a single short URL. */
  public record CreateUrlRequest(
      @NotBlank(message = "URL is required")
          @Size(max = 2048, message = "URL must not exceed 2048 characters")
          @URL(message = "Must be a valid URL")
          String longUrl,
      @Size(min = 3, max = 50, message = "Alias must be between 3 and 50 characters")
          @Pattern(
              regexp = "^[a-zA-Z0-9_-]*$",
              message = "Alias may only contain letters, digits, hyphens, and underscores")
          String customAlias,
      Instant expiresAt,
      Integer redirectType,
      Long maxClicks,
      String password) {
    public CreateUrlRequest {}
  }

  /** Request to update an existing short URL. */
  public record UpdateUrlRequest(
      @Size(max = 2048) @URL String longUrl,
      Instant expiresAt,
      Boolean active,
      Integer redirectType,
      Long maxClicks) {
    public UpdateUrlRequest {}
  }

  /** Request to create multiple short URLs in a single call. */
  public record BulkCreateRequest(
      @NotNull @Size(min = 1, max = 1000, message = "Bulk request must contain 1-1000 URLs")
          List<CreateUrlRequest> urls) {
    public BulkCreateRequest {}
  }
}
