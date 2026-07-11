package com.urlshortener.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.urlshortener.api.v1.dto.request.UrlDtos;
import com.urlshortener.api.v1.dto.response.UrlResponse;
import com.urlshortener.common.exception.Exceptions;
import com.urlshortener.common.util.ShortCodeGenerator;
import com.urlshortener.domain.model.ShortUrl;
import com.urlshortener.domain.model.User;
import com.urlshortener.domain.repository.ShortUrlRepository;
import com.urlshortener.domain.repository.UserRepository;
import com.urlshortener.domain.service.UrlValidationService;
import com.urlshortener.infrastructure.cache.CachedUrl;
import com.urlshortener.infrastructure.cache.UrlCacheService;
import com.urlshortener.infrastructure.kafka.producer.EventProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlUseCase")
class UrlUseCaseTest {

  @Mock private ShortUrlRepository shortUrlRepository;
  @Mock private UserRepository userRepository;
  @Mock private ShortCodeGenerator shortCodeGenerator;
  @Mock private UrlValidationService urlValidationService;
  @Mock private UrlCacheService urlCacheService;
  @Mock private EventProducer eventProducer;

  private UrlUseCase urlUseCase;
  private User testUser;
  private static final UUID USER_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    urlUseCase =
        new UrlUseCase(
            shortUrlRepository,
            userRepository,
            shortCodeGenerator,
            urlValidationService,
            urlCacheService,
            eventProducer,
            new BCryptPasswordEncoder(4),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(urlUseCase, "baseUrl", "http://localhost:8080");

    testUser = User.create("test@example.com", "hash");
    ReflectionTestUtils.setField(testUser, "id", USER_ID);
  }

  @Test
  @DisplayName("createUrl generates a short code and saves the URL")
  void createUrl_success() {
    var request =
        new UrlDtos.CreateUrlRequest("https://www.example.com", null, null, 302, null, null);

    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(testUser));
    // ShortCodeGenerator is mocked — production existsByShortCode is never called for
    // non-custom aliases; resolveShortCode delegates entirely to shortCodeGenerator.generate().
    when(shortCodeGenerator.generate()).thenReturn("abc1234");

    ShortUrl saved = ShortUrl.create(testUser, "abc1234", "https://www.example.com", false);
    ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
    when(shortUrlRepository.save(any())).thenReturn(saved);

    UrlResponse response = urlUseCase.createUrl(request, USER_ID);

    assertThat(response.shortCode()).isEqualTo("abc1234");
    assertThat(response.longUrl()).isEqualTo("https://www.example.com");
    assertThat(response.shortUrl()).startsWith("http://localhost:8080/r/");
    verify(urlValidationService).validate("https://www.example.com");
    verify(shortUrlRepository).save(any(ShortUrl.class));
    verify(eventProducer).publishLifecycleEvent(any());
  }

  @Test
  @DisplayName("createUrl with custom alias checks alias availability")
  void createUrl_customAlias_checksConflict() {
    var request =
        new UrlDtos.CreateUrlRequest("https://www.example.com", "my-alias", null, 302, null, null);

    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(testUser));
    when(shortUrlRepository.existsByShortCode("my-alias")).thenReturn(true);

    assertThatThrownBy(() -> urlUseCase.createUrl(request, USER_ID))
        .isInstanceOf(Exceptions.AliasAlreadyExistsException.class);

    verify(shortCodeGenerator, never()).generate();
  }

  @Test
  @DisplayName("resolveForRedirect returns cached URL on cache hit")
  void resolveForRedirect_cacheHit() {
    var cachedUrl = new CachedUrl("https://www.example.com", true, null, null, 0L, 302);
    when(urlCacheService.get("abc1234")).thenReturn(Optional.of(cachedUrl));

    String result = urlUseCase.resolveForRedirect("abc1234");

    assertThat(result).isEqualTo("https://www.example.com");
    verify(shortUrlRepository, never()).findByShortCode(anyString());
  }

  @Test
  @DisplayName("resolveForRedirect falls back to DB on cache miss")
  void resolveForRedirect_cacheMiss_loadsFromDb() {
    when(urlCacheService.get("abc1234")).thenReturn(Optional.empty());

    ShortUrl url = ShortUrl.create(testUser, "abc1234", "https://www.example.com", false);
    when(shortUrlRepository.findByShortCode("abc1234")).thenReturn(Optional.of(url));

    String result = urlUseCase.resolveForRedirect("abc1234");

    assertThat(result).isEqualTo("https://www.example.com");
    verify(urlCacheService).put(eq("abc1234"), any());
  }

  @Test
  @DisplayName("resolveForRedirect throws UrlNotFoundException for unknown code")
  void resolveForRedirect_notFound_throws() {
    when(urlCacheService.get("unknown")).thenReturn(Optional.empty());
    when(shortUrlRepository.findByShortCode("unknown")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> urlUseCase.resolveForRedirect("unknown"))
        .isInstanceOf(Exceptions.UrlNotFoundException.class);
  }

  @Test
  @DisplayName("resolveForRedirect throws UrlExpiredException for expired URL")
  void resolveForRedirect_expired_throws() {
    var expiredCached =
        new CachedUrl(
            "https://www.example.com",
            true,
            1L, // epoch second 1 = long past
            null,
            0L,
            302);
    when(urlCacheService.get("expired")).thenReturn(Optional.of(expiredCached));

    assertThatThrownBy(() -> urlUseCase.resolveForRedirect("expired"))
        .isInstanceOf(Exceptions.UrlExpiredException.class);
  }

  @Test
  @DisplayName("deleteUrl evicts cache and publishes lifecycle event")
  void deleteUrl_evictsCacheAndPublishes() {
    ShortUrl url = ShortUrl.create(testUser, "abc1234", "https://www.example.com", false);
    ReflectionTestUtils.setField(url, "id", UUID.randomUUID());
    when(shortUrlRepository.findByShortCode("abc1234")).thenReturn(Optional.of(url));
    when(shortUrlRepository.save(any())).thenReturn(url);

    urlUseCase.deleteUrl("abc1234", USER_ID);

    verify(urlCacheService).evict("abc1234");
    verify(eventProducer).publishLifecycleEvent(argThat(e -> "DELETED".equals(e.eventType())));
  }

  @Test
  @DisplayName("deleteUrl throws UnauthorizedAccessException for wrong owner")
  void deleteUrl_wrongOwner_throws() {
    User otherUser = User.create("other@example.com", "hash");
    ReflectionTestUtils.setField(otherUser, "id", UUID.randomUUID());

    ShortUrl url = ShortUrl.create(otherUser, "abc1234", "https://www.example.com", false);
    when(shortUrlRepository.findByShortCode("abc1234")).thenReturn(Optional.of(url));

    assertThatThrownBy(() -> urlUseCase.deleteUrl("abc1234", USER_ID))
        .isInstanceOf(Exceptions.UnauthorizedAccessException.class);
  }
}
