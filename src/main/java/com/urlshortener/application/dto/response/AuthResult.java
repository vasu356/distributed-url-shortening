package com.urlshortener.application.dto.response;

/** Authentication result containing access token, refresh token, and user details. */
public record AuthResult(
    String accessToken, String tokenType, long expiresIn, String refreshToken, UserResult user) {
  public AuthResult {}
}
