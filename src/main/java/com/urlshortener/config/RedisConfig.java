package com.urlshortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.urlshortener.infrastructure.cache.CachedUrl;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration.
 *
 * <p>Two RedisTemplates: - cachedUrlRedisTemplate: typed for CachedUrl (reliable serialization
 * without type metadata) - redisTemplate: generic Object template for other services
 * (TokenBlacklistService, etc.)
 *
 * <p>Two cache managers are not needed. One RedisCacheManager with per-cache TTL overrides covers
 * all use cases.
 */
@Configuration
@EnableCaching
public class RedisConfig {

  @Value("${app.cache.url-ttl-seconds:3600}")
  private long urlTtlSeconds;

  @Value("${app.cache.api-key-ttl-seconds:300}")
  private long apiKeyTtlSeconds;

  @Value("${app.cache.user-ttl-seconds:600}")
  private long userTtlSeconds;

  @Bean
  public RedisTemplate<String, CachedUrl> cachedUrlRedisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, CachedUrl> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);

    StringRedisSerializer keySerializer = new StringRedisSerializer();
    Jackson2JsonRedisSerializer<CachedUrl> valueSerializer =
        new Jackson2JsonRedisSerializer<>(CachedUrl.class);
    valueSerializer.setObjectMapper(redisObjectMapper());

    template.setKeySerializer(keySerializer);
    template.setValueSerializer(valueSerializer);
    template.setHashKeySerializer(keySerializer);
    template.setHashValueSerializer(valueSerializer);
    template.setEnableTransactionSupport(false);
    template.afterPropertiesSet();
    return template;
  }

  @Bean
  @Primary
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);

    StringRedisSerializer keySerializer = new StringRedisSerializer();
    GenericJackson2JsonRedisSerializer valueSerializer =
        new GenericJackson2JsonRedisSerializer(redisObjectMapper());

    template.setKeySerializer(keySerializer);
    template.setValueSerializer(valueSerializer);
    template.setHashKeySerializer(keySerializer);
    template.setHashValueSerializer(valueSerializer);
    template.setEnableTransactionSupport(false);
    template.afterPropertiesSet();
    return template;
  }

  @Bean
  @Primary
  public CacheManager cacheManager(RedisConnectionFactory factory) {
    RedisCacheConfiguration defaultConfig =
        defaultCacheConfig().entryTtl(Duration.ofSeconds(urlTtlSeconds));

    Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
    cacheConfigs.put("url-cache", defaultCacheConfig().entryTtl(Duration.ofSeconds(urlTtlSeconds)));
    cacheConfigs.put(
        "api-key-cache", defaultCacheConfig().entryTtl(Duration.ofSeconds(apiKeyTtlSeconds)));
    cacheConfigs.put(
        "user-cache", defaultCacheConfig().entryTtl(Duration.ofSeconds(userTtlSeconds)));
    cacheConfigs.put("rate-limit-cache", defaultCacheConfig().entryTtl(Duration.ofSeconds(60)));

    return RedisCacheManager.builder(factory)
        .cacheDefaults(defaultConfig)
        .withInitialCacheConfigurations(cacheConfigs)
        .build();
  }

  private RedisCacheConfiguration defaultCacheConfig() {
    return RedisCacheConfiguration.defaultCacheConfig()
        .serializeKeysWith(
            RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .disableCachingNullValues()
        .prefixCacheNameWith("url-shortener:");
  }

  private ObjectMapper redisObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }
}
