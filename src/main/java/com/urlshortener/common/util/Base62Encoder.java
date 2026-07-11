package com.urlshortener.common.util;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Base62 encoder for short code generation.
 *
 * <p>Alphabet: [0-9A-Za-z] — 62 characters. 7 characters gives 62^7 ≈ 3.5 trillion unique codes. At
 * 500 URLs/sec, this space lasts ~220 years.
 *
 * <p>SecureRandom vs Random: SecureRandom is cryptographically secure, preventing short code
 * enumeration attacks. The slight performance cost is negligible at URL creation frequency.
 *
 * <p>Collision handling: Bloom filter pre-check (probabilistic, fast) → DB unique constraint
 * (authoritative). Collision probability at 1B URLs: ~1-e^(-1B^2/(2*3.5T)) ≈ 13%.
 */
@Component
public class Base62Encoder {

  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int BASE = ALPHABET.length(); // 62
  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Generate a random Base62 string of the given length. Thread-safe: SecureRandom is thread-safe
   * internally.
   *
   * @param length the desired string length
   * @return a random Base62-encoded string
   */
  public String generate(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET.charAt(RANDOM.nextInt(BASE)));
    }
    return sb.toString();
  }

  /**
   * Encode a long value to Base62. Used for ID-based code generation (alternative strategy).
   *
   * @param value the long value to encode
   * @return Base62-encoded string
   */
  public String encode(long value) {
    if (value == 0) {
      return String.valueOf(ALPHABET.charAt(0));
    }
    StringBuilder sb = new StringBuilder();
    long remaining = value;
    while (remaining > 0) {
      sb.insert(0, ALPHABET.charAt((int) (remaining % BASE)));
      remaining /= BASE;
    }
    return sb.toString();
  }

  /**
   * Decode a Base62 string back to a long.
   *
   * @param encoded the Base62-encoded string
   * @return the decoded long value
   */
  public long decode(String encoded) {
    long result = 0;
    for (char c : encoded.toCharArray()) {
      result = result * BASE + ALPHABET.indexOf(c);
    }
    return result;
  }

  /**
   * Validate that a string contains only Base62 characters.
   *
   * @param s the string to validate
   * @return true if the string is a valid Base62 value
   */
  public boolean isValidBase62(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    for (char c : s.toCharArray()) {
      if (ALPHABET.indexOf(c) == -1) {
        return false;
      }
    }
    return true;
  }
}
