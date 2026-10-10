package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PortBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;
import org.sopt.hashi.auth.internal.token.RefreshTokenStore;
import org.sopt.hashi.config.RedisConfig;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 실제 Boot 인증 배선 + 서로 다른 메모리 정책의 Redis 두 개를 사용한다. 운영 서버에는 연결하지 않는다. */
@Testcontainers
class MapRedisIsolationIntegrationTest {
    private static final long CONTAINER_BYTES = 128L * 1024 * 1024;
    private static final String PREFIX = "hashi:restaurant:map:{sessions-v2}:";
    private static final String AUTH_KEY = "auth:refresh:ROLE_USER:1";
    @Container
    static final GenericContainer<?> AUTH = redis("volatile-lru");
    @Container
    static final GenericContainer<?> MAP = redis("noeviction");

    private static GenericContainer<?> redis(String policy) {
        // Docker Desktop은 hostPort=0 매핑을 컨테이너 재시작 때 다시 할당할 수 있다.
        // 테스트가 같은 Redis endpoint의 재시작을 검증하도록 빈 루프백 포트를 한 번 정한다.
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new GenericContainer<>(DockerImageName.parse(
                "redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", policy,
                        "--appendonly", "no", "--save", "")
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withMemory(CONTAINER_BYTES).withMemorySwap(CONTAINER_BYTES).withNanoCPUs(500_000_000L)
                        .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", port),
                                ExposedPort.tcp(6379))));
    }

    @BeforeEach
    void resetOnlyDisposableRedis() throws Exception {
        for (var redis : List.of(AUTH, MAP)) {
            assertThat(redis.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "64mb").getExitCode()).isZero();
            assertThat(redis.execInContainer("redis-cli", "FLUSHDB").getExitCode()).isZero();
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(ConfigurationUnderTest.class)
                .withPropertyValues("spring.data.redis.host=" + AUTH.getHost(),
                        "spring.data.redis.port=" + AUTH.getMappedPort(6379),
                        "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=1s",
                        "hashi.restaurant.map.redis.host=" + MAP.getHost(),
                        "hashi.restaurant.map.redis.port=" + MAP.getMappedPort(6379),
                        "hashi.restaurant.map.redis.timeout=1s", "hashi.restaurant.map.redis.connect-timeout=1s",
                        "hashi.restaurant.map.session.enabled=true",
                        "hashi.restaurant.map.session.signing-key=" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void 자동구성은_인증용으로_유지하고_지도키는_별도_Redis에만_저장한다() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(RedisConnectionFactory.class).hasSingleBean(StringRedisTemplate.class);
            var auth = context.getBean(StringRedisTemplate.class);
            var map = context.getBean(MapRedisConnection.class).template();
            var store = context.getBean(RedisMapSessionStore.class);
            var tokens = context.getBean(RefreshTokenStore.class);
            tokens.save("ROLE_USER", 1L, "synthetic-original");
            var session = session(store);
            var id = store.save(session);
            assertThat(store.find(id)).isEqualTo(session);
            assertThat(auth.keys(PREFIX + "*")).isEmpty();
            assertThat(map.hasKey(AUTH_KEY)).isFalse();
            assertThat(map.hasKey(PREFIX + "query:" + id.value())).isTrue();
            tokens.rotate("ROLE_USER", 1L, "synthetic-original", "synthetic-rotated");
            assertThat(context.getBean("redisTemplate", RedisTemplate.class).opsForValue().get(AUTH_KEY))
                    .isEqualTo("synthetic-rotated");
            assertThat(auth.getConnectionFactory()).isSameAs(context.getBean(RedisConnectionFactory.class));
            assertThat(map.getConnectionFactory()).isNotSameAs(auth.getConnectionFactory());
            try (var connection = auth.getConnectionFactory().getConnection()) {
                assertThat(connection.serverCommands().info("memory").getProperty("maxmemory_policy"))
                        .isEqualTo("volatile-lru");
            }
        });
    }

    @Test
    void 지도_비활성화나_호스트누락은_인증연결로_대체하지_않는다() {
        runner().withPropertyValues("hashi.restaurant.map.session.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            var holder = context.getBean(MapRedisConnection.class);
            assertCode(holder::template, RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
            assertThat(ReflectionTestUtils.getField(holder, "connections")).isNull();
            verifyTokenRotation(context.getBean(RefreshTokenStore.class));
        });
        runner().withPropertyValues("hashi.restaurant.map.redis.host=").run(context -> {
            assertThat(context).hasNotFailed();
            assertCode(() -> context.getBean(RedisMapSessionStore.class).admit("synthetic", true),
                    RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
            assertThat(ReflectionTestUtils.getField(context.getBean(MapRedisConnection.class), "connections")).isNull();
            verifyTokenRotation(context.getBean(RefreshTokenStore.class));
        });
    }

    @Test
    void 지도무응답은_503으로_끝나고_동시에_인증토큰은_회전한다() {
        runner().run(context -> {
            var store = context.getBean(RedisMapSessionStore.class);
            var id = store.save(session(store));
            MAP.getDockerClient().pauseContainerCmd(MAP.getContainerId()).exec();
            try {
                Instant start = Instant.now();
                assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
                assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
                verifyTokenRotation(context.getBean(RefreshTokenStore.class));
            } finally {
                MAP.getDockerClient().unpauseContainerCmd(MAP.getContainerId()).exec();
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(store.find(id)).isNotNull());
        });
    }

    @Test
    void 지도재시작은_기존조회만_410으로_만료시키고_새조회와_인증은_정상이다() {
        runner().run(context -> {
            var store = context.getBean(RedisMapSessionStore.class);
            var id = store.save(session(store));
            var tokens = context.getBean(RefreshTokenStore.class);
            tokens.save("ROLE_USER", 1L, "synthetic-original");
            MAP.getDockerClient().restartContainerCmd(MAP.getContainerId()).withTimeout(1).exec();
            await().atMost(Duration.ofSeconds(15)).ignoreExceptions().untilAsserted(() ->
                    assertThat(store.admit("after-restart", false)).isNotNull());
            assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED);
            tokens.rotate("ROLE_USER", 1L, "synthetic-original", "synthetic-rotated");
            assertThat(store.find(store.save(session(store)))).isNotNull();
        });
    }

    @Test
    void 지도_메모리쓰기거절과_TTL회수는_인증토큰을_삭제하지_않는다() {
        runner().run(context -> {
            var store = context.getBean(RedisMapSessionStore.class);
            var holder = context.getBean(MapRedisConnection.class);
            context.getBean(MapSessionLimits.class).setIdleTimeout(Duration.ofSeconds(1));
            var id = store.save(session(store));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED));
            assertThat(MAP.getDockerClient().inspectContainerCmd(MAP.getContainerId()).exec()
                    .getHostConfig().getMemory()).isEqualTo(CONTAINER_BYTES);
            try (var connection = holder.template().getConnectionFactory().getConnection()) {
                connection.serverCommands().setConfig("maxmemory", "1");
                assertThatThrownBy(() -> holder.template().opsForValue().set("synthetic-overflow", "value"))
                        .isInstanceOf(DataAccessException.class);
                assertCode(() -> store.admit("memory-full", true), RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
                verifyTokenRotation(context.getBean(RefreshTokenStore.class));
                connection.serverCommands().setConfig("maxmemory", "64mb");
            }
            assertThat(store.find(store.save(session(store)))).isNotNull();
        });
    }

    private static MapQuerySession session(RedisMapSessionStore store) {
        Instant now = store.admit("synthetic-caller", true);
        return new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null), List.of(), null,
                now, now.plus(Duration.ofMinutes(5)));
    }

    private static void verifyTokenRotation(RefreshTokenStore tokens) {
        tokens.save("ROLE_USER", 1L, "synthetic-original");
        tokens.rotate("ROLE_USER", 1L, "synthetic-original", "synthetic-rotated");
        tokens.rotate("ROLE_USER", 1L, "synthetic-rotated", "synthetic-final");
    }

    private static void assertCode(Runnable action, RestaurantErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MapRedisProperties.class, MapSessionProperties.class})
    @Import({RedisConfig.class, MapRedisConnection.class, RedisMapSessionStore.class, MapSessionSerializer.class,
            MapSessionLimits.class, MapCapacityMetrics.class, RefreshTokenStore.class})
    static class ConfigurationUnderTest {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        JwtProperties jwtProperties() {
            return new JwtProperties("synthetic-not-used-to-sign-a-token", Duration.ofMinutes(30),
                    Duration.ofDays(14), Duration.ofMinutes(30));
        }
    }
}
