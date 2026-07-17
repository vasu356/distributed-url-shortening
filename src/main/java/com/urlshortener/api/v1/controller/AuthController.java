package com.urlshortener.api.v1.controller;

import com.urlshortener.application.dto.request.AuthCommands;
import com.urlshortener.application.dto.response.AuthResult;
import com.urlshortener.application.dto.response.UserResult;
import com.urlshortener.application.usecase.AuthUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>Handles user registration, login, token refresh, logout, and current-user retrieval. Public
 * endpoints ({@code register}, {@code login}, {@code refresh}, {@code logout}) are permitted
 * without a token; {@code /me} requires authentication.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User registration and token management")
public class AuthController {

  private final AuthUseCase authUseCase;

  @PostMapping("/register")
  @Operation(summary = "Register a new user")
  public ResponseEntity<AuthResult> register(
      @Valid @RequestBody AuthCommands.RegisterCommand command) {
    return ResponseEntity.status(HttpStatus.CREATED).body(authUseCase.register(command));
  }

  @PostMapping("/login")
  @Operation(summary = "Authenticate and receive tokens")
  public ResponseEntity<AuthResult> login(@Valid @RequestBody AuthCommands.LoginCommand command) {
    return ResponseEntity.ok(authUseCase.login(command));
  }

  @PostMapping("/refresh")
  @Operation(summary = "Exchange a refresh token for a new access token")
  public ResponseEntity<AuthResult> refresh(
      @Valid @RequestBody AuthCommands.RefreshCommand command) {
    return ResponseEntity.ok(authUseCase.refresh(command.refreshToken()));
  }

  @PostMapping("/logout")
  @Operation(summary = "Invalidate the current access token")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
      authUseCase.logout(header.substring(7));
    }
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  @Operation(summary = "Get the current authenticated user")
  @SecurityRequirement(name = "bearerAuth")
  public ResponseEntity<UserResult> me(@AuthenticationPrincipal String userId) {
    return ResponseEntity.ok(authUseCase.getCurrentUser(UUID.fromString(userId)));
  }
}
