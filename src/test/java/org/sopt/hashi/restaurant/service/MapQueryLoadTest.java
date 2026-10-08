package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.MapSessionLimits;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Opt-in, loopback-only experiment. This is not a production capacity estimate. */
@Tag("map-load")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "jwt.secret=test-secret-key-must-be-at-least-32-bytes-long",
        "kakao.client-id=test-client-id", "kakao.redirect-uri=https://app.hashi.test/callback",
        "hashi.storage.cloudfront-domain=https://cdn.hashi.test",
        "hashi.map.location-job.enabled=false", "hashi.map.google-geocoding.enabled=false",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1",
        "hashi.restaurant.map.initial-bounds.south=10", "hashi.restaurant.map.initial-bounds.north=11",
        "hashi.restaurant.map.initial-bounds.west=20", "hashi.restaurant.map.initial-bounds.east=21",
        "hashi.restaurant.map.supported-bounds.south=10", "hashi.restaurant.map.supported-bounds.north=11",
        "hashi.restaurant.map.supported-bounds.west=20", "hashi.restaurant.map.supported-bounds.east=21",
        "hashi.restaurant.map.session.enabled=true",
        "hashi.restaurant.map.session.signing-key=c3ludGhldGljLW1hcC10ZXN0LWtleS0zMi1ieXRlcy1vbmx5",
        "hashi.restaurant.map.session.limits.idle-timeout=10s",
        "hashi.restaurant.map.session.limits.max-lifetime=30s",
        "hashi.restaurant.map.session.limits.requests-per-caller=600",
        "hashi.restaurant.map.session.limits.new-queries-per-caller=500",
        "hashi.restaurant.map.session.limits.requests-per-minute=3000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MapQueryLoadTest {
    private static final String PROFILE = System.getProperty("map.k6.profile", "smoke");
    private static final String TRAFFIC = System.getProperty("map.k6.traffic", "shared");
    private static final int RESTAURANTS = Integer.parseInt(System.getProperty("map.k6.restaurants", "620"));
    private static final int BUDGET_MIB = Integer.parseInt(System.getProperty("map.k6.budget-mib", "16"));
    private static final int CONCURRENT_REQUESTS = Integer.parseInt(System.getProperty(
            "hashi.restaurant.map.session.limits.concurrent-requests", "4"));
    private static final int IDLE_SECONDS = "ttl".equals(PROFILE) ? 300 : 10;
    private static final int WORKLOAD_SECONDS = "smoke".equals(PROFILE) ? 90 : 300;
    private static final String QUERIES = "hashi:restaurant:map:{sessions-v2}:query:*";
    private static final String SENTINEL = "map-load:auth-sentinel";
    private static final String REJECTION_METRIC = "hashi.restaurant.map.capacity.rejected";
    private static final List<String> REJECTION_REASONS = List.of("concurrent_requests", "candidate_count",
            "snapshot_bytes", "total_bytes", "session_count", "caller_rate", "caller_cardinality",
            "global_requests", "redis_memory_guard");

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("map_load").withUsername("hashi").withPassword("hashi");
    @Container @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--maxmemory", "128mb", "--maxmemory-policy", "noeviction");

    @LocalServerPort int port;
    @Autowired RestaurantRepository restaurants;
    @Autowired TransactionTemplate transactions;
    @Autowired StringRedisTemplate redis;
    @Autowired MapSessionLimits limits;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired MeterRegistry meterRegistry;
    @MockitoBean MediaPort mediaPort;
    @MockitoBean FileStorage fileStorage;

    @Test
    void 실제_HTTP_혼합부하_후_유휴세션이_정리되고_제한해제후_복구된다() throws Exception {
        assertThat(PROFILE).isIn("smoke", "normal", "staged", "ttl");
        assertThat(TRAFFIC).isIn("shared", "distinct");
        assertThat(RESTAURANTS).isBetween(620, 5000);
        assertThat(BUDGET_MIB).isIn(16, 32);
        assertThat(CONCURRENT_REQUESTS).isIn(4, 8);
        assertThat(limits.getConcurrentRequests()).isEqualTo(CONCURRENT_REQUESTS);
        limits.setTotalBytes(BUDGET_MIB * 1_048_576L);
        if ("normal".equals(PROFILE) || "ttl".equals(PROFILE)) {
            assertThat(TRAFFIC).as("paced profiles model distinct callers").isEqualTo("distinct");
            limits.setRequestsPerCaller(120);
            limits.setNewQueriesPerCaller(12);
            limits.setRequestsPerMinute(600);
        }
        limits.setIdleTimeout(Duration.ofSeconds(IDLE_SECONDS));
        limits.setMaxLifetime(Duration.ofSeconds("ttl".equals(PROFILE) ? 1800 : 30));
        Path output = Path.of(System.getProperty("map.k6.output"));
        Files.createDirectories(output);
        for (String file : List.of("k6-summary.json", "k6.log", "resource-samples.json", "recovery.json")) {
            Files.deleteIfExists(output.resolve(file));
        }
        seedRestaurants();
        redis.opsForValue().set(SENTINEL, "synthetic-auth-value", Duration.ofDays(1));
        String base = "http://127.0.0.1:" + port;
        List<Map<String, Object>> samples = new ArrayList<>();
        int k6Exit;
        samples.add(sample());
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            ProcessBuilder builder = new ProcessBuilder(System.getProperty("map.k6.executable"), "run", "--no-usage-report",
                    "--summary-export", output.resolve("k6-summary.json").toString(),
                    System.getProperty("map.k6.script"));
            builder.environment().remove("HASHI_MAP_GOOGLEGEOCODING_APIKEY");
            builder.environment().put("BASE_URL", base);
            builder.environment().put("MAP_LOAD_PROFILE", PROFILE);
            builder.environment().put("MAP_LOAD_TRAFFIC", TRAFFIC);
            builder.redirectErrorStream(true).redirectOutput(output.resolve("k6.log").toFile());
            Process process = builder.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(WORKLOAD_SECONDS + 60).toNanos();
            try {
                while (!process.waitFor(1, TimeUnit.SECONDS)) {
                    samples.add(sample());
                    assertThat(System.nanoTime()).as("k6 must finish within workload plus sixty seconds").isLessThan(deadline);
                }
                k6Exit = process.exitValue(); // Keep all failures; still measure cleanup/recovery before asserting.
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
                json.writeValue(output.resolve("resource-samples.json").toFile(), samples);
            }
            Map<String, Double> workloadRejections = capacityRejections();
            Map<String, Object> workloadStages = stageDurations();
            assertThat(samples).isNotEmpty();
            assertThat(samples.stream().mapToInt(sample -> ((Number) sample.get("sessions")).intValue())
                    .max().orElseThrow()).isPositive();
            awaitSessionCleanup(samples);
            assertThat(redis.opsForValue().get(SENTINEL)).isEqualTo("synthetic-auth-value");
            // Prove the limit rejects safely and releases naturally without flushing shared Redis keys.
            // The retention profile was measured unchanged above; shorten only this separate rejection probe.
            limits.setIdleTimeout(Duration.ofSeconds(10));
            limits.setMaxLifetime(Duration.ofSeconds(30));
            limits.setSessions(2);
            if ("shared".equals(TRAFFIC) && !"smoke".equals(PROFILE)) {
                // A saturated caller window lasts up to one minute. Do not erase admission Redis data.
                for (int second = 0; second < 65; second++) {
                    samples.add(sample());
                    Thread.sleep(1000);
                }
            }
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            HttpResponse<String> rejected = request(client, base);
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(json.readTree(rejected.body()).path("code").asText()).isEqualTo("RESTAURANT-016");
            awaitSessionCleanup(samples);
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            assertThat(redis.opsForValue().get(SENTINEL)).isEqualTo("synthetic-auth-value");
            json.writeValue(output.resolve("recovery.json").toFile(), Map.ofEntries(
                    Map.entry("syntheticRestaurants", RESTAURANTS), Map.entry("workloadSeconds", WORKLOAD_SECONDS),
                    Map.entry("idleTimeoutSeconds", IDLE_SECONDS), Map.entry("profile", PROFILE),
                    Map.entry("traffic", TRAFFIC), Map.entry("sessionBudgetMiB", BUDGET_MIB),
                    Map.entry("k6Exit", k6Exit), Map.entry("capacityRejected", true),
                    Map.entry("recoveredAfterExpiry", true), Map.entry("authSentinelPreserved", true),
                    Map.entry("workloadCapacityRejections", workloadRejections),
                    Map.entry("workloadStageDurations", workloadStages),
                    Map.entry("finalCapacityRejections", capacityRejections())));
            json.writeValue(output.resolve("resource-samples.json").toFile(), samples);
            assertThat(k6Exit).as("Original k6 thresholds remain enforced; see rejection counters and k6.log").isZero();
        }
    }

    private HttpResponse<String> request(HttpClient client, String base) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base
                        + "/api/v1/restaurants/map?south=10&north=11&west=20&east=21"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> sample() {
        try (var connection = redis.getConnectionFactory().getConnection()) {
            var memory = connection.serverCommands().info("memory");
            var hikari = (HikariDataSource) dataSource;
            var pool = hikari.getHikariPoolMXBean();
            var ledger = redis.opsForHash().values("hashi:restaurant:map:{sessions-v2}:admission");
            var requestLedger = "hashi:restaurant:map:{sessions-v2}:requests";
            Object requestTotal = redis.opsForHash().get(requestLedger, "total");
            long requestFields = redis.opsForHash().size(requestLedger);
            long recordedBytes = ledger.stream().map(Object::toString)
                    .mapToLong(value -> Long.parseLong(value.substring(value.indexOf(':') + 1))).sum();
            return Map.ofEntries(
                    Map.entry("elapsedAt", System.currentTimeMillis()),
                    Map.entry("sessions", redis.keys(QUERIES).size()),
                    Map.entry("redisUsedBytes", Long.parseLong(memory.getProperty("used_memory"))),
                    Map.entry("dbActive", pool.getActiveConnections()),
                    Map.entry("dbWaiting", pool.getThreadsAwaitingConnection()),
                    Map.entry("dbMaximumPoolSize", hikari.getMaximumPoolSize()),
                    Map.entry("ledgerRecordedBytes", recordedBytes),
                    Map.entry("ledgerEntries", ledger.size()),
                    Map.entry("requestWindowTotal", requestTotal == null ? 0 : Long.parseLong(requestTotal.toString())),
                    Map.entry("requestCallerEntries", Math.max(0, requestFields - 2)),
                    Map.entry("capacityRejections", capacityRejections()),
                    Map.entry("concurrentRequestLimit", limits.getConcurrentRequests()),
                    Map.entry("ledgerBudgetBytes", limits.getTotalBytes()),
                    Map.entry("sessionLimit", limits.getSessions()));
        }
    }

    private Map<String, Double> capacityRejections() {
        var counts = new LinkedHashMap<String, Double>();
        for (String reason : REJECTION_REASONS) {
            var counter = meterRegistry.find(REJECTION_METRIC).tag("reason", reason).counter();
            counts.put(reason, counter == null ? 0 : counter.count());
        }
        return counts;
    }

    /** Includes the initial HTTP readiness request; captured before cleanup/recovery probes. */
    private Map<String, Object> stageDurations() {
        var durations = new LinkedHashMap<String, Object>();
        for (String operation : List.of("new_query", "sort_change", "next_page")) {
            for (String stage : List.of("admit", "candidates", "save", "read_page", "touch")) {
                var timer = meterRegistry.find("hashi.restaurant.map.stage.duration")
                        .tags("operation", operation, "stage", stage).timer();
                if (timer != null && timer.count() > 0) {
                    durations.put(operation + ":" + stage, Map.of(
                            "count", timer.count(), "meanMs", timer.mean(TimeUnit.MILLISECONDS),
                            "totalMs", timer.totalTime(TimeUnit.MILLISECONDS)));
                }
            }
        }
        return durations;
    }

    private void awaitSessionCleanup(List<Map<String, Object>> samples) throws InterruptedException {
        long deadline = System.nanoTime() + limits.getIdleTimeout().plusSeconds(15).toNanos();
        while (!redis.keys(QUERIES).isEmpty() && System.nanoTime() < deadline) {
            samples.add(sample());
            Thread.sleep(1000);
        }
        assertThat(redis.keys(QUERIES)).isEmpty();
        samples.add(sample());
    }

    private void seedRestaurants() {
        Clock clock = Clock.systemUTC();
        transactions.executeWithoutResult(status -> {
            for (int index = 0; index < RESTAURANTS; index++) {
                Restaurant restaurant = Restaurant.create("합성 부하 식당 " + index, "試験", "요약", "설명",
                        "東京都試験区架空町1丁目2番3号", "합성 지역", RestaurantGenre.SUSHI, "초밥",
                        RestaurantPlaceType.RESTAURANT, PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN);
                restaurant.requestLocationResolution();
                restaurant.completeLocation(1, restaurant.getLocation().getRequestId(),
                        MapCoordinates.of(new BigDecimal("10.5"), new BigDecimal("20.5")),
                        RestaurantLocationSource.ADMIN, LocalDateTime.now(clock).minusHours(1),
                        LocalDateTime.now(clock).plusHours(1), clock);
                restaurants.save(restaurant);
            }
        });
    }
}
