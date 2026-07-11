package com.urlshortener.api.v1.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request DTOs for authentication endpoints. */
public final class AuthDtos {

  private AuthDtos() {}

  /** Registration request with email and password. */
  public record RegisterRequest(
      @NotBlank(message = "Email is required")
          @Email(message = "Must be a valid email address")
          @Size(max = 255)
          String email,
      @NotBlank(message = "Password is required")
          @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
          String password) {
    public RegisterRequest {}
  }

  /** Login request with email and password. */
  public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    public LoginRequest {}
  }

  /** Token refresh request carrying the refresh token. */
  public record RefreshRequest(@NotBlank String refreshToken) {
    public RefreshRequest {}
  }

  /** Change-password request with current and new password. */
  public record ChangePasswordRequest(
      @NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 128) String newPassword) {
    public ChangePasswordRequest {}
  }
}
