package com.urlshortener.domain.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Core user entity.
 *
 * <p>Design decisions: - UUID PK: avoids sequential ID enumeration attacks, safe to expose in APIs.
 * - No @Data on JPA entities: Lombok @Data generates equals/hashCode based on all fields, which
 * breaks Hibernate proxy equality and causes issues with collections. - @NoArgsConstructor with
 * PROTECTED: JPA requires no-arg constructor, but client code should not call it. - password_hash
 * column: raw password never stored, only BCrypt hash. - Soft-delete not applied to users; use
 * is_active flag with audit trail instead.
 */
@Entity
@Table(
    name = "users",
    indexes = {@Index(name = "idx_users_email", columnList = "email")})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, unique = true, length = 255)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role = Role.USER;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Bidirectional mapping — cascade only for lifecycle coherence. LAZY loading is mandatory: never
   * fetch urls when loading a user.
   */
  @OneToMany(
      mappedBy = "user",
      cascade = CascadeType.ALL,
      fetch = FetchType.LAZY,
      orphanRemoval = true)
  private List<ShortUrl> shortUrls = new ArrayList<>();

  @OneToMany(
      mappedBy = "user",
      cascade = CascadeType.ALL,
      fetch = FetchType.LAZY,
      orphanRemoval = true)
  private List<ApiKey> apiKeys = new ArrayList<>();

  /**
   * Factory method — prefer over constructor to enforce invariants.
   *
   * @param email the user's email address (lowercased and trimmed on creation)
   * @param passwordHash the BCrypt hash of the user's password
   * @return a new active User with USER role
   */
  public static User create(String email, String passwordHash) {
    User user = new User();
    user.email = email.toLowerCase().trim();
    user.passwordHash = passwordHash;
    user.role = Role.USER;
    user.active = true;
    return user;
  }

  /** Deactivates this user account. */
  public void deactivate() {
    this.active = false;
  }

  /** Promotes this user to the ADMIN role. */
  public void promoteToAdmin() {
    this.role = Role.ADMIN;
  }

  /**
   * Equals/hashCode based on business key (email), not surrogate PK.
   *
   * <p>{@inheritDoc}
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof User)) {
      return false;
    }
    User other = (User) o;
    return email != null && email.equals(other.email);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  /** Application roles. */
  public enum Role {
    USER,
    ADMIN
  }
}
