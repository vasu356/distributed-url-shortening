package com.urlshortener.api.v1.controller;

import com.urlshortener.api.v1.dto.request.ApiKeyDtos;
import com.urlshortener.api.v1.dto.response.ApiKeyCreatedResponse;
import com.urlshortener.api.v1.dto.response.ApiKeyResponse;
import com.urlshortener.application.usecase.ApiKeyUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API key management endpoints.
 *
 * <p>Allows authenticated users to create, list, and revoke their API keys. The raw key is returned
 * only on creation and never again.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
@Tag(name = "API Keys", description = "Manage programmatic access credentials")
@SecurityRequirement(name = "bearerAuth")
public class ApiKeyController {

  private final ApiKeyUseCase apiKeyUseCase;

  @PostMapping
  @Operation(
      summary = "Create a new API key",
      description = "The raw key is returned once and never retrievable again.")
  public ResponseEntity<ApiKeyCreatedResponse> create(
      @Valid @RequestBody ApiKeyDtos.CreateApiKeyRequest request,
      @AuthenticationPrincipal String userId) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(apiKeyUseCase.createApiKey(request, UUID.fromString(userId)));
  }

  @GetMapping
  @Operation(summary = "List all API keys for the authenticated user")
  public ResponseEntity<List<ApiKeyResponse>> list(@AuthenticationPrincipal String userId) {
    return ResponseEntity.ok(apiKeyUseCase.listApiKeys(UUID.fromString(userId)));
  }

  @DeleteMapping("/{apiKeyId}")
  @Operation(summary = "Revoke an API key")
  public ResponseEntity<Void> revoke(
      @PathVariable UUID apiKeyId, @AuthenticationPrincipal String userId) {
    apiKeyUseCase.revokeApiKey(apiKeyId, UUID.fromString(userId));
    return ResponseEntity.noContent().build();
  }
}
