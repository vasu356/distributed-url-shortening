package com.urlshortener.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.urlshortener.domain.model.ClickEvent;
import com.urlshortener.domain.repository.ClickEventRepository;
import com.urlshortener.domain.repository.ShortUrlRepository;
import com.urlshortener.infrastructure.kafka.consumer.ClickEventConsumer;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClickEventConsumer unit tests")
class ClickEventConsumerTest {

  @Mock private ClickEventRepository clickEventRepository;
  @Mock private ShortUrlRepository shortUrlRepository;
  @Captor private ArgumentCaptor<List<ClickEvent>> savedEventsCaptor;

  @Test
  @DisplayName("consume batch persists all click events and acknowledges")
  void consume_batchPersistsAndAcknowledges() {
    ClickEventConsumer consumer =
        new ClickEventConsumer(clickEventRepository, shortUrlRepository, new SimpleMeterRegistry());

    UUID urlId = UUID.randomUUID();
    KafkaEvents.ClickEvent event1 =
        KafkaEvents.ClickEvent.of(
            null,
            "abc1234",
            "hash1",
            "US",
            "NYC",
            "Mozilla/5.0",
            "https://google.com",
            "DESKTOP",
            "Chrome",
            "Windows");
    KafkaEvents.ClickEvent event2 =
        KafkaEvents.ClickEvent.of(
            null,
            "abc1234",
            "hash2",
            "GB",
            "London",
            "Safari/605.1",
            null,
            "MOBILE",
            "Safari",
            "iOS");

    var record1 = new ConsumerRecord<>("url.click-events", 0, 0L, "abc1234", event1);
    var record2 = new ConsumerRecord<>("url.click-events", 0, 1L, "abc1234", event2);

    Acknowledgment ack = mock(Acknowledgment.class);
    when(clickEventRepository.saveAll(anyList())).thenReturn(List.of());

    com.urlshortener.domain.model.ShortUrl mockShortUrl =
        mock(com.urlshortener.domain.model.ShortUrl.class);
    when(mockShortUrl.getId()).thenReturn(urlId);
    when(mockShortUrl.getShortCode()).thenReturn("abc1234");
    when(shortUrlRepository.findAllByShortCodeIn(List.of("abc1234")))
        .thenReturn(List.of(mockShortUrl));
    when(shortUrlRepository.incrementClickCountsBatch(anyList(), anyInt())).thenReturn(1);

    consumer.consume(List.of(record1, record2), ack);

    verify(clickEventRepository).saveAll(savedEventsCaptor.capture());
    List<ClickEvent> saved = savedEventsCaptor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved.get(0).getIpHash()).isEqualTo("hash1");
    assertThat(saved.get(0).getCountryCode()).isEqualTo("US");
    assertThat(saved.get(1).getIpHash()).isEqualTo("hash2");
    assertThat(saved.get(1).getDeviceType()).isEqualTo(ClickEvent.DeviceType.MOBILE);

    verify(ack).acknowledge();
  }

  @Test
  @DisplayName("consume empty batch acknowledges without DB call")
  void consume_emptyBatch_acknowledgesWithoutDbCall() {
    ClickEventConsumer consumer =
        new ClickEventConsumer(clickEventRepository, shortUrlRepository, new SimpleMeterRegistry());

    Acknowledgment ack = mock(Acknowledgment.class);
    consumer.consume(List.of(), ack);

    verify(clickEventRepository, never()).saveAll(anyList());
    verify(ack).acknowledge();
  }

  @Test
  @DisplayName("consume skips null event records — no DB write, still acknowledges")
  void consume_nullRecord_skipped() {
    ClickEventConsumer consumer =
        new ClickEventConsumer(clickEventRepository, shortUrlRepository, new SimpleMeterRegistry());

    var nullRecord =
        new ConsumerRecord<String, KafkaEvents.ClickEvent>("url.click-events", 0, 0L, "key", null);

    Acknowledgment ack = mock(Acknowledgment.class);

    // Null records are filtered out before shortCode extraction.
    // shortCodes list is empty → findAllByShortCodeIn([]) is called.
    // entities list remains empty → saveAll is NOT called (guarded by !entities.isEmpty()).
    when(shortUrlRepository.findAllByShortCodeIn(List.of())).thenReturn(List.of());

    consumer.consume(List.of(nullRecord), ack);

    verify(clickEventRepository, never()).saveAll(anyList());
    verify(ack).acknowledge();
  }
}
