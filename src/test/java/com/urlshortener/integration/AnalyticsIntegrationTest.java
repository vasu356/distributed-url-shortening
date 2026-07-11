package com.urlshortener.integration;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.api.v1.dto.request.AuthDtos;
import com.urlshortener.api.v1.dto.request.UrlDtos;
import com.urlshortener.api.v1.dto.response.AuthResponse;
import com.urlshortener.api.v1.dto.response.UrlResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@DisplayName("Analytics Integration Tests")
class AnalyticsIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("analytics endpoint returns structured data for a URL")
  void getAnalytics_returnsStructuredData() throws Exception {
    String token = registerAndGetToken("analytics@test.com");
    String shortCode = createUrl(token, "https://analytics-test.com");

    mockMvc.perform(get("/r/" + shortCode)).andReturn();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                mockMvc
                    .perform(
                        get("/api/v1/analytics/" + shortCode)
                            .header("Authorization", "Bearer " + token)
                            .param("days", "30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalClicks").value(1)));
  }

  @Test
  @DisplayName("analytics returns 403 when accessing another user's URL")
  void getAnalytics_otherUserUrl_returns403() throws Exception {
    String ownerToken = registerAndGetToken("owner-a@test.com");
    String attackerToken = registerAndGetToken("attacker-a@test.com");
    String shortCode = createUrl(ownerToken, "https://private.com");

    mockMvc
        .perform(
            get("/api/v1/analytics/" + shortCode)
                .header("Authorization", "Bearer " + attackerToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("dashboard returns aggregate metrics")
  void getDashboard_returnsAggregateMetrics() throws Exception {
    String token = registerAndGetToken("dash@test.com");
    createUrl(token, "https://dashboard-test.com");

    mockMvc
        .perform(
            get("/api/v1/analytics/dashboard")
                .header("Authorization", "Bearer " + token)
                .param("days", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalActiveUrls").isNumber())
        .andExpect(jsonPath("$.totalClicksInPeriod").isNumber())
        .andExpect(jsonPath("$.from").isNotEmpty());
  }

  @Test
  @DisplayName("days param is clamped to 1-365 range")
  void getAnalytics_daysParamClamped() throws Exception {
    String token = registerAndGetToken("clamp@test.com");
    String shortCode = createUrl(token, "https://clamp-test.com");

    mockMvc
        .perform(
            get("/api/v1/analytics/" + shortCode)
                .header("Authorization", "Bearer " + token)
                .param("days", "999"))
        .andExpect(status().isOk());
  }

  private String registerAndGetToken(String email) throws Exception {
    var reg = new AuthDtos.RegisterRequest(email, "Password@123");
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(reg)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), AuthResponse.class)
        .accessToken();
  }

  private String createUrl(String token, String longUrl) throws Exception {
    var req = new UrlDtos.CreateUrlRequest(longUrl, null, null, 302, null, null);
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/urls")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readValue(result.getResponse().getContentAsString(), UrlResponse.class)
        .shortCode();
  }
}
