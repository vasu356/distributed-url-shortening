package com.urlshortener.application.dto.response;

/** Aggregated click count for a single calendar date. */
public record ClickByDateResult(String date, long count) {
  public ClickByDateResult {}
}
