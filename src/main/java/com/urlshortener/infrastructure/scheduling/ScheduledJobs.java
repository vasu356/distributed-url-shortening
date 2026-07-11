package com.urlshortener.infrastructure.scheduling;

import com.urlshortener.domain.model.ShortUrl;
import com.urlshortener.domain.repository.ShortUrlRepository;
import com.urlshortener.infrastructure.cache.UrlCacheService;
import com.urlshortener.infrastructure.kafka.producer.EventProducer;
import com.urlshortener.infrastructure.kafka.producer.KafkaEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled background jobs.
 *
 * <p>Uses fixedDelay (not fixedRate) so jobs don't overlap if they run long.
 *
 * <p>In a multi-replica deployment add ShedLock to guarantee single-execution:
 * {@code @SchedulerLock(name = "expireUrls", lockAtMostFor = "PT55S")} All jobs here are idempotent
 * so running on every replica is safe but wasteful.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobs {

  private final ShortUrlRepository shortUrlRepository;
  private final UrlCacheService urlCacheService;
  private final EventProducer eventProducer;
  private final MeterRegistry meterRegistry;

  /** Mark expired URLs as inactive and evict them from cache. Runs every 60 s. */
  @Scheduled(fixedDelay = 60_000)
  @Transactional
  public void expireUrls() {
    List<ShortUrl> expired = shortUrlRepository.findExpiredUrls(Instant.now());
    if (expired.isEmpty()) {
      return;
    }

    log.info("Expiring {} URLs", expired.size());
    for (ShortUrl url : expired) {
      url.expire();
      urlCacheService.evict(url.getShortCode());
      eventProducer.publishLifecycleEvent(
          KafkaEvents.LifecycleEvent.of(
              url.getId(), url.getShortCode(), url.getLongUrl(), "EXPIRED", url.getUser().getId()));
    }
    shortUrlRepository.saveAll(expired);
    meterRegistry.counter("scheduler.urls.expired").increment(expired.size());
  }

  /** Hard-delete URLs soft-deleted more than 30 days ago. Runs every hour. */
  @Scheduled(fixedDelay = 3_600_000)
  @Transactional
  public void hardDeleteTombstonedUrls() {
    Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);
    List<ShortUrl> toDelete = shortUrlRepository.findUrlsReadyForHardDelete(threshold);
    if (toDelete.isEmpty()) {
      return;
    }

    log.info("Hard-deleting {} tombstoned URLs", toDelete.size());
    shortUrlRepository.deleteAll(toDelete);
    meterRegistry.counter("scheduler.urls.hard_deleted").increment(toDelete.size());
  }

  /** Evict URLs with no clicks in 7 days from Redis cache. Runs daily at 03:00. */
  @Scheduled(cron = "0 0 3 * * *")
  public void evictColdUrlsFromCache() {
    Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
    List<UUID> inactiveIds = shortUrlRepository.findInactiveUrlIds(sevenDaysAgo, 10_000);
    if (inactiveIds.isEmpty()) {
      return;
    }

    log.info("Evicting {} cold URLs from cache", inactiveIds.size());
    shortUrlRepository
        .findAllById(inactiveIds)
        .forEach(url -> urlCacheService.evict(url.getShortCode()));
    meterRegistry.counter("scheduler.cache.evictions").increment(inactiveIds.size());
  }
}
