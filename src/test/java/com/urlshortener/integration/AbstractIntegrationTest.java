package com.urlshortener.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for all integration tests.
 *
 * <p>Starts real PostgreSQL, Redis, and Kafka containers using Testcontainers. Containers are
 * shared across all tests in the same JVM via static fields — started once in the static
 * initializer, not once per test class. This dramatically reduces test suite runtime.
 *
 * <p>Container lifecycle pattern: static fields + static initializer (not @Testcontainers
 * + @Container). Using @Testcontainers alongside a manual static start() call causes double
 * lifecycle management: the annotation tries to start already-running containers and interferes
 * with withReuse(true). The static initializer pattern is the correct approach for shared
 * containers.
 *
 * <p>Why Testcontainers over H2:
 *
 * <ul>
 *   <li>Production parity: same PostgreSQL dialect, extensions, and query behaviour.
 *   <li>H2 doesn't support JSONB, GIN indexes, declarative partitions, or pg-specific functions.
 *   <li>Redis: real distributed cache behaviour, not a mock.
 *   <li>Kafka: real consumer group semantics, offset management, and DLT routing.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("urlshortener_test")
          .withUsername("test")
          .withPassword("test")
          .withReuse(true);

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
          .withReuse(true);

  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0")).withReuse(true);

  static {
    POSTGRES.start();
    REDIS.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);

    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());

    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }
}
