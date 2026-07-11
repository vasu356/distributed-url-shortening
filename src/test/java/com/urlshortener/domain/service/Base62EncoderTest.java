package com.urlshortener.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.common.util.Base62Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Base62Encoder")
class Base62EncoderTest {

  private Base62Encoder encoder;

  @BeforeEach
  void setUp() {
    encoder = new Base62Encoder();
  }

  @Test
  @DisplayName("generate(7) produces 7-character strings")
  void generate_producesCorrectLength() {
    String code = encoder.generate(7);
    assertThat(code).hasSize(7);
  }

  @RepeatedTest(100)
  @DisplayName("generate(7) always produces valid Base62 characters")
  void generate_alwaysValidBase62() {
    String code = encoder.generate(7);
    assertThat(code).matches("[0-9A-Za-z]{7}");
  }

  @Test
  @DisplayName("generate produces different values across calls")
  void generate_producesUniqueValues() {
    var codes = new java.util.HashSet<String>();
    for (int i = 0; i < 1000; i++) {
      codes.add(encoder.generate(7));
    }
    // With 62^7 ≈ 3.5T possible values, 1000 samples should all be unique
    assertThat(codes).hasSize(1000);
  }

  @Test
  @DisplayName("encode/decode round-trip is lossless")
  void encodeDecodeRoundTrip() {
    long original = 123456789L;
    String encoded = encoder.encode(original);
    long decoded = encoder.decode(encoded);
    assertThat(decoded).isEqualTo(original);
  }

  @Test
  @DisplayName("encode(0) returns single character")
  void encode_zero() {
    assertThat(encoder.encode(0)).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 61L, 62L, 3843L, Long.MAX_VALUE / 1000})
  @DisplayName("encode/decode round-trip for edge values")
  void encodeDecodeEdgeCases(long value) {
    assertThat(encoder.decode(encoder.encode(value))).isEqualTo(value);
  }

  @Test
  @DisplayName("isValidBase62 accepts all valid characters")
  void isValidBase62_validInput() {
    assertThat(encoder.isValidBase62("abc123ABC")).isTrue();
    assertThat(encoder.isValidBase62("0000000")).isTrue();
    assertThat(encoder.isValidBase62("ZZZZZZZ")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc-def", "hello world", "abc_123", "abc!"})
  @DisplayName("isValidBase62 rejects invalid characters")
  void isValidBase62_invalidInput(String input) {
    assertThat(encoder.isValidBase62(input)).isFalse();
  }

  @Test
  @DisplayName("isValidBase62 rejects null")
  void isValidBase62_null() {
    assertThat(encoder.isValidBase62(null)).isFalse();
  }
}
