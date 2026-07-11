package com.urlshortener;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot application class for the Distributed URL Shortening Platform.
 *
 * <p>Virtual Threads are enabled via application.yml ({@code spring.threads.virtual.enabled=true})
 * introduced in Spring Boot 3.2. This provides reactive-equivalent throughput on the redirect hot
 * path without reactive programming complexity. Platform threads are no longer blocked during
 * Redis/DB I/O calls.
 *
 * <p>Application startup is handled by {@link UrlShortenerLauncher#main(String[])}. Key design
 * decisions documented in {@code /docs/adr/}.
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class UrlShortenerApplication {

  /** No-arg constructor required by Spring's component scanning. */
  public UrlShortenerApplication() {}
}
