package com.urlshortener.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CORS configuration properties bound from {@code app.cors.*}.
 *
 * <p>Configure allowed origins in {@code application.yml} under {@code app.cors.allowed-origins}.
 */
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

  private List<String> allowedOrigins = new ArrayList<>();

  /**
   * Returns the list of allowed CORS origins.
   *
   * @return allowed origins list
   */
  public List<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  /**
   * Sets the list of allowed CORS origins.
   *
   * @param allowedOrigins the list of origin strings to allow
   */
  public void setAllowedOrigins(List<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }
}
