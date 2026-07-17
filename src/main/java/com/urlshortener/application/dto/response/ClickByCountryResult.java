package com.urlshortener.application.dto.response;

/** Aggregated click count by country code. */
public record ClickByCountryResult(String countryCode, long count) {
  public ClickByCountryResult {}
}
