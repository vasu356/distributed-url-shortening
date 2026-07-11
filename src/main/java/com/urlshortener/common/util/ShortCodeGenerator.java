package com.urlshortener.common.util;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.urlshortener.domain.repository.ShortUrlRepository;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Generates unique short codes with Bloom filter pre-check.
 *
 * <p>Algorithm: 1. Generate random Base62 string (7 chars) 2. Check Bloom filter (in-memory,
 * probabilistic) — eliminates 99.9% of DB lookups 3. On Bloom false positive: check DB (source of
 * truth) 4. On true collision: regenerate (max 5 attempts before throwing) 5. On success: add to
 * Bloom filter + DB insert
 *
 * <p>Bloom filter sizing: - Expected insertions: 10M URLs (tune per deployment) - False positive
 * rate: 0.1% - Memory: ~14MB (acceptable for JVM heap)
 *
 * <p>Thread safety: BloomFilter.put() is thread-safe in Guava. SecureRandom is thread-safe.
 *
 * <p>Limitation: Bloom filter is in-process. In a multi-replica deployment, each replica has its
 * own Bloom filter. This means a code checked as "not exists" in one replica might exist from
 * another replica's inserts. The DB unique constraint is the ultimate safety net. The Bloom filter
 * exists purely for performance, not correctness.
 *
 * <p>Future: A Redis-backed Bloom filter (RedisBloom module) would provide cross-replica
 * consistency. Currently deprioritized: the collision → DB check → regenerate path is fast enough.
 */
@Component
@Slf4j
public class ShortCodeGenerator {

  private static final int MAX_ATTEMPTS = 5;

  private final Base62Encoder encoder;
  private final ShortUrlRepository shortUrlRepository;
  private final int shortCodeLength;
  private final BloomFilter<String> bloomFilter;

  public ShortCodeGenerator(
      Base62Encoder encoder,
      ShortUrlRepository shortUrlRepository,
      @Value("${app.url.short-code-length:7}") int shortCodeLength) {
    this.encoder = encoder;
    this.shortUrlRepository = shortUrlRepository;
    this.shortCodeLength = shortCodeLength;

    // 10M expected insertions, 0.1% false positive rate → ~14MB memory
    this.bloomFilter =
        BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 10_000_000, 0.001);

    log.info("ShortCodeGenerator initialized: length={}, bloomFilter=10M/0.1%", shortCodeLength);
  }

  /**
   * Generate a unique short code. Not transactional — caller must insert atomically with DB unique
   * constraint as the final guard.
   */
  public String generate() {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      String code = encoder.generate(shortCodeLength);

      // Fast path: Bloom filter says definitely not present
      if (!bloomFilter.mightContain(code)) {
        bloomFilter.put(code);
        log.debug("Generated short code '{}' (attempt {}, bloom miss)", code, attempt);
        return code;
      }

      // Bloom false positive (or genuine collision): check DB
      if (!shortUrlRepository.existsByShortCode(code)) {
        bloomFilter.put(code);
        log.debug("Generated short code '{}' (attempt {}, bloom fp, db miss)", code, attempt);
        return code;
      }

      log.debug("Short code collision '{}' (attempt {}), regenerating", code, attempt);
    }

    throw new IllegalStateException(
        "Failed to generate unique short code after "
            + MAX_ATTEMPTS
            + " attempts. "
            + "This indicates either extreme collision rate or a bug.");
  }

  /** Register a code as used (for custom aliases or after successful DB insert). */
  public void register(String code) {
    bloomFilter.put(code);
  }
}
