package com.urlshortener.infrastructure.kafka.consumer;

import com.urlshortener.domain.model.ClickEvent;
import com.urlshortener.domain.repository.ClickEventRepository;
import com.urlshortener.domain.repository.ShortUrlRepository;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes click events from Kafka and batch-inserts them into PostgreSQL.
 *
 * <p>The redirect controller publishes events with a null shortUrlId (to keep the redirect path at
 * single-cache-lookup latency). This consumer resolves shortUrlId from shortCode using a batch
 * lookup, then bulk-inserts all click events in one round-trip.
 *
 * <p>Idempotency: each event has a UUID id generated at creation. DB primary key prevents duplicate
 * inserts on Kafka at-least-once redelivery.
 *
 * <p>Ordering: events for the same shortCode arrive on the same partition (keyed by shortCode), so
 * ordering is preserved within a partition.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClickEventConsumer {

  private final ClickEventRepository clickEventRepository;
  private final ShortUrlRepository shortUrlRepository;
  private final MeterRegistry meterRegistry;

  @KafkaListener(
      topics = "${app.kafka.topics.click-events}",
      groupId = "${app.kafka.consumer-groups.analytics}",
      containerFactory = "kafkaListenerContainerFactory",
      batch = "true")
  @Transactional
  public void consume(
      List<ConsumerRecord<String, KafkaEvents.ClickEvent>> records, Acknowledgment ack) {

    if (records.isEmpty()) {
      ack.acknowledge();
      return;
    }

    Timer.Sample timer = Timer.start(meterRegistry);

    try {
      // --- Step 1: resolve shortCode → shortUrlId in one batch query ---
      List<String> shortCodes =
          records.stream()
              .filter(r -> r.value() != null)
              .map(r -> r.value().shortCode())
              .distinct()
              .toList();

      Map<String, UUID> codeToId =
          shortUrlRepository.findAllByShortCodeIn(shortCodes).stream()
              .collect(Collectors.toMap(su -> su.getShortCode(), su -> su.getId()));

      // --- Step 2: build ClickEvent entities ---
      List<ClickEvent> entities = new ArrayList<>(records.size());
      Map<UUID, Long> clickCounts = new HashMap<>();

      for (ConsumerRecord<String, KafkaEvents.ClickEvent> record : records) {
        KafkaEvents.ClickEvent event = record.value();
        if (event == null) {
          continue;
        }

        UUID urlId =
            event.shortUrlId() != null ? event.shortUrlId() : codeToId.get(event.shortCode());

        if (urlId == null) {
          log.warn("Could not resolve shortUrlId for shortCode={}, skipping", event.shortCode());
          continue;
        }

        entities.add(
            ClickEvent.create(
                urlId,
                event.ipHash(),
                event.countryCode(),
                event.city(),
                event.userAgent(),
                event.referrer(),
                parseDeviceType(event.deviceType()),
                event.browser(),
                event.os()));

        clickCounts.merge(urlId, 1L, Long::sum);
      }

      // --- Step 3: batch insert all events ---
      if (!entities.isEmpty()) {
        clickEventRepository.saveAll(entities);
      }

      // --- Step 4: increment denormalised click_count per URL ---
      clickCounts.forEach(
          (urlId, count) -> {
            if (count > 0) {
              shortUrlRepository.incrementClickCountsBatch(List.of(urlId), count.intValue());
            }
          });

      ack.acknowledge();

      timer.stop(meterRegistry.timer("kafka.consumer.batch.duration", "topic", "click-events"));
      meterRegistry
          .counter("kafka.consumer.records.processed", "topic", "click-events")
          .increment(entities.size());

      log.debug("Processed {} click events from {} records", entities.size(), records.size());

    } catch (Exception ex) {
      log.error(
          "Failed to process click event batch size={}: {}", records.size(), ex.getMessage(), ex);
      meterRegistry.counter("kafka.consumer.batch.failed", "topic", "click-events").increment();
      throw ex; // rethrow → Kafka retry → DLT after max retries
    }
  }

  private ClickEvent.DeviceType parseDeviceType(String type) {
    if (type == null) {
      return ClickEvent.DeviceType.UNKNOWN;
    }
    try {
      return ClickEvent.DeviceType.valueOf(type.toUpperCase());
    } catch (IllegalArgumentException e) {
      return ClickEvent.DeviceType.UNKNOWN;
    }
  }
}
