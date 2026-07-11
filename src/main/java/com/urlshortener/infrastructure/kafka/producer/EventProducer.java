package com.urlshortener.infrastructure.kafka.producer;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

/**
 * Kafka event producers.
 *
 * <p>All publishes are fire-and-forget from the caller's perspective. The KafkaTemplate handles: -
 * Idempotent producer (deduplication at broker level) - Retry with backoff - Async callback logging
 * for observability
 *
 * <p>The redirect path (ClickEventProducer) is on the critical hot path. We use async send with
 * non-blocking callback to achieve ~0ms overhead on the redirect response time. A failed publish is
 * logged and metered but does NOT fail the redirect — analytics loss is acceptable, redirect
 * unavailability is not.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventProducer {

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final MeterRegistry meterRegistry;

  @Value("${app.kafka.topics.click-events}")
  private String clickEventsTopic;

  @Value("${app.kafka.topics.audit-events}")
  private String auditEventsTopic;

  @Value("${app.kafka.topics.lifecycle-events}")
  private String lifecycleEventsTopic;

  /**
   * Publish a click event. Called on every successful redirect. Key = shortCode (never null on the
   * hot path; shortUrlId is resolved by the consumer) to ensure per-URL ordering within a partition
   * without a NullPointerException. Fire-and-forget: does not block the redirect response.
   */
  public void publishClickEvent(KafkaEvents.ClickEvent event) {
    CompletableFuture<SendResult<String, Object>> future =
        kafkaTemplate.send(clickEventsTopic, event.shortCode(), event);

    future.whenComplete(
        (result, ex) -> {
          if (ex != null) {
            log.error(
                "Failed to publish click event shortCode={}: {}",
                event.shortCode(),
                ex.getMessage());
            meterRegistry.counter("kafka.publish.failed", "topic", "click-events").increment();
          } else {
            meterRegistry.counter("kafka.publish.success", "topic", "click-events").increment();
          }
        });
  }

  /** Publish an audit event. Called on every significant mutation. */
  public void publishAuditEvent(KafkaEvents.AuditEvent event) {
    String key = event.actorId() != null ? event.actorId().toString() : "system";
    CompletableFuture<SendResult<String, Object>> future =
        kafkaTemplate.send(auditEventsTopic, key, event);

    future.whenComplete(
        (result, ex) -> {
          if (ex != null) {
            log.error(
                "Failed to publish audit event action={}: {}", event.action(), ex.getMessage());
            meterRegistry.counter("kafka.publish.failed", "topic", "audit-events").increment();
          }
        });
  }

  /** Publish a URL lifecycle event. */
  public void publishLifecycleEvent(KafkaEvents.LifecycleEvent event) {
    CompletableFuture<SendResult<String, Object>> future =
        kafkaTemplate.send(lifecycleEventsTopic, event.shortUrlId().toString(), event);

    future.whenComplete(
        (result, ex) -> {
          if (ex != null) {
            log.error(
                "Failed to publish lifecycle event type={} shortCode={}: {}",
                event.eventType(),
                event.shortCode(),
                ex.getMessage());
          }
        });
  }
}
