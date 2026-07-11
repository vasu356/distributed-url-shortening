package com.urlshortener.api.v1.controller;

import com.urlshortener.api.v1.dto.response.AuditLogResponse;
import com.urlshortener.api.v1.dto.response.PagedResponse;
import com.urlshortener.api.v1.dto.response.SystemStatsResponse;
import com.urlshortener.api.v1.dto.response.UrlResponse;
import com.urlshortener.api.v1.dto.response.UserResponse;
import com.urlshortener.application.usecase.AdminUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints — privileged operations on users, URLs, and audit logs.
 *
 * <p>All methods require {@code ROLE_ADMIN} enforced via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Administrative operations (ADMIN role required)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

  private final AdminUseCase adminUseCase;

  @GetMapping("/users")
  @Operation(summary = "List all users (paginated)")
  public ResponseEntity<PagedResponse<UserResponse>> listUsers(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String search) {
    return ResponseEntity.ok(adminUseCase.listUsers(page, size, search));
  }

  @GetMapping("/users/{userId}")
  @Operation(summary = "Get a specific user by ID")
  public ResponseEntity<UserResponse> getUser(@PathVariable UUID userId) {
    return ResponseEntity.ok(adminUseCase.getUser(userId));
  }

  @PatchMapping("/users/{userId}/deactivate")
  @Operation(summary = "Deactivate a user account")
  public ResponseEntity<Void> deactivateUser(
      @PathVariable UUID userId, @AuthenticationPrincipal String actorId) {
    adminUseCase.deactivateUser(userId, actorId != null ? UUID.fromString(actorId) : null);
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/users/{userId}/promote")
  @Operation(summary = "Promote a user to ADMIN role")
  public ResponseEntity<UserResponse> promoteUser(@PathVariable UUID userId) {
    return ResponseEntity.ok(adminUseCase.promoteToAdmin(userId));
  }

  @GetMapping("/urls")
  @Operation(summary = "List all URLs in the system (paginated)")
  public ResponseEntity<PagedResponse<UrlResponse>> listAllUrls(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String userId,
      @RequestParam(required = false) Boolean active) {
    return ResponseEntity.ok(adminUseCase.listAllUrls(page, size, userId, active));
  }

  @DeleteMapping("/urls/{shortCode}")
  @Operation(summary = "Admin force-delete a URL")
  public ResponseEntity<Void> forceDeleteUrl(
      @PathVariable String shortCode, @AuthenticationPrincipal String actorId) {
    adminUseCase.forceDeleteUrl(shortCode, actorId != null ? UUID.fromString(actorId) : null);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/audit-logs")
  @Operation(summary = "Query audit logs with filters")
  public ResponseEntity<PagedResponse<AuditLogResponse>> getAuditLogs(
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String entityType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ResponseEntity.ok(adminUseCase.getAuditLogs(actorId, action, entityType, page, size));
  }

  @GetMapping("/stats")
  @Operation(summary = "System-wide statistics")
  public ResponseEntity<SystemStatsResponse> getStats() {
    return ResponseEntity.ok(adminUseCase.getSystemStats());
  }
}
