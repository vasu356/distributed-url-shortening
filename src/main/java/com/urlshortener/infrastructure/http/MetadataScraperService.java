package com.urlshortener.infrastructure.http;

import com.urlshortener.domain.repository.ShortUrlRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Asynchronously scrapes OG metadata from target URLs. All scraping is best-effort — failures are
 * silently logged.
 *
 * <p>The {@code @Async} annotation runs this method on the taskExecutor thread pool.
 * {@code @TimeLimiter} wraps the returned CompletableFuture and cancels it after the configured
 * timeout (5 s). The CompletableFuture must be returned directly — do NOT wrap with an additional
 * {@code runAsync()} as that spawns a thread outside the timed future.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataScraperService {

  private static final Pattern OG_TITLE =
      Pattern.compile(
          "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern PLAIN_TITLE =
      Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
  private static final Pattern OG_DESC =
      Pattern.compile(
          "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern FAVICON =
      Pattern.compile(
          "<link[^>]+rel=[\"'](?:shortcut )?icon[\"'][^>]+href=[\"']([^\"']+)[\"']",
          Pattern.CASE_INSENSITIVE);

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  private final ShortUrlRepository shortUrlRepository;

  /**
   * Scrapes OG metadata from the target URL and updates the ShortUrl entity.
   *
   * <p>Returns a CompletableFuture so that {@code @TimeLimiter} can enforce a hard timeout. The
   * {@code @Async} executor runs this method on a platform thread from the taskExecutor pool.
   *
   * @param shortUrlId the UUID of the ShortUrl to update
   * @param longUrl the target URL to scrape
   * @return a CompletableFuture that completes when scraping finishes or is cancelled
   */
  @Async("taskExecutor")
  @CircuitBreaker(name = "metadata-scraper", fallbackMethod = "scrapeFallback")
  @TimeLimiter(name = "metadata-scraper", fallbackMethod = "scrapeFallback")
  public CompletableFuture<Void> scrapeAndUpdate(UUID shortUrlId, String longUrl) {
    return CompletableFuture.supplyAsync(
        () -> {
          doScrape(shortUrlId, longUrl);
          return null;
        });
  }

  /**
   * Fallback when circuit is open or timeout exceeded — no-op, best-effort only.
   *
   * @param shortUrlId the URL id (unused in fallback)
   * @param longUrl the target URL (unused in fallback)
   * @param ex the exception that triggered the fallback
   * @return a completed future with null
   */
  public CompletableFuture<Void> scrapeFallback(UUID shortUrlId, String longUrl, Throwable ex) {
    log.warn(
        "Metadata scraper circuit open or timed out for shortUrlId={}: {}",
        shortUrlId,
        ex.getMessage());
    return CompletableFuture.completedFuture(null);
  }

  private void doScrape(UUID shortUrlId, String longUrl) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(longUrl))
              .header("User-Agent", "URLShortener/1.0 Metadata-Bot")
              .header("Accept", "text/html")
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();

      HttpResponse<String> response =
          HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return;
      }

      String html = response.body();
      if (html.length() > 100_000) {
        html = html.substring(0, 100_000);
      }

      String title = extract(OG_TITLE, html);
      if (title == null) {
        title = extract(PLAIN_TITLE, html);
      }
      String description = extract(OG_DESC, html);
      String faviconUrl = extractFavicon(html, longUrl);

      final String finalTitle = title;
      final String finalDesc = description;
      final String finalFavicon = faviconUrl;

      shortUrlRepository
          .findById(shortUrlId)
          .ifPresent(
              url -> {
                if (finalTitle != null) {
                  url.setTitle(finalTitle.trim());
                }
                if (finalDesc != null) {
                  url.setDescription(finalDesc.trim());
                }
                if (finalFavicon != null) {
                  url.setFaviconUrl(finalFavicon);
                }
                shortUrlRepository.save(url);
              });

    } catch (Exception ex) {
      log.warn("Metadata scrape failed shortUrlId={}: {}", shortUrlId, ex.getMessage());
    }
  }

  private String extract(Pattern p, String html) {
    Matcher m = p.matcher(html);
    return m.find() ? m.group(1) : null;
  }

  private String extractFavicon(String html, String baseUrl) {
    String href = extract(FAVICON, html);
    if (href == null) {
      return null;
    }
    if (href.startsWith("http")) {
      return href;
    }
    try {
      URI base = URI.create(baseUrl);
      return base.getScheme() + "://" + base.getHost() + (href.startsWith("/") ? href : "/" + href);
    } catch (Exception e) {
      return null;
    }
  }
}
