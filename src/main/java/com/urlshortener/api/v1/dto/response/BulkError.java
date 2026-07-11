package com.urlshortener.api.v1.dto.response;

/** A single error entry from a bulk or CSV import operation. */
public record BulkError(int index, String longUrl, String reason) {
  public BulkError {}
}
