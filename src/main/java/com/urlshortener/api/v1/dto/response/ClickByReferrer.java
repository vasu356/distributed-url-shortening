package com.urlshortener.api.v1.dto.response;

/** Aggregated click count by referrer domain. */
public record ClickByReferrer(String referrer, long count) {
  public ClickByReferrer {}
}
