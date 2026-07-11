package com.urlshortener.api.v1.controller;

import com.urlshortener.api.v1.dto.response.BulkCreateResponse;
import com.urlshortener.application.usecase.CsvUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV import/export endpoints.
 *
 * <p>Provides bulk URL creation via CSV upload and full URL export as a downloadable CSV file.
 */
@RestController
@RequestMapping("/api/v1/csv")
@RequiredArgsConstructor
@Tag(name = "CSV", description = "Bulk import and export of URLs")
@SecurityRequirement(name = "bearerAuth")
public class CsvController {

  private final CsvUseCase csvUseCase;

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(
      summary = "Import URLs from a CSV file",
      description = "CSV format: longUrl,customAlias (alias optional). Max 1000 rows.")
  public ResponseEntity<BulkCreateResponse> importCsv(
      @RequestPart("file") MultipartFile file, @AuthenticationPrincipal String userId) {
    return ResponseEntity.ok(csvUseCase.importCsv(file, UUID.fromString(userId)));
  }

  @GetMapping("/export")
  @Operation(summary = "Export all user URLs as a CSV file")
  public ResponseEntity<byte[]> exportCsv(@AuthenticationPrincipal String userId) {
    byte[] csvBytes = csvUseCase.exportCsv(UUID.fromString(userId));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("urls-export.csv").build().toString())
        .body(csvBytes);
  }
}
