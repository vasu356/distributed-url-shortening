package com.urlshortener.infrastructure.http;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * QR code generation and caching.
 *
 * <p>Generated QR codes are cached in Redis as Base64-encoded PNG strings. TTL = 24 hours. Storing
 * as Base64 string ensures reliable serialization via StringRedisSerializer without requiring type
 * hints or a byte[]-specific serializer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QrCodeService {

  private static final String QR_CACHE_PREFIX = "qr:";
  private static final Duration QR_CACHE_TTL = Duration.ofHours(24);
  private static final int DEFAULT_SIZE = 300; // pixels

  private final RedisTemplate<String, Object> redisTemplate;

  @Value("${app.base-url}")
  private String baseUrl;

  @Async("taskExecutor")
  public void generateAndCache(String shortCode) {
    try {
      byte[] qrBytes = generate(shortCode, DEFAULT_SIZE);
      String encoded = Base64.getEncoder().encodeToString(qrBytes);
      String key = QR_CACHE_PREFIX + shortCode;
      redisTemplate.opsForValue().set(key, encoded, QR_CACHE_TTL);
      log.debug("Pre-generated and cached QR for shortCode={}", shortCode);
    } catch (Exception ex) {
      log.warn("Failed to pre-generate QR for shortCode={}: {}", shortCode, ex.getMessage());
    }
  }

  public byte[] getOrGenerate(String shortCode, int size) {
    String key = QR_CACHE_PREFIX + shortCode;
    Object cached = redisTemplate.opsForValue().get(key);
    if (cached instanceof String encoded) {
      try {
        return Base64.getDecoder().decode(encoded);
      } catch (IllegalArgumentException ex) {
        log.warn("Corrupted QR cache entry for shortCode={}, regenerating", shortCode);
      }
    }

    byte[] generated = generate(shortCode, size);
    redisTemplate
        .opsForValue()
        .set(key, Base64.getEncoder().encodeToString(generated), QR_CACHE_TTL);
    return generated;
  }

  public void evict(String shortCode) {
    redisTemplate.delete(QR_CACHE_PREFIX + shortCode);
  }

  private byte[] generate(String shortCode, int size) {
    String url = baseUrl + "/r/" + shortCode;
    QRCodeWriter writer = new QRCodeWriter();

    Map<EncodeHintType, Object> hints =
        Map.of(
            EncodeHintType.ERROR_CORRECTION,
            ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN,
            1,
            EncodeHintType.CHARACTER_SET,
            "UTF-8");

    try {
      BitMatrix matrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size, hints);
      BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(image, "PNG", baos);
      return baos.toByteArray();
    } catch (WriterException | IOException ex) {
      throw new IllegalStateException("Failed to generate QR code for " + shortCode, ex);
    }
  }
}
