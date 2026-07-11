package com.urlshortener.api.v1.dto.response;

/** Authentication response containing access token, refresh token, and user details. */
public record AuthResponse(
    String accessToken, String tokenType, long expiresIn, String refreshToken, UserResponse user) {
  public AuthResponse {}
}
