package com.urlshortener.application.dto.response;

/** Aggregated click count by device type. */
public record ClickByDeviceResult(String deviceType, long count) {
  public ClickByDeviceResult {}
}
