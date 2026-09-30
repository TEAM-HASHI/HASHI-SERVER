package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisMapSessionStoreIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private static RedisMapSessionStore store;
    private static final MapSessionSerializer SERIALIZER = new MapSessionSerializer();
    private static final String PREFIX = "hashi:restaurant:map:{sessions-v1}:slot:";

    @BeforeAll
    static void connect() {
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(2)).build()).build()).build();
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)), client);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        var factory = new StaticListableBeanFactory();
        factory.addBean("redis", redis);
        store = new RedisMapSessionStore(factory.getBeanProvider(StringRedisTemplate.class), SERIALIZER, Clock.systemUTC());
    }

    @AfterAll
    static void disconnect() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @BeforeEach
    void clearOnlyTestRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void 최종_serializer의_최대후보_bytes와_실제_Redis메모리를_측정하고_Long을_보존한다() throws Exception {
        var candidates = IntStream.range(0, 500).mapToObj(index -> new RestaurantMapCandidate(
                Long.MAX_VALUE - index, new BigDecimal("5.0"), Long.MAX_VALUE)).toList();
        var criteria = MapSearchCriteria.of(MapQueryBounds.parse("0." + "1".repeat(126), "0." + "9".repeat(126),
                "0." + "1".repeat(126), "0." + "9".repeat(126)), Long.MAX_VALUE, "rice-bowl", "restaurant", "😀".repeat(100));
        var session = new MapQuerySession(1, UUID.randomUUID(), criteria, candidates,
                Instant.now(), Instant.now().plusSeconds(900));
        String json = SERIALIZER.serialize(session);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        assertThat(bytes).isLessThanOrEqualTo(MapQuerySession.MAX_BYTES);
        MapSessionId id = store.save(session);
        assertThat(store.find(id)).isEqualTo(session);
        assertThat(json).doesNotContain("@class", "latitude", "longitude", "image", "isSaved");
        String memory = REDIS.execInContainer("redis-cli", "MEMORY", "USAGE", PREFIX + id.slot()).getStdout().strip();
        System.out.println("MAP_SESSION_MEASUREMENT candidates=500 utf8Bytes=" + bytes + " redisMemoryBytes=" + memory);
        assertThat(Long.parseLong(memory)).isLessThan(100_000);
    }

    @Test
    void 동시_생성도_128개를_넘지_않고_별도관리정보_유실이_한도를_늘리지_않는다() throws Exception {
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<Boolean>> calls = IntStream.range(0, 160).mapToObj(index -> (Callable<Boolean>) () -> {
                try {
                    store.save(session(Duration.ofMinutes(15)));
                    return true;
                } catch (BusinessException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
                    return false;
                }
            }).toList();
            var results = executor.invokeAll(calls);
            int accepted = 0;
            for (var result : results) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(128);
        }
        assertThat(redis.keys(PREFIX + "*")).hasSize(128);
        redis.delete("hashi:restaurant:map:admission");
        assertCode(() -> store.save(session(Duration.ofMinutes(15))), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        redis.delete(List.of(PREFIX + "0", PREFIX + "1"));
        store.save(session(Duration.ofMinutes(15)));
        store.save(session(Duration.ofMinutes(15)));
        assertCode(() -> store.save(session(Duration.ofMinutes(15))), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
    }

    @Test
    void 실제TTL_만료와_슬롯재사용에도_옛세션을_이어붙이지_않는다() throws Exception {
        var session = session(Duration.ofMillis(1600));
        var id = store.save(session);
        long before = redis.getExpire(PREFIX + id.slot(), java.util.concurrent.TimeUnit.MILLISECONDS);
        Thread.sleep(200);
        assertThat(store.find(id)).isEqualTo(session);
        long after = redis.getExpire(PREFIX + id.slot(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(after).isLessThan(before);
        Thread.sleep(1500);
        assertThat(redis.hasKey(PREFIX + id.slot())).isFalse();
        assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED);
        var replacement = store.save(session(Duration.ofMinutes(15)));
        assertThat(replacement.slot()).isEqualTo(id.slot());
        assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED);
    }

    @Test
    void 가득찬_슬롯에서_만료한_한자리만_정확히_회수한다() throws Exception {
        for (int index = 0; index < 127; index++) {
            store.save(session(Duration.ofMinutes(15)));
        }
        var expiring = store.save(session(Duration.ofMillis(1400)));
        assertCode(() -> store.save(session(Duration.ofMinutes(15))), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        Thread.sleep(1500);
        var replacement = store.save(session(Duration.ofMinutes(15)));
        assertThat(replacement.slot()).isEqualTo(expiring.slot());
        assertThat(redis.keys(PREFIX + "*")).hasSize(128);
        assertCode(() -> store.save(session(Duration.ofMinutes(15))), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
    }

    @Test
    void bytes초과와_구버전_손상_유실을_분리하고_인증키를_변경하지_않는다() {
        redis.opsForValue().set("auth:synthetic", "unchanged", Duration.ofSeconds(90));
        var id = store.save(session(Duration.ofMinutes(15)));
        redis.opsForValue().set(PREFIX + id.slot(), "{\"schemaVersion\":999}");
        assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED);
        redis.delete(PREFIX + id.slot());
        assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED);
        var criteria = MapSearchCriteria.of(new MapQueryBounds(new BigDecimal("0." + "1".repeat(70_000)),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE), null, null, null, null);
        var oversized = new MapQuerySession(1, UUID.randomUUID(), criteria, List.of(),
                Instant.now(), Instant.now().plusSeconds(900));
        assertCode(() -> store.save(oversized), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        assertThat(redis.keys(PREFIX + "*")).isEmpty();
        assertThat(redis.opsForValue().get("auth:synthetic")).isEqualTo("unchanged");
        assertThat(redis.getExpire("auth:synthetic")).isBetween(1L, 90L);
    }

    @Test
    void 실제쓰기거절은_503이고_부분키를_남기지_않는다() throws Exception {
        try {
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "1");
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory-policy", "noeviction");
            assertCode(() -> store.save(session(Duration.ofMinutes(15))), RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        } finally {
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0");
        }
        assertThat(redis.keys(PREFIX + "*")).isEmpty();
    }

    @Test
    void 무응답_Redis는_기존_명령timeout_안에_503으로_끝난다() {
        var id = store.save(session(Duration.ofMinutes(15)));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        long start = System.nanoTime();
        try {
            assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private MapQuerySession session(Duration ttl) {
        return new MapQuerySession(1, UUID.randomUUID(), MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null), List.of(),
                Instant.now(), Instant.now().plus(ttl));
    }

    private void assertCode(Runnable action, RestaurantErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
