package com.urlshortener.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Centralised metrics registration.
 *
 * <p>Metric naming conventions follow Micrometer/Prometheus best practices: - Use dots as
 * separators (Micrometer converts to underscores for Prometheus) - Use consistent tag names across
 * related metrics - Histograms for latency; counters for events; gauges for current state
 *
 * <p>Key metrics for SLO alerting: - url_redirect_duration_seconds{cache="hit|miss"} — p99 < 10ms
 * (hit), < 50ms (miss) - url_created_total — creation rate - kafka_publish_failed_total — should
 * stay 0 - rate_limit_exceeded_total — track abuse patterns
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

  private final MeterRegistry registry;

  // Cache-aside metrics
  public void recordCacheHit(String cacheName) {
    Counter.builder("cache.hit")
        .tag("cache", cacheName)
        .description("Cache hits")
        .register(registry)
        .increment();
  }

  public void recordCacheMiss(String cacheName) {
    Counter.builder("cache.miss")
        .tag("cache", cacheName)
        .description("Cache misses")
        .register(registry)
        .increment();
  }

  // URL operations
  public void incrementUrlCreated(boolean isCustom) {
    Counter.builder("url.created")
        .tag("custom", String.valueOf(isCustom))
        .description("URLs created")
        .register(registry)
        .increment();
  }

  public void incrementUrlDeleted() {
    Counter.builder("url.deleted").description("URLs soft-deleted").register(registry).increment();
  }

  public void incrementUrlExpired() {
    Counter.builder("url.expired")
        .description("URLs expired by scheduler")
        .register(registry)
        .increment();
  }

  // Redirect metrics
  public Timer redirectTimer(String cacheResult) {
    return Timer.builder("url.redirect.duration")
        .tag("cache", cacheResult)
        .description("Redirect resolution time")
        .publishPercentiles(0.5, 0.95, 0.99)
        .publishPercentileHistogram()
        .register(registry);
  }

  // Rate limiting
  public void incrementRateLimitExceeded(String limitType) {
    Counter.builder("rate.limit.exceeded")
        .tag("type", limitType)
        .description("Rate limit violations")
        .register(registry)
        .increment();
  }

  // Kafka
  public void incrementKafkaPublishFailed(String topic) {
    Counter.builder("kafka.publish.failed")
        .tag("topic", topic)
        .description("Kafka publish failures")
        .register(registry)
        .increment();
  }

  // Bloom filter (approximated)
  public void recordBloomFilterCollision() {
    Counter.builder("bloom.filter.collision")
        .description("Bloom filter false positives requiring DB check")
        .register(registry)
        .increment();
  }

  // Gauge: active connections or queue depths (caller manages the AtomicLong)
  public void registerQueueDepthGauge(String queueName, AtomicLong value) {
    Gauge.builder("queue.depth", value, AtomicLong::get)
        .tag("queue", queueName)
        .description("Current depth of a processing queue")
        .register(registry);
  }
}
