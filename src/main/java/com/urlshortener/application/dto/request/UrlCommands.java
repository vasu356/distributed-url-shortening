package com.urlshortener.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.hibernate.validator.constraints.URL;

/**
 * Application-layer command objects for URL shortening use cases.
 *
 * <p>These live in the application layer because use cases define their own input ports. The API
 * layer (controllers) uses these types directly, keeping the dependency direction correct: API →
 * Application, never Application → API.
 */
public final class UrlCommands {

  private UrlCommands() {}

  /** Command to create a single short URL. */
  public record CreateUrlCommand(
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
    public CreateUrlCommand {}
  }

  /** Command to update an existing short URL. */
  public record UpdateUrlCommand(
      @Size(max = 2048) @URL String longUrl,
      Instant expiresAt,
      Boolean active,
      Integer redirectType,
      Long maxClicks) {
    public UpdateUrlCommand {}
  }

  /** Command to create multiple short URLs in a single call. */
  public record BulkCreateCommand(
      @NotNull @Size(min = 1, max = 1000, message = "Bulk request must contain 1-1000 URLs")
          List<CreateUrlCommand> urls) {
    public BulkCreateCommand {}
  }
}
