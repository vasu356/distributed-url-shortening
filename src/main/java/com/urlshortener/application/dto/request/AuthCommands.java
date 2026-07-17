package com.urlshortener.application.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Application-layer command objects for authentication use cases. */
public final class AuthCommands {

  private AuthCommands() {}

  /** Registration command with email and password. */
  public record RegisterCommand(
      @NotBlank(message = "Email is required")
          @Email(message = "Must be a valid email address")
          @Size(max = 255)
          String email,
      @NotBlank(message = "Password is required")
          @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
          String password) {
    public RegisterCommand {}
  }

  /** Login command with email and password. */
  public record LoginCommand(@NotBlank @Email String email, @NotBlank String password) {
    public LoginCommand {}
  }

  /** Token refresh command carrying the refresh token. */
  public record RefreshCommand(@NotBlank String refreshToken) {
    public RefreshCommand {}
  }

  /** Change-password command with current and new password. */
  public record ChangePasswordCommand(
      @NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 128) String newPassword) {
    public ChangePasswordCommand {}
  }
}
