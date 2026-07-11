package com.urlshortener.infrastructure.kafka.consumer;

import com.urlshortener.domain.model.AuditLog;
import com.urlshortener.domain.repository.AuditLogRepository;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes audit events from Kafka and batch-inserts them into the audit_logs table.
 *
 * <p>All significant mutations in the system publish an {@link KafkaEvents.AuditEvent}. This
 * consumer persists them as append-only {@link AuditLog} records for forensics and compliance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventConsumer {

  private final AuditLogRepository auditLogRepository;

  /**
   * Processes a batch of audit events and persists them to the database.
   *
   * @param records the Kafka consumer records containing audit events
   * @param ack the acknowledgment to commit once processing succeeds
   */
  @KafkaListener(
      topics = "${app.kafka.topics.audit-events}",
      groupId = "${app.kafka.consumer-groups.audit}",
      containerFactory = "auditKafkaListenerContainerFactory",
      batch = "true")
  @Transactional
  public void consume(
      List<ConsumerRecord<String, KafkaEvents.AuditEvent>> records, Acknowledgment ack) {

    List<AuditLog> logs = new ArrayList<>(records.size());

    for (ConsumerRecord<String, KafkaEvents.AuditEvent> record : records) {
      KafkaEvents.AuditEvent event = record.value();
      if (event == null) {
        continue;
      }

      AuditLog auditLog =
          AuditLog.create(
              event.actorId(),
              parseActorType(event.actorType()),
              event.action(),
              event.entityType(),
              event.entityId(),
              event.oldValue(),
              event.newValue(),
              event.ipAddress(),
              event.userAgent());

      logs.add(auditLog);
    }

    if (!logs.isEmpty()) {
      auditLogRepository.saveAll(logs);
    }
    ack.acknowledge();
    log.debug("Persisted {} audit events", logs.size());
  }

  private AuditLog.ActorType parseActorType(String type) {
    if (type == null) {
      return AuditLog.ActorType.SYSTEM;
    }
    try {
      return AuditLog.ActorType.valueOf(type.toUpperCase());
    } catch (IllegalArgumentException e) {
      return AuditLog.ActorType.SYSTEM;
    }
  }
}
