package com.urlshortener.observability.health;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.Node;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Custom health indicators for Redis and Kafka.
 *
 * <p>Exposed via {@code /actuator/health/redis} and {@code /actuator/health/kafka}. These
 * supplement Spring Boot's auto-configured health checks with latency details.
 */
@Slf4j
public final class HealthIndicators {

  /** Health indicator for Redis connectivity and latency. */
  @Component("redis")
  @RequiredArgsConstructor
  public static class RedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public Health health() {
      try {
        long start = System.currentTimeMillis();
        var connection = redisConnectionFactory.getConnection();
        String pong = connection.ping();
        long latencyMs = System.currentTimeMillis() - start;
        connection.close();

        if ("PONG".equalsIgnoreCase(pong)) {
          return Health.up().withDetail("latencyMs", latencyMs).build();
        }
        return Health.down().withDetail("response", pong).build();
      } catch (Exception ex) {
        log.warn("Redis health check failed: {}", ex.getMessage());
        return Health.down().withDetail("error", ex.getMessage()).build();
      }
    }
  }

  /** Health indicator for Kafka cluster connectivity and broker count. */
  @Component("kafka")
  @RequiredArgsConstructor
  public static class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;

    @Override
    public Health health() {
      try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {

        Collection<Node> nodes = adminClient.describeCluster().nodes().get(5, TimeUnit.SECONDS);

        return Health.up()
            .withDetail("brokerCount", nodes.size())
            .withDetail("brokers", nodes.stream().map(n -> n.host() + ":" + n.port()).toList())
            .build();
      } catch (Exception ex) {
        log.warn("Kafka health check failed: {}", ex.getMessage());
        return Health.down().withDetail("error", ex.getMessage()).build();
      }
    }
  }
}
