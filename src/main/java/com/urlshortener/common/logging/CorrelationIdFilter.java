package com.urlshortener.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Injects a correlation ID into the MDC (Mapped Diagnostic Context) for every request.
 *
 * <p>The correlation ID is: 1. Taken from incoming X-Correlation-ID header (if client provides one)
 * 2. Generated as a random UUID if not provided
 *
 * <p>This ID appears in: - Every log line (via logback pattern %X{correlationId}) - Response header
 * X-Correlation-ID (client can use for support requests) - ProblemDetail error responses -
 * OpenTelemetry span attributes
 *
 * <p>Priority: Ordered.HIGHEST_PRECEDENCE ensures this runs before Spring Security filters, so all
 * security-related log messages also include the correlation ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
  public static final String CORRELATION_ID_MDC_KEY = "correlationId";
  public static final String REQUEST_ID_MDC_KEY = "requestId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String correlationId = request.getHeader(CORRELATION_ID_HEADER);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }

    String requestId = UUID.randomUUID().toString();

    try {
      MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
      MDC.put(REQUEST_ID_MDC_KEY, requestId);

      // Propagate back to client for end-to-end request tracing
      response.setHeader(CORRELATION_ID_HEADER, correlationId);
      response.setHeader("X-Request-ID", requestId);

      filterChain.doFilter(request, response);
    } finally {
      // Always clean up MDC — Virtual Threads reuse JVM carrier threads,
      // so MDC must be cleared to prevent leaking values across requests
      MDC.remove(CORRELATION_ID_MDC_KEY);
      MDC.remove(REQUEST_ID_MDC_KEY);
      MDC.remove("userId");
      MDC.remove("shortCode");
    }
  }
}
