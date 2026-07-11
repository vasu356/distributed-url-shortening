package com.urlshortener.domain.repository;

import com.urlshortener.domain.model.AuditLog;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

  Page<AuditLog> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);

  Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
      String entityType, UUID entityId, Pageable pageable);

  Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);

  Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
      Instant from, Instant to, Pageable pageable);

  @Query(
      """
      SELECT al FROM AuditLog al
      WHERE (:actorId IS NULL OR al.actorId = :actorId)
        AND (:action IS NULL OR al.action = :action)
        AND (:entityType IS NULL OR al.entityType = :entityType)
        AND al.createdAt >= :from
        AND al.createdAt <= :to
      ORDER BY al.createdAt DESC
      """)
  Page<AuditLog> findWithFilters(
      @Param("actorId") UUID actorId,
      @Param("action") String action,
      @Param("entityType") String entityType,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);
}
