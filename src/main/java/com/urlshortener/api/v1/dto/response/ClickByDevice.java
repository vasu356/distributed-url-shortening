package com.urlshortener.api.v1.dto.response;

/** Aggregated click count by device type. */
public record ClickByDevice(String deviceType, long count) {
  public ClickByDevice {}
}
