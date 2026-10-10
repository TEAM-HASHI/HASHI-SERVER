package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Base64;
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
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent.ResultBounds;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageRequest;
import org.sopt.hashi.restaurant.service.RestaurantMapPageReader;
import org.sopt.hashi.restaurant.service.RestaurantMapPageService;
import org.sopt.hashi.restaurant.service.RestaurantMapService;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    private static MapSessionLimits limits;
    private static final SimpleMeterRegistry METRICS = new SimpleMeterRegistry();
    private static final MapCapacityMetrics CAPACITY_METRICS = new MapCapacityMetrics(METRICS);
    private static final MapSessionSerializer SERIALIZER = new MapSessionSerializer();
    private static final String PREFIX = "hashi:restaurant:map:{sessions-v2}:query:";

    @BeforeAll
    static void connect() {
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(2)).build()).build()).build();
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)), client);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        var connection = mock(MapRedisConnection.class);
        when(connection.template()).thenReturn(redis);
        limits = new MapSessionLimits();
        store = new RedisMapSessionStore(connection, SERIALIZER, limits,
                CAPACITY_METRICS);
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
        METRICS.clear();
        limits.setIdleTimeout(Duration.ofMinutes(5));
        limits.setMaxLifetime(Duration.ofMinutes(30));
        limits.setSessions(1024);
        limits.setSnapshotBytes(1_048_576);
        limits.setTotalBytes(16_777_216);
        limits.setRequestsPerMinute(600);
        limits.setRequestsPerCaller(120);
        limits.setNewQueriesPerCaller(12);
        limits.setCallersPerMinute(2048);
    }

    @Test
    void 앱시각의_조회메타데이터가_앞서도_Redis시각으로_세션을_생성한다() {
        var configuration = new MapSessionLimits();
        configuration.setIdleTimeout(Duration.ofSeconds(2));
        configuration.setMaxLifetime(Duration.ofSeconds(10));
        var repository = mock(RestaurantMapService.class);
        var reader = mock(RestaurantMapPageReader.class);
        var properties = new MapSessionProperties();
        properties.setEnabled(true);
        properties.setSigningKey(Base64.getEncoder().encodeToString(new byte[32]));
        var sessionStore = storeWith(configuration);
        var service = new RestaurantMapPageService(repository, reader,
                sessionStore, new MapCursorCodec(properties), configuration, CAPACITY_METRICS);
        Instant before = redisNow();
        Instant appRankingTime = before.plusSeconds(20);
        var criteria = session(Duration.ofSeconds(10)).criteria();
        when(repository.findCandidates(any(),
                anyInt())).thenReturn(
                new RestaurantMapService.CandidateSnapshot(List.of(), appRankingTime));
        when(reader.read(any(), any(),
                anyInt())).thenReturn(
                new RestaurantMapPageReader.Page(List.of(), false, 0));
        var response = service.getPage(new RestaurantMapPageRequest(criteria,
                RestaurantMapSort.RECOMMEND, null, null), "synthetic-caller");
        Instant after = redisNow();
        var saved = sessionStore.find(MapSessionId.parse(response.querySessionId()));
        assertThat(saved.rankingAsOf()).isEqualTo(appRankingTime);
        assertThat(saved.expiresAt()).isBetween(before.plusSeconds(10), after.plusSeconds(10));
        assertThat(response.expiresAt()).isBetween(before.plusSeconds(2), after.plusSeconds(2));
    }

    @Test
    void 최종_serializer의_최대후보_bytes와_실제_Redis메모리를_측정하고_Long을_보존한다() throws Exception {
        var candidates = IntStream.range(0, 500).mapToObj(index -> new RestaurantMapCandidate(
                Long.MAX_VALUE - index, new BigDecimal("5.0"), Long.MAX_VALUE)).toList();
        var criteria = MapSearchCriteria.of(MapQueryBounds.parse("0." + "1".repeat(126), "0." + "9".repeat(126),
                "0." + "1".repeat(126), "0." + "9".repeat(126)), Long.MAX_VALUE,
                "rice-bowl", "restaurant", "😀".repeat(30));
        Instant rankingAsOf = Instant.now();
        Instant expiresAt = rankingAsOf.plusSeconds(900);
        var result = new MapSearchResultExtent(candidates.size(),
                new ResultBounds(criteria.bounds().south(), criteria.bounds().north(),
                        criteria.bounds().west(), criteria.bounds().east()), expiresAt);
        var session = new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), criteria, candidates,
                result, rankingAsOf, expiresAt);
        String json = SERIALIZER.serialize(session);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        assertThat(bytes).isLessThanOrEqualTo(MapQuerySession.MAX_BYTES);
        MapSessionId id = store.save(session);
        assertThat(store.find(id)).isEqualTo(session);
        assertThat(json).doesNotContain("@class", "latitude", "longitude", "image", "isSaved");
        String memory = REDIS.execInContainer("redis-cli", "MEMORY", "USAGE", PREFIX + id.value()).getStdout().strip();
        System.out.println("MAP_SESSION_MEASUREMENT candidates=500 utf8Bytes=" + bytes + " redisMemoryBytes=" + memory);
        assertThat(Long.parseLong(memory)).isLessThan(100_000);
    }

    @Test
    void 작은조회_160개는_128슬롯에_막히지_않고_설정예산_안에서_보관된다() {
        for (int index = 0; index < 160; index++) {
            store.save(session(Duration.ofMinutes(30)));
        }
        assertThat(redis.keys(PREFIX + "*")).hasSize(160);
        assertThat(redis.opsForHash().size("hashi:restaurant:map:{sessions-v2}:admission")).isEqualTo(160);
    }

    @Test
    void 동시_생성은_설정된_예산을_지키며_만료한_예약은_다시_사용한다() throws Exception {
        limits.setSessions(2);
        limits.setIdleTimeout(Duration.ofSeconds(1));
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> calls = IntStream.range(0, 8).mapToObj(index -> (Callable<Boolean>) () -> {
                try {
                    store.save(session(Duration.ofMinutes(30)));
                    return true;
                } catch (BusinessException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
                    return false;
                }
            }).toList();
            int accepted = 0;
            for (var result : executor.invokeAll(calls)) {
                if (result.get()) accepted++;
            }
            assertThat(accepted).isEqualTo(2);
        }
        assertThat(rejections("session_count")).isEqualTo(6);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redis.keys(PREFIX + "*")).isEmpty());
        assertThat(store.find(store.save(session(Duration.ofMinutes(30))))).isNotNull();
    }

    @Test
    void 조회만으로_연장하지_않고_성공_touch는_유휴수명만_연장하며_절대수명을_넘지_않는다() throws Exception {
        limits.setIdleTimeout(Duration.ofSeconds(2));
        var session = session(Duration.ofMillis(4500));
        var id = store.save(session);
        long before = redis.getExpire(PREFIX + id.value(), java.util.concurrent.TimeUnit.MILLISECONDS);
        Thread.sleep(250);
        assertThat(store.find(id)).isEqualTo(session);
        assertThat(redis.getExpire(PREFIX + id.value(), java.util.concurrent.TimeUnit.MILLISECONDS)).isLessThan(before);
        Instant expiry = store.touch(id, session);
        assertThat(expiry).isAfter(Instant.now().plusMillis(1700)).isBeforeOrEqualTo(session.expiresAt());
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Instant>> calls = IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<Instant>) () -> store.touch(id, session)).toList();
            for (var result : executor.invokeAll(calls)) {
                assertThat(result.get()).isAfterOrEqualTo(expiry).isBeforeOrEqualTo(session.expiresAt());
            }
        }
        Thread.sleep(2000);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(redis.hasKey(PREFIX + id.value())).isFalse());
        assertCode(() -> store.touch(id, session), RestaurantErrorCode.MAP_SESSION_EXPIRED);
        assertThat(redis.hasKey(PREFIX + id.value())).isFalse();
    }

    @Test
    void 짧은설정의_새인스턴스가_기존ledger수명과_갱신한예약을_줄이지_않는다() throws Exception {
        String ledger = "hashi:restaurant:map:{sessions-v2}:admission";
        var longLimits = new MapSessionLimits();
        longLimits.setIdleTimeout(Duration.ofSeconds(4));
        longLimits.setMaxLifetime(Duration.ofSeconds(10));
        var longStore = storeWith(longLimits);
        var existing = session(Duration.ofSeconds(10));
        var existingId = longStore.save(existing);
        long before = redis.getExpire(ledger, java.util.concurrent.TimeUnit.MILLISECONDS);

        var shortLimits = new MapSessionLimits();
        shortLimits.setIdleTimeout(Duration.ofSeconds(1));
        shortLimits.setMaxLifetime(Duration.ofSeconds(2));
        var shortStore = storeWith(shortLimits);
        var shorter = shortStore.save(session(Duration.ofSeconds(2)));
        long after = redis.getExpire(ledger, java.util.concurrent.TimeUnit.MILLISECONDS);
        // Normal command elapsed time is allowed, but replacing the 11s lifetime with 3s is not.
        assertThat(after).isGreaterThan(before - 1000);

        Thread.sleep(2200);
        assertThat(redis.hasKey(PREFIX + shorter.value())).isFalse();
        longStore.touch(existingId, existing);
        Thread.sleep(1200); // Past the short instance's maxLifetime + 1s ledger deadline.
        assertThat(redis.opsForHash().hasKey(ledger, existingId.value())).isTrue();
        assertThat(longStore.find(existingId)).isEqualTo(existing);
        assertThat(longStore.touch(existingId, existing)).isAfter(Instant.now());
    }

    @Test
    void 기존ledger에_TTL이_없으면_살아있는예약을_보존한다() {
        String ledger = "hashi:restaurant:map:{sessions-v2}:admission";
        var existing = session(Duration.ofMinutes(30));
        var existingId = store.save(existing);
        redis.persist(ledger);
        store.save(session(Duration.ofMinutes(30)));
        assertThat(redis.getExpire(ledger)).isEqualTo(-1);
        assertThat(redis.opsForHash().hasKey(ledger, existingId.value())).isTrue();
        assertThat(store.touch(existingId, existing)).isAfter(Instant.now());
    }

    @Test
    void 호출자_신규조회와_고유호출자수_제한은_DB작업_앞에서_사용할_수_있다() {
        limits.setNewQueriesPerCaller(2);
        limits.setCallersPerMinute(2);
        store.admit("caller-a", true);
        store.admit("caller-a", true);
        assertCode(() -> store.admit("caller-a", true), RestaurantErrorCode.MAP_RATE_LIMITED);
        assertThat(rejections("caller_rate")).isEqualTo(1);
        store.admit("caller-a", false);
        store.admit("caller-b", true);
        assertCode(() -> store.admit("caller-c", true), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        assertThat(rejections("caller_cardinality")).isEqualTo(1);
        assertThat(redis.keys(PREFIX + "*")).isEmpty();
    }

    @Test
    void 전역호출한도_거절은_호출자한도와_다른_내부사유로_집계한다() {
        limits.setRequestsPerMinute(2);
        store.admit("caller-a", false);
        store.admit("caller-b", false);

        assertCode(() -> store.admit("caller-c", false), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        assertThat(rejections("global_requests")).isEqualTo(1);
    }

    @Test
    void 세션수가_남아도_전체_byte예산을_넘으면_인증키를_보존하고_거절한다() {
        limits.setSnapshotBytes(65_536);
        limits.setTotalBytes(65_536);
        redis.opsForValue().set("auth:synthetic", "unchanged");
        var candidates = IntStream.range(0, 500).mapToObj(index -> new RestaurantMapCandidate(
                Long.MAX_VALUE - index, new BigDecimal("5.0"), Long.MAX_VALUE)).toList();
        var base = session(Duration.ofMinutes(30));
        var first = new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), base.criteria(), candidates,
                base.searchResult(), base.rankingAsOf(), base.expiresAt());
        store.save(first);
        var second = new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), base.criteria(), candidates,
                base.searchResult(), base.rankingAsOf(), base.expiresAt());
        assertCode(() -> store.save(second), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        assertThat(rejections("total_bytes")).isEqualTo(1);
        assertThat(redis.keys(PREFIX + "*")).hasSize(1);
        assertThat(redis.opsForValue().get("auth:synthetic")).isEqualTo("unchanged");
    }

    @Test
    void 공유Redis의_eviction설정은_변경하지_않고_지도입장만_거절한다() throws Exception {
        redis.opsForValue().set("auth:synthetic", "unchanged");
        try {
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory-policy", "allkeys-lru");
            assertCode(() -> store.admit("caller", true), RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
            assertCode(() -> store.save(session(Duration.ofMinutes(30))), RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
            assertThat(rejections("redis_memory_guard")).isEqualTo(2);
            assertThat(redis.opsForValue().get("auth:synthetic")).isEqualTo("unchanged");
        } finally {
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory-policy", "noeviction");
        }
    }

    @Test
    void bytes초과와_구버전_손상_유실을_분리하고_인증키를_변경하지_않는다() {
        redis.opsForValue().set("auth:synthetic", "unchanged", Duration.ofSeconds(90));
        var id = store.save(session(Duration.ofMinutes(15)));
        redis.opsForValue().set(PREFIX + id.value(), "{\"schemaVersion\":999}");
        assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED);
        redis.delete(PREFIX + id.value());
        assertCode(() -> store.find(id), RestaurantErrorCode.MAP_SESSION_EXPIRED);
        var criteria = MapSearchCriteria.of(new MapQueryBounds(new BigDecimal("0." + "1".repeat(70_000)),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE), null, null, null, null);
        limits.setSnapshotBytes(65_536);
        var oversized = new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), criteria, List.of(),
                null, Instant.now(), Instant.now().plusSeconds(900));
        assertCode(() -> store.save(oversized), RestaurantErrorCode.MAP_CAPACITY_EXCEEDED);
        assertThat(rejections("snapshot_bytes")).isEqualTo(1);
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

    private RedisMapSessionStore storeWith(MapSessionLimits configuration) {
        var connection = mock(MapRedisConnection.class);
        when(connection.template()).thenReturn(redis);
        return new RedisMapSessionStore(connection, SERIALIZER, configuration,
                CAPACITY_METRICS);
    }

    private MapQuerySession session(Duration ttl) {
        Instant now = redisNow();
        return new MapQuerySession(MapQuerySession.SCHEMA_VERSION, UUID.randomUUID(), MapSearchCriteria.of(
                MapQueryBounds.parse("0", "1", "0", "1"), null, null, null, null), List.of(), null,
                now, now.plus(ttl));
    }

    private Instant redisNow() {
        var timeScript = new DefaultRedisScript<Long>(
                "local t=redis.call('TIME'); return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)", Long.class);
        return Instant.ofEpochMilli(redis.execute(timeScript, List.of()));
    }

    private void assertCode(Runnable action, RestaurantErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private double rejections(String reason) {
        return METRICS.get(MapCapacityMetrics.REJECTION_METRIC).tag("reason", reason).counter().count();
    }
}
