package com.urlshortener.domain.repository;

import com.urlshortener.domain.model.ApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

  Optional<ApiKey> findByKeyHash(String keyHash);

  List<ApiKey> findByUserIdAndRevokedFalse(UUID userId);

  List<ApiKey> findByUserId(UUID userId);

  @Modifying
  @Transactional
  @Query("UPDATE ApiKey k SET k.lastUsedAt = CURRENT_TIMESTAMP WHERE k.id = :id")
  void updateLastUsed(@Param("id") UUID id);

  long countByUserIdAndRevokedFalse(UUID userId);
}
