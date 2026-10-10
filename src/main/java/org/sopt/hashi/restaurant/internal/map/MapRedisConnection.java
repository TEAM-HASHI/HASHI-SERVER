package org.sopt.hashi.restaurant.internal.map;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 지도 전용 연결의 생애를 소유한다. factory/template을 빈으로 노출하지 않아
 * Boot의 인증용 Redis 자동 구성과 기존 주입 지점을 바꾸지 않는다.
 */
@Slf4j
@Component
public class MapRedisConnection {
    private final MapRedisProperties properties;
    private final MapSessionProperties sessions;
    private ClientResources clientResources;
    private LettuceConnectionFactory connections;
    private StringRedisTemplate template;
    private boolean closed;

    public MapRedisConnection(MapRedisProperties properties, MapSessionProperties sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    @PostConstruct
    void reportConfiguration() {
        if (!sessions.isEnabled()) {
            return;
        }
        try {
            properties.validate();
        } catch (BusinessException exception) {
            log.warn("Map query sessions are enabled but the dedicated Redis configuration is missing or invalid.");
        }
    }

    /** 첫 유효 요청에서만 초기화하며, 동시 요청이 별도 연결을 중복 생성하지 않는다. */
    synchronized StringRedisTemplate template() {
        sessions.requireConfigured();
        properties.validate();
        if (closed) {
            throw unavailable();
        }
        if (template != null) {
            return template;
        }
        var server = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            server.setUsername(properties.getUsername());
        }
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            server.setPassword(properties.getPassword());
        }
        if (clientResources == null) {
            clientResources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        }
        var client = LettuceClientConfiguration.builder()
                .clientResources(clientResources)
                .commandTimeout(properties.getTimeout())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(properties.getConnectTimeout())
                                .keepAlive(true).build())
                        .requestQueueSize(256)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build());
        if (properties.isSsl()) {
            client.useSsl(); // 기본 인증서/호스트 검증 유지.
        }
        var created = new LettuceConnectionFactory(server, client.build());
        try {
            created.afterPropertiesSet();
            template = new StringRedisTemplate(created);
            connections = created;
            return template;
        } catch (RuntimeException exception) {
            created.destroy();
            // 접속정보를 포함할 수 있는 드라이버 예외는 외부로 전달하지 않는다.
            throw unavailable();
        }
    }

    @PreDestroy
    synchronized void close() {
        closed = true;
        template = null;
        if (connections != null) {
            connections.destroy();
            connections = null;
        }
        if (clientResources != null) {
            clientResources.shutdown();
            clientResources = null;
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
    }
}
