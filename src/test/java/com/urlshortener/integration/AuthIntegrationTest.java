package com.urlshortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.api.v1.dto.request.AuthDtos;
import com.urlshortener.api.v1.dto.response.AuthResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@DisplayName("Auth Integration Tests")
class AuthIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("register → login → me flow succeeds")
  void registerLoginMe_success() throws Exception {
    var reg = new AuthDtos.RegisterRequest("auth-it@test.com", "Password@123");
    MvcResult regResult =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reg)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.user.email").value("auth-it@test.com"))
            .andReturn();

    AuthResponse authResponse =
        objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);

    mockMvc
        .perform(
            get("/api/v1/auth/me").header("Authorization", "Bearer " + authResponse.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("auth-it@test.com"))
        .andExpect(jsonPath("$.role").value("USER"));
  }

  @Test
  @DisplayName("duplicate registration returns 409")
  void register_duplicate_returns409() throws Exception {
    var reg = new AuthDtos.RegisterRequest("dup-auth@test.com", "Password@123");
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("USER_ALREADY_EXISTS"));
  }

  @Test
  @DisplayName("login with wrong password returns 401")
  void login_wrongPassword_returns401() throws Exception {
    var reg = new AuthDtos.RegisterRequest("wrongpw@test.com", "Password@123");
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
        .andExpect(status().isCreated());

    var login = new AuthDtos.LoginRequest("wrongpw@test.com", "WrongPassword");
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
  }

  @Test
  @DisplayName("refresh token issues new access token")
  void refresh_issuesnNewAccessToken() throws Exception {
    var reg = new AuthDtos.RegisterRequest("refresh@test.com", "Password@123");
    MvcResult regResult =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reg)))
            .andExpect(status().isCreated())
            .andReturn();

    AuthResponse auth =
        objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);

    var refreshReq = new AuthDtos.RefreshRequest(auth.refreshToken());
    MvcResult refreshResult =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
            .andExpect(status().isOk())
            .andReturn();

    AuthResponse refreshed =
        objectMapper.readValue(
            refreshResult.getResponse().getContentAsString(), AuthResponse.class);

    assertThat(refreshed.accessToken()).isNotBlank();
    assertThat(refreshed.accessToken()).isNotEqualTo(auth.accessToken());
  }

  @Test
  @DisplayName("logout blacklists the token")
  void logout_blacklistsToken() throws Exception {
    var reg = new AuthDtos.RegisterRequest("logout@test.com", "Password@123");
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reg)))
            .andExpect(status().isCreated())
            .andReturn();
    AuthResponse auth =
        objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);

    mockMvc
        .perform(
            post("/api/v1/auth/logout").header("Authorization", "Bearer " + auth.accessToken()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + auth.accessToken()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("missing Authorization header returns 401")
  void noToken_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("register with invalid email returns 400")
  void register_invalidEmail_returns400() throws Exception {
    var reg = new AuthDtos.RegisterRequest("not-an-email", "Password@123");
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("register with too-short password returns 400")
  void register_shortPassword_returns400() throws Exception {
    var reg = new AuthDtos.RegisterRequest("short@test.com", "abc");
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
  }
}
