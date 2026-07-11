package com.urlshortener.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralized exception handling.
 *
 * <p>Uses RFC 9457 ProblemDetail (Spring 6+) for structured error responses. This is the
 * industry-standard error format for REST APIs.
 *
 * <p>Security principle: NEVER leak internal details (stack traces, SQL, class names) to clients.
 * All exceptions are logged with full context server-side; clients receive only safe, structured
 * messages.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /** Handle all domain exceptions — maps to appropriate HTTP status. */
  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ProblemDetail> handleDomainException(
      DomainException ex, HttpServletRequest request) {

    log.warn(
        "Domain exception [{}] {}: {}",
        ex.getErrorCode(),
        request.getRequestURI(),
        ex.getMessage());

    ProblemDetail problem = buildProblemDetail(ex.getStatus(), ex.getMessage(), ex.getErrorCode());
    addRequestContext(problem, request);

    // Add Retry-After header for rate limit responses
    HttpHeaders headers = new HttpHeaders();
    if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
      headers.set("Retry-After", "60");
    }

    return ResponseEntity.status(ex.getStatus()).headers(headers).body(problem);
  }

  /** Handle validation errors from @Valid / @Validated. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {

    List<Map<String, String>> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fe ->
                    Map.of(
                        "field", fe.getField(),
                        "message",
                            fe.getDefaultMessage() != null
                                ? fe.getDefaultMessage()
                                : "Invalid value",
                        "rejectedValue",
                            fe.getRejectedValue() != null
                                ? fe.getRejectedValue().toString()
                                : "null"))
            .toList();

    ProblemDetail problem =
        buildProblemDetail(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_ERROR");
    problem.setProperty("errors", fieldErrors);

    log.debug("Validation failed: {} field errors", fieldErrors.size());
    return ResponseEntity.badRequest().body(problem);
  }

  /** Handle constraint violations (method-level validation). */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {

    List<Map<String, String>> violations =
        ex.getConstraintViolations().stream()
            .map(
                cv ->
                    Map.of(
                        "field", extractFieldName(cv),
                        "message", cv.getMessage()))
            .toList();

    ProblemDetail problem =
        buildProblemDetail(HttpStatus.BAD_REQUEST, "Constraint violation", "VALIDATION_ERROR");
    problem.setProperty("errors", violations);
    addRequestContext(problem, request);

    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthenticationException(
      AuthenticationException ex, HttpServletRequest request) {
    log.warn("Authentication failed for {}: {}", request.getRequestURI(), ex.getMessage());
    ProblemDetail problem =
        buildProblemDetail(HttpStatus.UNAUTHORIZED, "Authentication required", "UNAUTHORIZED");
    addRequestContext(problem, request);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ProblemDetail> handleBadCredentials(
      BadCredentialsException ex, HttpServletRequest request) {
    // Delay response slightly to slow down brute-force attacks
    try {
      Thread.sleep(100);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    ProblemDetail problem =
        buildProblemDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials", "INVALID_CREDENTIALS");
    addRequestContext(problem, request);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    log.warn("Access denied for {}: {}", request.getRequestURI(), ex.getMessage());
    ProblemDetail problem = buildProblemDetail(HttpStatus.FORBIDDEN, "Access denied", "FORBIDDEN");
    addRequestContext(problem, request);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
  }

  /**
   * Catch-all for unexpected exceptions. Log full stack trace internally, return generic 500 to
   * client.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    String errorId = UUID.randomUUID().toString();
    log.error("Unexpected error [{}] processing {}: ", errorId, request.getRequestURI(), ex);

    ProblemDetail problem =
        buildProblemDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Reference: " + errorId,
            "INTERNAL_ERROR");
    addRequestContext(problem, request);
    problem.setProperty("errorId", errorId);

    return ResponseEntity.internalServerError().body(problem);
  }

  private ProblemDetail buildProblemDetail(HttpStatus status, String detail, String errorCode) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("errorCode", errorCode);
    problem.setProperty("timestamp", Instant.now().toString());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      problem.setProperty("correlationId", correlationId);
    }
    return problem;
  }

  private void addRequestContext(ProblemDetail problem, HttpServletRequest request) {
    problem.setProperty("path", request.getRequestURI());
    problem.setProperty("method", request.getMethod());
  }

  private String extractFieldName(ConstraintViolation<?> cv) {
    String path = cv.getPropertyPath().toString();
    int lastDot = path.lastIndexOf('.');
    return lastDot >= 0 ? path.substring(lastDot + 1) : path;
  }
}
