package com.urlshortener.infrastructure.kafka.consumer;

import com.urlshortener.infrastructure.http.MetadataScraperService;
import com.urlshortener.infrastructure.http.QrCodeService;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleEventConsumer {

  private final MetadataScraperService metadataScraperService;
  private final QrCodeService qrCodeService;

  @KafkaListener(
      topics = "${app.kafka.topics.lifecycle-events}",
      groupId = "${app.kafka.consumer-groups.qr-generator}",
      containerFactory = "singleKafkaListenerContainerFactory")
  public void consume(
      ConsumerRecord<String, KafkaEvents.LifecycleEvent> record, Acknowledgment ack) {

    KafkaEvents.LifecycleEvent event = record.value();
    if (event == null) {
      ack.acknowledge();
      return;
    }

    try {
      switch (event.eventType()) {
        case "CREATED" -> {
          metadataScraperService.scrapeAndUpdate(event.shortUrlId(), event.longUrl());
          qrCodeService.generateAndCache(event.shortCode());
        }
        case "DELETED", "EXPIRED" -> qrCodeService.evict(event.shortCode());
        default -> log.debug("Ignoring lifecycle event type: {}", event.eventType());
      }
    } catch (Exception ex) {
      log.warn(
          "Lifecycle event processing failed for shortCode={}: {}",
          event.shortCode(),
          ex.getMessage());
    }
    ack.acknowledge();
  }
}
