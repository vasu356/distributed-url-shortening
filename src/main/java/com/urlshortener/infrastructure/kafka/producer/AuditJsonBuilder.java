package com.urlshortener.infrastructure.kafka.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds valid JSON strings for audit event old/new value fields.
 *
 * <p>Centralises Jackson serialisation so callers never hand-roll JSON strings. A serialisation
 * failure returns {@code null} (audit write is best-effort) and logs a warning rather than
 * propagating an exception into the business flow.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditJsonBuilder {

  private final ObjectMapper objectMapper;

  /**
   * Serialises the supplied key-value pairs to a compact JSON object string.
   *
   * @param keyValuePairs alternating key, value pairs (key must be a String; value may be any type
   *     serialisable by Jackson)
   * @return JSON string, e.g. {@code {"email":"a@b.com","role":"USER"}} or {@code null} on error
   */
  public String build(Object... keyValuePairs) {
    if (keyValuePairs == null || keyValuePairs.length == 0) {
      return null;
    }
    if (keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException(
          "keyValuePairs must be an even number of alternating key/value arguments");
    }

    Map<String, Object> map = new LinkedHashMap<>(keyValuePairs.length / 2);
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
    }

    try {
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialise audit JSON payload: {}", e.getMessage());
      return null;
    }
  }
}
