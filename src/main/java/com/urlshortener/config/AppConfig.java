package com.urlshortener.config;

import java.util.Optional;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * General application configuration.
 *
 * <p>JPA auditing wired here — populates @CreatedDate / @LastModifiedDate automatically. The
 * auditorProvider resolves the currently-authenticated user's email for audit columns.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AppConfig implements AsyncConfigurer {

  @Bean
  public AuditorAware<String> auditorProvider() {
    return () -> {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
        return Optional.of(auth.getName());
      }
      return Optional.of("SYSTEM");
    };
  }

  /**
   * Async executor for @Async methods (metadata scraping, QR generation). Virtual threads handle
   * I/O-bound work; this pool handles CPU-bound @Async tasks.
   */
  @Override
  @Bean(name = "taskExecutor")
  public Executor getAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("async-task-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }
}
