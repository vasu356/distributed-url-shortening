package com.urlshortener.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.domain.model.ShortUrl;
import com.urlshortener.domain.model.User;
import com.urlshortener.integration.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DisplayName("ShortUrlRepository integration tests")
class ShortUrlRepositoryTest extends AbstractIntegrationTest {

  @Autowired private ShortUrlRepository shortUrlRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  private User testUser;

  @BeforeEach
  void setUp() {
    testUser = User.create("repo-test@example.com", "hash");
    testUser = userRepository.save(testUser);
  }

  @Test
  @DisplayName("findByShortCode returns URL when exists")
  void findByShortCode_exists() {
    ShortUrl url = ShortUrl.create(testUser, "abc1234", "https://example.com", false);
    shortUrlRepository.save(url);

    Optional<ShortUrl> found = shortUrlRepository.findByShortCode("abc1234");

    assertThat(found).isPresent();
    assertThat(found.get().getLongUrl()).isEqualTo("https://example.com");
  }

  @Test
  @DisplayName("findByShortCode returns empty for unknown code")
  void findByShortCode_notFound() {
    assertThat(shortUrlRepository.findByShortCode("xxxxxxx")).isEmpty();
  }

  @Test
  @DisplayName("existsByShortCode returns true when code exists")
  void existsByShortCode_true() {
    shortUrlRepository.save(ShortUrl.create(testUser, "exists1", "https://a.com", false));
    assertThat(shortUrlRepository.existsByShortCode("exists1")).isTrue();
  }

  @Test
  @DisplayName("existsByShortCode returns false when code absent")
  void existsByShortCode_false() {
    assertThat(shortUrlRepository.existsByShortCode("absent1")).isFalse();
  }

  @Test
  @DisplayName("findByUserIdAndDeletedAtIsNull returns only non-deleted URLs")
  void findByUser_excludesDeleted() {
    ShortUrl active = ShortUrl.create(testUser, "active1", "https://active.com", false);
    ShortUrl deleted = ShortUrl.create(testUser, "delet1x", "https://deleted.com", false);
    deleted.softDelete();

    shortUrlRepository.saveAll(List.of(active, deleted));

    Page<ShortUrl> page =
        shortUrlRepository.findByUserIdAndDeletedAtIsNull(testUser.getId(), PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getShortCode()).isEqualTo("active1");
  }

  @Test
  @DisplayName("incrementClickCount atomically increments the counter")
  void incrementClickCount_atomic() {
    ShortUrl url =
        shortUrlRepository.save(ShortUrl.create(testUser, "click11", "https://clicks.com", false));
    assertThat(url.getClickCount()).isZero();

    int updated = shortUrlRepository.incrementClickCount(url.getId());
    assertThat(updated).isEqualTo(1);

    // @Modifying queries bypass the JPA L1 cache; flush + clear forces a fresh DB load
    shortUrlRepository.flush();
    entityManager.clear();
    ShortUrl reloaded = shortUrlRepository.findById(url.getId()).orElseThrow();
    assertThat(reloaded.getClickCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("findExpiredUrls returns only active URLs past expiry")
  void findExpiredUrls_correctlyFiltered() {
    Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

    ShortUrl expired = ShortUrl.create(testUser, "exprd1x", "https://exp.com", false);
    expired.setExpiresAt(past);

    ShortUrl notExpired = ShortUrl.create(testUser, "notexp1", "https://nexp.com", false);
    notExpired.setExpiresAt(future);

    ShortUrl alreadyInactive = ShortUrl.create(testUser, "inact1x", "https://ia.com", false);
    alreadyInactive.setExpiresAt(past);
    alreadyInactive.softDelete();

    shortUrlRepository.saveAll(List.of(expired, notExpired, alreadyInactive));

    List<ShortUrl> found = shortUrlRepository.findExpiredUrls(Instant.now());

    assertThat(found).hasSize(1);
    assertThat(found.get(0).getShortCode()).isEqualTo("exprd1x");
  }

  @Test
  @DisplayName("full-text search finds URL by title keyword")
  void searchByUserAndQuery_findsMatch() {
    ShortUrl url = ShortUrl.create(testUser, "srch001", "https://github.com", false);
    url.setTitle("GitHub: The Developer Platform");
    shortUrlRepository.save(url);

    // Wait for FTS index to be available within transaction
    shortUrlRepository.flush();

    Page<ShortUrl> results =
        shortUrlRepository.searchByUserAndQuery(
            testUser.getId(),
            "developer",
            PageRequest.of(0, 10, Sort.by("createdAt").descending()));

    assertThat(results.getContent()).isNotEmpty();
    assertThat(results.getContent().get(0).getShortCode()).isEqualTo("srch001");
  }

  @Test
  @DisplayName("countByUserIdAndActiveTrue returns correct count")
  void countByUserIdAndActiveTrue() {
    shortUrlRepository.save(ShortUrl.create(testUser, "cnt0001", "https://a.com", false));
    shortUrlRepository.save(ShortUrl.create(testUser, "cnt0002", "https://b.com", false));
    ShortUrl inactive = ShortUrl.create(testUser, "cnt0003", "https://c.com", false);
    inactive.softDelete();
    shortUrlRepository.save(inactive);

    assertThat(shortUrlRepository.countByUserIdAndActiveTrue(testUser.getId())).isEqualTo(2);
  }
}
