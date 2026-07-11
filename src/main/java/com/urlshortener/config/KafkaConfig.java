package com.urlshortener.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka topic definitions and error handling.
 *
 * <p>Topic partitions: click-events has 24 so peak consumer parallelism (3 pods × 8 threads) is
 * matched. audit/lifecycle have 6 each — lower volume.
 *
 * <p>Error handling: ExponentialBackOff (1s → 2s → 4s, max 3 retries) then DLT. Prevents a single
 * poison-pill message from blocking a partition indefinitely.
 */
@Configuration
@EnableKafka
@Slf4j
public class KafkaConfig {

  @Value("${app.kafka.topics.click-events}")
  private String clickEventsTopic;

  @Value("${app.kafka.topics.audit-events}")
  private String auditEventsTopic;

  @Value("${app.kafka.topics.lifecycle-events}")
  private String lifecycleEventsTopic;

  @Value("${app.kafka.topics.click-events-dlt}")
  private String clickEventsDlt;

  @Value("${app.kafka.topics.audit-events-dlt}")
  private String auditEventsDlt;

  @Value("${app.kafka.topics.lifecycle-events-dlt}")
  private String lifecycleEventsDlt;

  // ── Topics ──────────────────────────────────────────────────────────

  /** Defines the click-events topic with 24 partitions for high throughput. */
  @Bean
  public NewTopic clickEventsTopic() {
    return TopicBuilder.name(clickEventsTopic)
        .partitions(24)
        .replicas(1)
        .config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000L))
        .config("compression.type", "snappy")
        .build();
  }

  /** Defines the click-events dead-letter topic. */
  @Bean
  public NewTopic clickEventsDltTopic() {
    return TopicBuilder.name(clickEventsDlt)
        .partitions(6)
        .replicas(1)
        .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000L))
        .build();
  }

  /** Defines the audit-events dead-letter topic. */
  @Bean
  public NewTopic auditEventsDltTopic() {
    return TopicBuilder.name(auditEventsDlt)
        .partitions(3)
        .replicas(1)
        .config("retention.ms", String.valueOf(90L * 24 * 60 * 60 * 1000L))
        .build();
  }

  /** Defines the audit-events topic. */
  @Bean
  public NewTopic auditEventsTopic() {
    return TopicBuilder.name(auditEventsTopic)
        .partitions(6)
        .replicas(1)
        .config("retention.ms", String.valueOf(90L * 24 * 60 * 60 * 1000L))
        .build();
  }

  /** Defines the lifecycle-events topic. */
  @Bean
  public NewTopic lifecycleEventsTopic() {
    return TopicBuilder.name(lifecycleEventsTopic)
        .partitions(6)
        .replicas(1)
        .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000L))
        .build();
  }

  /** Defines the lifecycle-events dead-letter topic. */
  @Bean
  public NewTopic lifecycleEventsDltTopic() {
    return TopicBuilder.name(lifecycleEventsDlt)
        .partitions(3)
        .replicas(1)
        .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000L))
        .build();
  }

  // ── Error handling ───────────────────────────────────────────────────

  /**
   * Exponential back-off: 1 s → 2 s → 4 s, then publish to DLT. Uses Spring's ExponentialBackOff
   * (not the Kafka client class).
   *
   * @param kafkaTemplate the template used to publish to the dead-letter topic
   * @return the configured error handler
   */
  @Bean
  public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
              log.error(
                  "Sending to DLT topic={} partition={} offset={} error={}",
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  ex.getMessage());
              return new TopicPartition(record.topic() + ".DLT", record.partition() % 6);
            });

    ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
    backOff.setMaxAttempts(3);

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        IllegalArgumentException.class, com.fasterxml.jackson.core.JsonProcessingException.class);

    return handler;
  }
}
