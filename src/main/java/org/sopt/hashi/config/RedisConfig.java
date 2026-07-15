package org.sopt.hashi.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 배선. key/hashKey는 문자열, value/hashValue는 타입 정보를 담은 JSON으로 직렬화한다.
 * Spring Cache 추상화(@Cacheable 등)를 위한 RedisCacheManager도 함께 제공한다.
 */
@EnableCaching
@Configuration
public class RedisConfig {

    // 템플릿·캐시가 공유하는 단일 값 직렬화기(내부 ObjectMapper도 1개만 생성).
    private final GenericJackson2JsonRedisSerializer valueSerializer =
            new GenericJackson2JsonRedisSerializer(buildObjectMapper());

    /** 직접 Redis 접근용 템플릿. 키/해시키는 문자열, 값/해시값은 타입 정보를 담은 JSON으로 직렬화한다. */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);
        redisTemplate.setValueSerializer(valueSerializer);
        redisTemplate.setHashValueSerializer(valueSerializer);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    /** @Cacheable 등 Spring Cache 추상화용 매니저. TTL은 전역 기본값 없이 캐시별로 지정한다. null 값은 캐싱하지 않는다. */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer))
                .disableCachingNullValues();
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .build();
    }

    /** Redis 값 직렬화용 ObjectMapper. LocalDateTime을 ISO-8601로 직렬화하고, 역직렬화 시 구체 타입을 복원한다. */
    private static ObjectMapper buildObjectMapper() {
        // 역직렬화 가젯 체인 방지 — 우리 패키지와 안전한 JDK 타입(컬렉션·시각)만 폴리모픽 역직렬화를 허용한다.
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("org.sopt.hashi.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .build();
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY);
    }
}
