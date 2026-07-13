package com.urlshortener.api.v1.controller;

import com.urlshortener.api.v1.dto.request.UrlDtos;
import com.urlshortener.api.v1.dto.response.BulkCreateResponse;
import com.urlshortener.api.v1.dto.response.PagedResponse;
import com.urlshortener.api.v1.dto.response.UrlResponse;
import com.urlshortener.application.usecase.UrlUseCase;
import com.urlshortener.infrastructure.http.QrCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * URL management endpoints.
 *
 * <p>Handles creation, retrieval, update, deletion, bulk operations, and QR code generation for
 * short URLs. All endpoints require authentication.
 */
@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
@Tag(name = "URLs", description = "URL shortening operations")
@SecurityRequirement(name = "bearerAuth")
public class UrlController {

  private static final Set<String> ALLOWED_SORT_FIELDS =
      Set.of("createdAt", "updatedAt", "clickCount", "shortCode");

  private final UrlUseCase urlUseCase;
  private final QrCodeService qrCodeService;

  @PostMapping
  @Operation(summary = "Create a short URL")
  public ResponseEntity<UrlResponse> create(
      @Valid @RequestBody UrlDtos.CreateUrlRequest request,
      @AuthenticationPrincipal String userId) {

    UrlResponse response = urlUseCase.createUrl(request, UUID.fromString(userId));
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{shortCode}")
  @Operation(summary = "Get URL details")
  public ResponseEntity<UrlResponse> get(
      @PathVariable String shortCode, @AuthenticationPrincipal String userId) {

    return ResponseEntity.ok(urlUseCase.getUrl(shortCode, UUID.fromString(userId)));
  }

  @GetMapping
  @Operation(summary = "List user's URLs with pagination")
  public ResponseEntity<PagedResponse<UrlResponse>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt") String sortBy,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(required = false) String search,
      @AuthenticationPrincipal String userId) {

    if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid sortBy value '" + sortBy + "'. Allowed: " + ALLOWED_SORT_FIELDS);
    }

    Sort sort =
        "asc".equalsIgnoreCase(direction)
            ? Sort.by(sortBy).ascending()
            : Sort.by(sortBy).descending();
    PageRequest pageable = PageRequest.of(page, Math.min(size, 100), sort);

    PagedResponse<UrlResponse> result =
        (search != null && !search.isBlank())
            ? urlUseCase.searchUserUrls(UUID.fromString(userId), search, pageable)
            : urlUseCase.getUserUrls(UUID.fromString(userId), pageable);

    return ResponseEntity.ok(result);
  }

  @PatchMapping("/{shortCode}")
  @Operation(summary = "Update a URL")
  public ResponseEntity<UrlResponse> update(
      @PathVariable String shortCode,
      @Valid @RequestBody UrlDtos.UpdateUrlRequest request,
      @AuthenticationPrincipal String userId) {

    return ResponseEntity.ok(urlUseCase.updateUrl(shortCode, request, UUID.fromString(userId)));
  }

  @DeleteMapping("/{shortCode}")
  @Operation(summary = "Soft-delete a URL")
  public ResponseEntity<Void> delete(
      @PathVariable String shortCode, @AuthenticationPrincipal String userId) {

    urlUseCase.deleteUrl(shortCode, UUID.fromString(userId));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/bulk")
  @Operation(summary = "Create multiple URLs (max 1000)")
  public ResponseEntity<BulkCreateResponse> bulkCreate(
      @Valid @RequestBody UrlDtos.BulkCreateRequest request,
      @AuthenticationPrincipal String userId) {

    return ResponseEntity.status(HttpStatus.MULTI_STATUS)
        .body(urlUseCase.bulkCreate(request, UUID.fromString(userId)));
  }

  @GetMapping("/{shortCode}/qr")
  @Operation(summary = "Get QR code for a short URL")
  public ResponseEntity<byte[]> getQrCode(
      @PathVariable String shortCode,
      @RequestParam(defaultValue = "300") int size,
      @AuthenticationPrincipal String userId) {

    // Verify URL exists and belongs to user
    urlUseCase.getUrl(shortCode, UUID.fromString(userId));

    byte[] qrBytes = qrCodeService.getOrGenerate(shortCode, size);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
        .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
        .body(qrBytes);
  }
}
