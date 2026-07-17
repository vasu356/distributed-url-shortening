package com.urlshortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.application.dto.request.AuthCommands;
import com.urlshortener.application.dto.request.UrlCommands;
import com.urlshortener.application.dto.response.AuthResult;
import com.urlshortener.application.dto.response.UrlResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@DisplayName("URL Shortening Integration Tests")
class UrlIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("full flow: register → create URL → redirect → get analytics")
  void fullFlow_registerCreateRedirectAnalytics() throws Exception {
    var registerCommand = new AuthCommands.RegisterCommand("integration@test.com", "TestPass@123");

    MvcResult registerResult =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(registerCommand)))
            .andExpect(status().isCreated())
            .andReturn();

    AuthResult authResult =
        objectMapper.readValue(registerResult.getResponse().getContentAsString(), AuthResult.class);
    String token = authResult.accessToken();
    assertThat(token).isNotBlank();

    var createCommand =
        new UrlCommands.CreateUrlCommand(
            "https://www.example.com/very-long-url", null, null, 302, null, null);

    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/v1/urls")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .content(objectMapper.writeValueAsString(createCommand)))
            .andExpect(status().isCreated())
            .andReturn();

    UrlResult urlResult =
        objectMapper.readValue(createResult.getResponse().getContentAsString(), UrlResult.class);
    String shortCode = urlResult.shortCode();
    assertThat(shortCode).hasSize(7);
    assertThat(urlResult.longUrl()).isEqualTo("https://www.example.com/very-long-url");

    mockMvc
        .perform(get("/r/" + shortCode))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://www.example.com/very-long-url"));

    mockMvc
        .perform(get("/api/v1/urls").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content[0].shortCode").value(shortCode));
  }

  @Test
  @DisplayName("custom alias: creates and redirects with alias")
  void customAlias_createAndRedirect() throws Exception {
    String token = registerAndLogin("alias@test.com", "TestPass@123");

    var command =
        new UrlCommands.CreateUrlCommand("https://github.com", "my-github", null, 302, null, null);

    mockMvc
        .perform(
            post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.shortCode").value("my-github"));

    mockMvc
        .perform(get("/r/my-github"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://github.com"));
  }

  @Test
  @DisplayName("duplicate custom alias returns 409 Conflict")
  void duplicateAlias_returns409() throws Exception {
    String token = registerAndLogin("dup@test.com", "TestPass@123");

    var command =
        new UrlCommands.CreateUrlCommand("https://example.com", "dup-alias", null, 302, null, null);

    mockMvc
        .perform(
            post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("ALIAS_CONFLICT"));
  }

  @Test
  @DisplayName("redirect to unknown short code returns 404")
  void redirect_unknownCode_returns404() throws Exception {
    mockMvc.perform(get("/r/unknown9")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("soft-delete URL stops redirects")
  void deleteUrl_stopsRedirect() throws Exception {
    String token = registerAndLogin("delete@test.com", "TestPass@123");

    var command =
        new UrlCommands.CreateUrlCommand("https://todelete.com", "del-me", null, 302, null, null);

    mockMvc
        .perform(
            post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/r/del-me")).andExpect(status().isFound());

    mockMvc
        .perform(delete("/api/v1/urls/del-me").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/r/del-me")).andExpect(status().isGone());
  }

  @Test
  @DisplayName("unauthenticated request to /api/v1/urls returns 401")
  void unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/urls")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("validation: invalid URL returns 400 with field errors")
  void createUrl_invalidUrl_returns400() throws Exception {
    String token = registerAndLogin("val@test.com", "TestPass@123");

    var command = new UrlCommands.CreateUrlCommand("not-a-url", null, null, null, null, null);

    mockMvc
        .perform(
            post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors").isArray());
  }

  private String registerAndLogin(String email, String password) throws Exception {
    var reg = new AuthCommands.RegisterCommand(email, password);
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reg)))
            .andExpect(status().isCreated())
            .andReturn();
    AuthResult auth =
        objectMapper.readValue(result.getResponse().getContentAsString(), AuthResult.class);
    return auth.accessToken();
  }
}
