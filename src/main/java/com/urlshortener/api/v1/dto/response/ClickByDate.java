package com.urlshortener.api.v1.dto.response;

/** Aggregated click count for a single calendar date. */
public record ClickByDate(String date, long count) {
  public ClickByDate {}
}
