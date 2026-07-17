package com.urlshortener.application.dto.response;

/** A single error entry from a bulk or CSV import operation. */
public record BulkItemError(int index, String longUrl, String reason) {
  public BulkItemError {}
}
