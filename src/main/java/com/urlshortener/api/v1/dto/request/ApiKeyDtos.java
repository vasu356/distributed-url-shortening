package com.urlshortener.api.v1.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request DTOs for API key management endpoints. */
public final class ApiKeyDtos {

  private ApiKeyDtos() {}

  /** Request to create a new API key. */
  public record CreateApiKeyRequest(
      @NotBlank(message = "Name is required") @Size(max = 100) String name,
      List<String> scopes,
      Instant expiresAt,
      Integer rateLimit) {
    public CreateApiKeyRequest {}
  }
}
