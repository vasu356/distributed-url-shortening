package com.urlshortener.application.dto.response;

import java.util.List;

/** Summary result of a bulk URL creation request. */
public record BulkCreateResult(
    int total, int succeeded, int failed, List<UrlResult> created, List<BulkItemError> errors) {
  public BulkCreateResult {}
}
