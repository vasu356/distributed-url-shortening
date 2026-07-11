package com.urlshortener.config;

import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Infrastructure beans for Kafka listener container factories.
 *
 * <p>Three factories: - kafkaListenerContainerFactory: batch, typed for ClickEvent (500
 * records/poll) - singleKafkaListenerContainerFactory: single-record, typed for LifecycleEvent -
 * auditKafkaListenerContainerFactory: batch, typed for AuditEvent
 */
@Configuration
@Slf4j
public class InfrastructureConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  // ── Consumer factories ──────────────────────────────────────────────

  @Bean
  public ConsumerFactory<String, KafkaEvents.ClickEvent> clickEventConsumerFactory() {
    return new DefaultKafkaConsumerFactory<>(baseConsumerProps());
  }

  @Bean
  public ConsumerFactory<String, KafkaEvents.AuditEvent> auditEventConsumerFactory() {
    return new DefaultKafkaConsumerFactory<>(baseConsumerProps());
  }

  @Bean
  public ConsumerFactory<String, KafkaEvents.LifecycleEvent> lifecycleEventConsumerFactory() {
    return new DefaultKafkaConsumerFactory<>(baseConsumerProps());
  }

  // ── Listener container factories ────────────────────────────────────

  /** Batch listener factory for ClickEventConsumer (500 records per poll). */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, KafkaEvents.ClickEvent>
      kafkaListenerContainerFactory(
          ConsumerFactory<String, KafkaEvents.ClickEvent> clickEventConsumerFactory,
          CommonErrorHandler kafkaErrorHandler) {

    ConcurrentKafkaListenerContainerFactory<String, KafkaEvents.ClickEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(clickEventConsumerFactory);
    factory.setConcurrency(3);
    factory.setCommonErrorHandler(kafkaErrorHandler);
    factory.setBatchListener(true);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setShutdownTimeout(30_000L);
    return factory;
  }

  /** Batch listener factory for AuditEventConsumer. */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, KafkaEvents.AuditEvent>
      auditKafkaListenerContainerFactory(
          ConsumerFactory<String, KafkaEvents.AuditEvent> auditEventConsumerFactory,
          CommonErrorHandler kafkaErrorHandler) {

    ConcurrentKafkaListenerContainerFactory<String, KafkaEvents.AuditEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(auditEventConsumerFactory);
    factory.setConcurrency(2);
    factory.setCommonErrorHandler(kafkaErrorHandler);
    factory.setBatchListener(true);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setShutdownTimeout(30_000L);
    return factory;
  }

  /** Single-record listener factory for LifecycleEventConsumer. */
  @Bean("singleKafkaListenerContainerFactory")
  public ConcurrentKafkaListenerContainerFactory<String, KafkaEvents.LifecycleEvent>
      singleKafkaListenerContainerFactory(
          ConsumerFactory<String, KafkaEvents.LifecycleEvent> lifecycleEventConsumerFactory,
          CommonErrorHandler kafkaErrorHandler) {

    ConcurrentKafkaListenerContainerFactory<String, KafkaEvents.LifecycleEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(lifecycleEventConsumerFactory);
    factory.setConcurrency(2);
    factory.setCommonErrorHandler(kafkaErrorHandler);
    factory.setBatchListener(false);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setShutdownTimeout(30_000L);
    return factory;
  }

  // ── Shared consumer configuration ───────────────────────────────────

  private Map<String, Object> baseConsumerProps() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.urlshortener.*");
    props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);
    return props;
  }
}
