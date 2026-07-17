package com.urlshortener.application.dto.response;

/** Aggregated click count by referrer domain. */
public record ClickByReferrerResult(String referrer, long count) {
  public ClickByReferrerResult {}
}
