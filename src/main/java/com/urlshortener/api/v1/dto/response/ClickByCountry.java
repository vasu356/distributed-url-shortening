package com.urlshortener.api.v1.dto.response;

/** Aggregated click count by country code. */
public record ClickByCountry(String countryCode, long count) {
  public ClickByCountry {}
}
