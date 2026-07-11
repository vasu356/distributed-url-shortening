package com.urlshortener.common.exception;

import org.springframework.http.HttpStatus;

/** Centralised exception catalogue. All concrete exceptions as static nested classes. */
public final class Exceptions {

  private Exceptions() {}

  public static class UrlNotFoundException extends DomainException {
    public UrlNotFoundException(String shortCode) {
      super("Short URL not found: " + shortCode, HttpStatus.NOT_FOUND, "URL_NOT_FOUND");
    }
  }

  public static class UrlExpiredException extends DomainException {
    public UrlExpiredException(String shortCode) {
      super("Short URL has expired: " + shortCode, HttpStatus.GONE, "URL_EXPIRED");
    }
  }

  public static class UrlInactiveException extends DomainException {
    public UrlInactiveException(String shortCode) {
      super("Short URL is inactive: " + shortCode, HttpStatus.GONE, "URL_INACTIVE");
    }
  }

  public static class UrlClickCapReachedException extends DomainException {
    public UrlClickCapReachedException(String shortCode) {
      super(
          "Short URL has reached its click limit: " + shortCode, HttpStatus.GONE, "URL_CLICK_CAP");
    }
  }

  public static class AliasAlreadyExistsException extends DomainException {
    public AliasAlreadyExistsException(String alias) {
      super("Custom alias already taken: " + alias, HttpStatus.CONFLICT, "ALIAS_CONFLICT");
    }
  }

  public static class UserNotFoundException extends DomainException {
    public UserNotFoundException(String identifier) {
      super("User not found: " + identifier, HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }
  }

  public static class UserAlreadyExistsException extends DomainException {
    public UserAlreadyExistsException(String email) {
      super("User already exists with email: " + email, HttpStatus.CONFLICT, "USER_ALREADY_EXISTS");
    }
  }

  public static class InvalidCredentialsException extends DomainException {
    public InvalidCredentialsException() {
      super("Invalid email or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }
  }

  public static class AccountDeactivatedException extends DomainException {
    public AccountDeactivatedException() {
      super("Account has been deactivated", HttpStatus.FORBIDDEN, "ACCOUNT_DEACTIVATED");
    }
  }

  public static class ApiKeyNotFoundException extends DomainException {
    public ApiKeyNotFoundException() {
      super("API key not found or revoked", HttpStatus.UNAUTHORIZED, "API_KEY_INVALID");
    }
  }

  public static class ApiKeyExpiredException extends DomainException {
    public ApiKeyExpiredException() {
      super("API key has expired", HttpStatus.UNAUTHORIZED, "API_KEY_EXPIRED");
    }
  }

  public static class RateLimitExceededException extends DomainException {
    public RateLimitExceededException(String limitType) {
      super(
          "Rate limit exceeded: " + limitType, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED");
    }
  }

  public static class InvalidUrlException extends DomainException {
    public InvalidUrlException(String url) {
      super("Invalid or unsafe URL: " + url, HttpStatus.BAD_REQUEST, "INVALID_URL");
    }
  }

  public static class BulkImportException extends DomainException {
    public BulkImportException(String message) {
      super(message, HttpStatus.BAD_REQUEST, "BULK_IMPORT_ERROR");
    }
  }

  public static class UnauthorizedAccessException extends DomainException {
    public UnauthorizedAccessException() {
      super("Access denied to this resource", HttpStatus.FORBIDDEN, "FORBIDDEN");
    }
  }

  public static class TokenExpiredException extends DomainException {
    public TokenExpiredException() {
      super("Authentication token has expired", HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    }
  }

  public static class InvalidTokenException extends DomainException {
    public InvalidTokenException() {
      super("Invalid authentication token", HttpStatus.UNAUTHORIZED, "TOKEN_INVALID");
    }
  }
}
