package org.sopt.hashi.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.event.connection.ConnectedEvent;
import io.lettuce.core.event.connection.DisconnectedEvent;
import io.lettuce.core.event.connection.ReconnectFailedEvent;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
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
@Slf4j
@EnableCaching
@Configuration
public class RedisConfig {

    // TCP 계층에서 죽은 연결을 감지하는 한도 — 공식 권장 규칙 TCP_USER_TIMEOUT = IDLE + INTERVAL × COUNT 준수
        private static final Duration TCP_USER_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration KEEPALIVE_IDLE = Duration.ofSeconds(5);
    private static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(5);
    private static final int KEEPALIVE_COUNT = 3;

    /**
     * half-open 연결 방어 — 종료 통보(FIN/RST) 없이 죽은 연결을 OS(TCP 계층)가 감지·폐기해
     * Lettuce 재연결이 발동하도록 keepalive(유휴 중)와 TCP_USER_TIMEOUT(트래픽 중 무응답)을 켠다.
     * TCP_USER_TIMEOUT은 epoll 네이티브 전송(리눅스)에서만 적용되고 그 외 환경은 무시된다.
     * ClientOptions 빌더 커스터마이저라 자동 구성의 나머지 옵션(timeoutOptions 등)은 유지되지만,
     * socketOptions는 통째로 교체되므로 yml의 connect-timeout만 여기서 다시 실어준다.
     */
    @Bean
    public LettuceClientOptionsBuilderCustomizer lettuceSocketOptionsCustomizer(
            RedisProperties redisProperties) {
        SocketOptions.Builder socketOptions = SocketOptions.builder()
                .tcpUserTimeout(SocketOptions.TcpUserTimeoutOptions.builder()
                        .tcpUserTimeout(TCP_USER_TIMEOUT)
                        .enable()
                        .build())
                .keepAlive(SocketOptions.KeepAliveOptions.builder()
                        .idle(KEEPALIVE_IDLE)
                        .interval(KEEPALIVE_INTERVAL)
                        .count(KEEPALIVE_COUNT)
                        .enable()
                        .build());
        if (redisProperties.getConnectTimeout() != null) {
            socketOptions.connectTimeout(redisProperties.getConnectTimeout());
        }
        return builder -> builder.socketOptions(socketOptions.build());
    }

    /**
     * Lettuce 연결 생애 이벤트 로깅 — 연결 단절·재연결 실패 시각이 로그(Loki)에 남아,
     * half-open 장애 재발 시 앱 관점의 타임라인을 소급 확인할 수 있다.
     */
    @Bean
    public InitializingBean lettuceConnectionEventLogger(ClientResources clientResources) {
        return () -> clientResources.eventBus().get().subscribe(event -> {
            if (event instanceof ConnectedEvent connected) {
                log.info("Lettuce 연결 수립. remote={}", connected.remoteAddress());
            } else if (event instanceof DisconnectedEvent disconnected) {
                log.warn("Lettuce 연결 끊김. remote={}", disconnected.remoteAddress());
            } else if (event instanceof ReconnectFailedEvent failed) {
                log.warn("Lettuce 재연결 실패. remote={}, attempt={}",
                        failed.remoteAddress(), failed.getAttempt());
            }
        });
    }

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
