package com.urlshortener.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base domain exception. All business-rule violations extend this. Maps cleanly to HTTP status
 * codes without leaking implementation details to clients.
 */
public abstract class DomainException extends RuntimeException {

  private final HttpStatus status;
  private final String errorCode;

  protected DomainException(String message, HttpStatus status, String errorCode) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }

  protected DomainException(String message, HttpStatus status, String errorCode, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.errorCode = errorCode;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }
}
