package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
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
    private static final String QUERIES = "hashi:restaurant:map:{sessions-v2}:query:*";
    private static final String SENTINEL = "map-load:auth-sentinel";

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
    @MockitoBean MediaPort mediaPort;
    @MockitoBean FileStorage fileStorage;

    @Test
    void 실제_HTTP_혼합부하_후_유휴세션이_정리되고_제한해제후_복구된다() throws Exception {
        Path output = Path.of(System.getProperty("map.k6.output"));
        Files.createDirectories(output);
        seedRestaurants();
        redis.opsForValue().set(SENTINEL, "synthetic-auth-value", Duration.ofDays(1));
        String base = "http://127.0.0.1:" + port;
        List<Map<String, Object>> samples = new ArrayList<>();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            ProcessBuilder builder = new ProcessBuilder(System.getProperty("map.k6.executable"), "run",
                    "--summary-export", output.resolve("k6-summary.json").toString(),
                    System.getProperty("map.k6.script"));
            builder.environment().put("BASE_URL", base);
            builder.redirectErrorStream(true).redirectOutput(output.resolve("k6.log").toFile());
            Process process = builder.start();
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            try {
                while (!process.waitFor(1, TimeUnit.SECONDS)) {
                    samples.add(sample());
                    assertThat(System.nanoTime()).as("k6 must finish within three minutes").isLessThan(deadline);
                }
                assertThat(process.exitValue()).as("k6 thresholds; see build/reports/map-load/k6.log").isZero();
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
                json.writeValue(output.resolve("resource-samples.json").toFile(), samples);
            }
            assertThat(samples).isNotEmpty();
            assertThat(samples.stream().mapToInt(sample -> ((Number) sample.get("sessions")).intValue())
                    .max().orElseThrow()).isPositive();
            awaitSessionCleanup();
            assertThat(redis.opsForValue().get(SENTINEL)).isEqualTo("synthetic-auth-value");
            // Prove the limit rejects safely and releases naturally without flushing shared Redis keys.
            limits.setSessions(2);
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            HttpResponse<String> rejected = request(client, base);
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(json.readTree(rejected.body()).path("code").asText()).isEqualTo("RESTAURANT-016");
            awaitSessionCleanup();
            assertThat(request(client, base).statusCode()).isEqualTo(200);
            assertThat(redis.opsForValue().get(SENTINEL)).isEqualTo("synthetic-auth-value");
            json.writeValue(output.resolve("recovery.json").toFile(), Map.of(
                    "syntheticRestaurants", 620, "workloadSeconds", 90, "idleTimeoutSeconds", 10,
                    "capacityRejected", true, "recoveredAfterExpiry", true, "authSentinelPreserved", true));
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
            var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
            return Map.of("elapsedAt", System.currentTimeMillis(), "sessions", redis.keys(QUERIES).size(),
                    "redisUsedBytes", Long.parseLong(memory.getProperty("used_memory")),
                    "dbActive", pool.getActiveConnections(), "dbWaiting", pool.getThreadsAwaitingConnection());
        }
    }

    private void awaitSessionCleanup() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!redis.keys(QUERIES).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        assertThat(redis.keys(QUERIES)).isEmpty();
    }

    private void seedRestaurants() {
        Clock clock = Clock.systemUTC();
        transactions.executeWithoutResult(status -> {
            for (int index = 0; index < 620; index++) {
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
