package com.urlshortener.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Application-layer command objects for API key management use cases. */
public final class ApiKeyCommands {

  private ApiKeyCommands() {}

  /** Command to create a new API key. */
  public record CreateApiKeyCommand(
      @NotBlank(message = "Name is required") @Size(max = 100) String name,
      List<String> scopes,
      Instant expiresAt,
      Integer rateLimit) {
    public CreateApiKeyCommand {}
  }
}
