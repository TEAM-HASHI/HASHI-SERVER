package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Isolated("Temporarily changes the JVM default timezone and restores it after each case")
@Execution(ExecutionMode.SAME_THREAD)
class RestaurantLocationTimezoneTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
    private static final DateTimeFormatter DATABASE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi_location_timezone").withUsername("hashi").withPassword("hashi");

    @BeforeAll
    static void 스키마를_준비한다() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @ParameterizedTest(name = "JVM={0}, JDBC={1}")
    @CsvSource({"UTC,UTC", "UTC,Asia/Seoul", "Asia/Seoul,UTC", "Asia/Seoul,Asia/Seoul"})
    void 위치의_세_시각은_JVM과_JDBC_시간대가_달라도_UTC_그대로_저장하고_읽는다(
            String jvmTimezone, String jdbcTimezone) {
        TimeZone originalTimezone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(jvmTimezone));
            assertThat(TimeZone.getDefault().getID()).isEqualTo(jvmTimezone);
            String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                    + "serverTimezone=" + jdbcTimezone;
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    url, MYSQL.getUsername(), MYSQL.getPassword()));
            var registry = new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.url", url)
                    .applySetting("hibernate.connection.username", MYSQL.getUsername())
                    .applySetting("hibernate.connection.password", MYSQL.getPassword())
                    .applySetting("hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .applySetting("hibernate.hbm2ddl.auto", "validate")
                    .build();
            try (SessionFactory factory = new MetadataSources(registry)
                    .addAnnotatedClass(RestaurantLocation.class).buildMetadata().buildSessionFactory()) {
                verifyPersistence(factory, jdbc);
            } finally {
                StandardServiceRegistryBuilder.destroy(registry);
            }
        } finally {
            TimeZone.setDefault(originalTimezone);
        }
    }

    private void verifyPersistence(SessionFactory factory, JdbcTemplate jdbc) {
        LocalDateTime obtained = NOW.minusHours(1);
        LocalDateTime validUntil = NOW.plusHours(1);
        LocalDateTime nextAttempt = NOW.plusMinutes(5);
        RestaurantLocation ready = RestaurantLocation.pending();
        assertThat(ready.complete(1, ready.getRequestId(), point(), RestaurantLocationSource.OPERATOR,
                obtained, validUntil, CLOCK)).isTrue();
        RestaurantLocation retry = RestaurantLocation.pending();
        assertThat(retry.defer(1, retry.getRequestId(), nextAttempt, CLOCK)).isTrue();

        try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            session.persist(ready);
            session.persist(retry);
            transaction.commit();
        }
        assertRawValues(jdbc, ready.getId(), retry.getId(), obtained, validUntil, nextAttempt);

        try (var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            RestaurantLocation loadedReady = session.find(RestaurantLocation.class, ready.getId());
            RestaurantLocation loadedRetry = session.find(RestaurantLocation.class, retry.getId());
            assertTimesAndBoundaries(loadedReady, loadedRetry, obtained, validUntil, nextAttempt);
            loadedReady.beginRefresh();
            assertThat(loadedReady.complete(1, loadedReady.getRequestId(), point(), RestaurantLocationSource.OPERATOR,
                    obtained.plusMinutes(1), validUntil.plusMinutes(1), CLOCK)).isTrue();
            assertThat(loadedRetry.beginScheduledRetry(clockAt(nextAttempt))).isTrue();
            assertThat(loadedRetry.defer(1, loadedRetry.getRequestId(), nextAttempt.plusMinutes(1),
                    clockAt(nextAttempt))).isTrue();
            transaction.commit();
        }
        assertRawValues(jdbc, ready.getId(), retry.getId(), obtained.plusMinutes(1),
                validUntil.plusMinutes(1), nextAttempt.plusMinutes(1));

        // Text binding makes this a raw DATETIME fixture without Timestamp/Calendar conversion.
        jdbc.update("UPDATE restaurant_location SET obtained_at=?, valid_until=? WHERE id=?",
                obtained.format(DATABASE_TIME), validUntil.format(DATABASE_TIME), ready.getId());
        jdbc.update("UPDATE restaurant_location SET next_attempt_at=? WHERE id=?",
                nextAttempt.format(DATABASE_TIME), retry.getId());
        try (var session = factory.openSession()) {
            assertTimesAndBoundaries(session.find(RestaurantLocation.class, ready.getId()),
                    session.find(RestaurantLocation.class, retry.getId()), obtained, validUntil, nextAttempt);
        }
    }

    private void assertRawValues(JdbcTemplate jdbc, Long readyId, Long retryId,
                                 LocalDateTime obtained, LocalDateTime validUntil, LocalDateTime nextAttempt) {
        var ready = jdbc.queryForMap("""
                SELECT DATE_FORMAT(obtained_at, '%Y-%m-%d %H:%i:%s.%f') AS obtained,
                    DATE_FORMAT(valid_until, '%Y-%m-%d %H:%i:%s.%f') AS until_time
                FROM restaurant_location WHERE id=?
                """, readyId);
        assertThat(ready.get("obtained")).isEqualTo(obtained.format(DATABASE_TIME));
        assertThat(ready.get("until_time")).isEqualTo(validUntil.format(DATABASE_TIME));
        assertThat(jdbc.queryForObject("""
                SELECT DATE_FORMAT(next_attempt_at, '%Y-%m-%d %H:%i:%s.%f')
                FROM restaurant_location WHERE id=?
                """, String.class, retryId)).isEqualTo(nextAttempt.format(DATABASE_TIME));
    }

    private void assertTimesAndBoundaries(RestaurantLocation ready, RestaurantLocation retry,
                                          LocalDateTime obtained, LocalDateTime validUntil, LocalDateTime nextAttempt) {
        assertThat(ready.getObtainedAt()).isEqualTo(obtained);
        assertThat(ready.getValidUntil()).isEqualTo(validUntil);
        assertThat(retry.getNextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(ready.isUsable(clockAt(validUntil.minusNanos(1_000)))).isTrue();
        assertThat(ready.isUsable(clockAt(validUntil))).isFalse();
        assertThat(retry.beginScheduledRetry(clockAt(nextAttempt.minusNanos(1_000)))).isFalse();
    }

    private Clock clockAt(LocalDateTime time) {
        return Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private MapCoordinates point() {
        return MapCoordinates.of(BigDecimal.TEN, BigDecimal.TEN);
    }
}
