package com.urlshortener.api.v1.dto.response;

import java.util.List;

/** Summary result of a bulk URL creation request. */
public record BulkCreateResponse(
    int total, int succeeded, int failed, List<UrlResponse> created, List<BulkError> errors) {
  public BulkCreateResponse {}
}
