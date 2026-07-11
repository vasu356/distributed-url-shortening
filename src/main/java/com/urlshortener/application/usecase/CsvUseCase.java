package com.urlshortener.application.usecase;

import com.urlshortener.api.v1.dto.request.UrlDtos;
import com.urlshortener.api.v1.dto.response.BulkCreateResponse;
import com.urlshortener.api.v1.dto.response.BulkError;
import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.domain.model.ShortUrl;
import com.urlshortener.domain.repository.ShortUrlRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV import/export use case.
 *
 * <p>Import format (header row required): {@code longUrl,customAlias,expiresAt,redirectType} {@code
 * https://example.com,my-alias,,302}
 *
 * <p>Export format: all user URLs with full metadata.
 *
 * <p>Security: CSV injection prevention — values containing {@code =,-,+,@} are prefixed with a tab
 * character when exporting so spreadsheet applications do not evaluate them as formulas.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsvUseCase {

  private static final int MAX_CSV_ROWS = 1000;
  private static final int EXPORT_PAGE_SIZE = 500;

  private final UrlUseCase urlUseCase;
  private final ShortUrlRepository shortUrlRepository;

  /**
   * Parses and imports a CSV file of URLs for the given user.
   *
   * @param file the uploaded CSV file
   * @param userId the authenticated user's UUID
   * @return a summary of created URLs and errors
   */
  public BulkCreateResponse importCsv(MultipartFile file, UUID userId) {
    if (file.isEmpty()) {
      throw new Exceptions.BulkImportException("CSV file is empty");
    }
    if (file.getSize() > 5 * 1024 * 1024) { // 5 MB limit
      throw new Exceptions.BulkImportException("CSV file exceeds maximum size of 5MB");
    }

    List<UrlDtos.CreateUrlRequest> requests = new ArrayList<>();
    List<BulkError> parseErrors = new ArrayList<>();

    try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
        CSVParser parser =
            CSVFormat.DEFAULT
                .builder()
                .setHeader("longUrl", "customAlias", "expiresAt", "redirectType")
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(reader)) {

      int rowIndex = 0;
      for (CSVRecord record : parser) {
        if (rowIndex >= MAX_CSV_ROWS) {
          parseErrors.add(
              new BulkError(rowIndex, "", "Exceeded maximum of " + MAX_CSV_ROWS + " rows"));
          break;
        }

        try {
          String longUrl = record.get("longUrl");
          if (longUrl == null || longUrl.isBlank()) {
            parseErrors.add(new BulkError(rowIndex, "", "longUrl is required"));
            rowIndex++;
            continue;
          }

          String alias = getColumnSafe(record, "customAlias");
          String expiresAtStr = getColumnSafe(record, "expiresAt");
          String redirectTypeStr = getColumnSafe(record, "redirectType");

          Instant expiresAt = null;
          if (expiresAtStr != null && !expiresAtStr.isBlank()) {
            try {
              expiresAt = Instant.parse(expiresAtStr);
            } catch (Exception e) {
              parseErrors.add(
                  new BulkError(rowIndex, longUrl, "Invalid expiresAt format, expected ISO-8601"));
              rowIndex++;
              continue;
            }
          }

          Integer redirectType = null;
          if (redirectTypeStr != null && !redirectTypeStr.isBlank()) {
            try {
              redirectType = Integer.parseInt(redirectTypeStr);
            } catch (NumberFormatException e) {
              redirectType = 302;
            }
          }

          requests.add(
              new UrlDtos.CreateUrlRequest(
                  longUrl,
                  (alias != null && !alias.isBlank()) ? alias : null,
                  expiresAt,
                  redirectType,
                  null,
                  null));

        } catch (Exception e) {
          parseErrors.add(new BulkError(rowIndex, "", "Parse error: " + e.getMessage()));
        }
        rowIndex++;
      }
    } catch (IOException e) {
      throw new Exceptions.BulkImportException("Failed to parse CSV: " + e.getMessage());
    }

    if (requests.isEmpty() && !parseErrors.isEmpty()) {
      throw new Exceptions.BulkImportException(
          "No valid rows found in CSV. First error: " + parseErrors.get(0).reason());
    }

    BulkCreateResponse result =
        urlUseCase.bulkCreate(new UrlDtos.BulkCreateRequest(requests), userId);

    // Merge CSV parse errors with creation errors
    List<BulkError> allErrors = new ArrayList<>(parseErrors);
    allErrors.addAll(result.errors());

    log.info(
        "CSV import: userId={} total={} succeeded={} failed={}",
        userId,
        requests.size() + parseErrors.size(),
        result.succeeded(),
        allErrors.size());

    return new BulkCreateResponse(
        requests.size() + parseErrors.size(),
        result.succeeded(),
        allErrors.size(),
        result.created(),
        allErrors);
  }

  /**
   * Exports all active URLs for the given user as a CSV byte array.
   *
   * @param userId the user's UUID
   * @return UTF-8 encoded CSV bytes
   */
  public byte[] exportCsv(UUID userId) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        CSVPrinter printer =
            CSVFormat.DEFAULT
                .builder()
                .setHeader(
                    "shortCode",
                    "shortUrl",
                    "longUrl",
                    "title",
                    "clickCount",
                    "active",
                    "createdAt",
                    "expiresAt",
                    "redirectType")
                .build()
                .print(writer)) {

      int page = 0;
      List<ShortUrl> batch;
      do {
        PageRequest pageable =
            PageRequest.of(page, EXPORT_PAGE_SIZE, Sort.by("createdAt").descending());
        batch = shortUrlRepository.findByUserIdAndDeletedAtIsNull(userId, pageable).getContent();

        for (ShortUrl url : batch) {
          printer.printRecord(
              sanitizeCsvValue(url.getShortCode()),
              sanitizeCsvValue("https://short.ly/r/" + url.getShortCode()),
              sanitizeCsvValue(url.getLongUrl()),
              sanitizeCsvValue(url.getTitle()),
              url.getClickCount(),
              url.isActive(),
              url.getCreatedAt(),
              url.getExpiresAt() != null ? url.getExpiresAt().toString() : "",
              url.getRedirectType());
        }
        page++;
      } while (batch.size() == EXPORT_PAGE_SIZE);

      printer.flush();
      log.info("CSV export: userId={} rows={}", userId, page * EXPORT_PAGE_SIZE);
      return baos.toByteArray();

    } catch (IOException e) {
      throw new Exceptions.BulkImportException("Failed to generate CSV: " + e.getMessage());
    }
  }

  /**
   * CSV injection prevention: prefix dangerous leading characters with a tab. Prevents spreadsheet
   * applications from interpreting values as formulas. Reference: OWASP CSV injection.
   */
  private String sanitizeCsvValue(String value) {
    if (value == null) {
      return "";
    }
    if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
      return "\t" + value;
    }
    return value;
  }

  private String getColumnSafe(CSVRecord record, String column) {
    try {
      return record.get(column);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
