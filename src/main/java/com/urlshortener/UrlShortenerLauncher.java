package com.urlshortener;

import org.springframework.boot.SpringApplication;

/**
 * Application entry point.
 *
 * <p>Separated from {@link UrlShortenerApplication} so the Spring Boot application class is a pure
 * configuration class (instantiated by Spring) while this launcher class is a proper utility class
 * with a private constructor and a single static entry point.
 */
public final class UrlShortenerLauncher {

  private UrlShortenerLauncher() {}

  /**
   * Starts the Spring Boot application.
   *
   * @param args command-line arguments forwarded to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(UrlShortenerApplication.class, args);
  }
}
