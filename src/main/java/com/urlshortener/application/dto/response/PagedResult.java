package com.urlshortener.application.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

/** Generic paged response wrapper for use-case results. */
public record PagedResult<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean first,
    boolean last) {
  public static <T> PagedResult<T> of(Page<T> page) {
    return new PagedResult<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        page.isFirst(),
        page.isLast());
  }
}
