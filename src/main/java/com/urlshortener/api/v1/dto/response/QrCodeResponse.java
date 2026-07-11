package com.urlshortener.api.v1.dto.response;

/** Metadata about a generated QR code (not the image bytes themselves). */
public record QrCodeResponse(String shortCode, String shortUrl, String format, int size) {
  public QrCodeResponse {}
}
