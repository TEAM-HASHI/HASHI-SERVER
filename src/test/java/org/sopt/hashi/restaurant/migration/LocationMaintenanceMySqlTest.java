package org.sopt.hashi.restaurant.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.sopt.hashi.restaurant.internal.map.RestaurantLocationWorker;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceProperties.Command;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceProperties.Mode;
import org.sopt.hashi.restaurant.service.LocationAdoptionPolicy;
import org.sopt.hashi.restaurant.service.LocationJobTransactions;
import org.sopt.hashi.restaurant.service.LocationRetryPolicy;
import org.sopt.hashi.restaurant.service.RestaurantLocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LocationMaintenanceReader.class, LocationMaintenanceStore.class, LocationMaintenanceTransactions.class,
        LocationMaintenanceInspection.class, LocationMaintenanceRunner.class, LocationRetentionService.class,
        LocationRetentionTransactions.class, RestaurantLocationService.class, LocationJobTransactions.class,
        RestaurantLocationWorker.class, LocationRetryPolicy.class, LocationAdoptionPolicy.class,
        TimeConfig.class, LocationMaintenanceMySqlTest.Fixtures.class})
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Isolated("Verifies JVM UTC against a JDBC Asia/Seoul session")
class LocationMaintenanceMySqlTest {
    private static final String ADDRESS = "東京都試験区架空町1丁目2番3号";
    private static TimeZone originalZone;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("location_maintenance").withUsername("hashi").withPassword("hashi")
            .withCommand("--log-bin-trust-function-creators=1")
            .withUrlParam("connectionTimeZone", "Asia/Seoul")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Autowired RestaurantRepository restaurants;
    @Autowired RestaurantLocationJobRepository jobs;
    @Autowired RestaurantLocationService locations;
    @Autowired LocationMaintenanceReader reader;
    @Autowired LocationMaintenanceStore store;
    @Autowired LocationMaintenanceInspection inspection;
    @Autowired LocationMaintenanceTransactions maintenance;
    @Autowired LocationMaintenanceRunner runner;
    @Autowired LocationRetentionService retention;
    @Autowired LocationRetentionTransactions purge;
    @Autowired LocationJobTransactions workerTransactions;
    @Autowired RestaurantLocationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired FakeProvider provider;
    @Autowired EntityManagerFactory factory;
    long after;

    @BeforeAll
    static void utcJvm() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterAll
    static void restoreJvm() {
        TimeZone.setDefault(originalZone);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_location_checkpoint");
        jdbc.update("DELETE FROM restaurant_location_maintenance_job");
        jdbc.update("DELETE FROM restaurant_location_maintenance_run");
        jdbc.update("DELETE FROM restaurant_location_job");
        // Keep prior parent/child fixture rows; make them ineligible for this case's retention scan.
        jdbc.update("UPDATE restaurant_location SET source='OPERATOR' WHERE status='READY'");
        jdbc.update("""
                UPDATE restaurant_geocoding_budget SET enabled=true, daily_limit=1000, max_concurrent=4,
                    reserved_calls=0, budget_day=NULL, blocked_until=NULL WHERE id=1
                """);
        after = reader.upperId();
        provider.calls.set(0);
        provider.answer.set(address -> new GeocodingResult.Candidates(List.of(candidate())));
    }

    @Test
    void 확인모드는_범위와_부분집계를_명시하고_쓰기와_provider호출과_N플러스1이_없다() {
        long missing = original();
        long valid = ready(Duration.ofDays(3), false);
        ready(Duration.ofHours(3), false);
        ready(Duration.ofSeconds(-1), false);
        long pending = original();
        enqueue(pending);
        long review = original();
        enqueue(review);
        provider.answer.set(address -> new GeocodingResult.NoResults());
        worker.process(target(review));
        long failed = original();
        enqueue(failed);
        provider.answer.set(address -> new GeocodingResult.Failure(GeocodingResult.FailureKind.ACCESS_DENIED, null));
        worker.process(target(failed));
        long retry = original();
        enqueue(retry);
        provider.answer.set(address -> new GeocodingResult.Failure(GeocodingResult.FailureKind.TRANSIENT_ERROR, null));
        worker.process(target(retry));
        ready(Duration.ofMinutes(20), true);
        var stats = factory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        int callsBefore = provider.calls.get();
        var report = inspection.inspect(options(Command.DRY_RUN, Mode.BACKFILL, false, null, reader.upperId(), 100, 800));
        assertThat(report.categories()).containsEntry("UNRESOLVED", 1L).containsEntry("VALID", 1L)
                .containsEntry("REFRESH_DUE", 1L).containsEntry("EXPIRED", 1L)
                .containsEntry("PENDING", 1L).containsEntry("REVIEW_REQUIRED", 1L)
                .containsEntry("FAILED", 1L).containsEntry("DELETED", 1L).containsEntry("RETRY_WAIT", 1L);
        assertThat(report.partial()).isFalse();
        assertThat(report.inspected()).isEqualTo(9);
        assertThat(report.googlePurgeDueInInspectedRows()).isEqualTo(2);
        assertThat(report.deletedGoogleInInspectedRows()).isEqualTo(1);
        assertThat(report.registrationEstimateInInspectedRows()).isEqualTo(1);
        assertThat(report.reservedCallCeilingForEstimate()).isEqualTo(8);
        assertThat(provider.calls.get()).isEqualTo(callsBefore);
        assertThat(stats.getEntityInsertCount() + stats.getEntityUpdateCount() + stats.getEntityDeleteCount()).isZero();
        assertThat(stats.getEntityLoadCount()).isZero();
        assertThat(count("restaurant_location_maintenance_run")).isZero();
        assertThat(report.toString()).doesNotContain(ADDRESS, "latitude", "longitude");
        var limited = new LocationMaintenanceProperties(Command.DRY_RUN, Mode.BACKFILL, false, null, after,
                reader.upperId(), 2, 1, 10, 80, null, null, false, null);
        var partial = inspection.inspect(limited);
        assertThat(partial.partial()).isTrue();
        assertThat(partial.inspected()).isEqualTo(2);
        assertThat(partial.lastInspectedId()).isEqualTo(valid).isGreaterThan(missing);
    }

    @Test
    void 고정범위와_checkpoint는_같은run의_재시작과_새로생긴식당에도_유지된다() {
        original();
        long upper = original();
        var options = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), upper, 10, 80);
        maintenance.start(options);
        assertThat(maintenance.advance(options.runId())).isTrue();
        original();
        maintenance.start(options);
        drain(options.runId());
        var status = maintenance.status(options.runId());
        assertThat(status.registration().state()).isEqualTo("SCANNED");
        assertThat(status.registration().cursorId()).isEqualTo(upper);
        assertThat(status.registration().enqueued()).isEqualTo(2);
        assertThat(status.completion().jobStates()).containsEntry("PENDING", 2L);
        assertThat(status.completion().currentlyUsableLocations()).isZero();
        worker.runOnce();
        assertThat(maintenance.status(options.runId()).completion().currentlyUsableLocations()).isEqualTo(2);
        assertThat(provider.calls.get()).isEqualTo(2);
        var changed = options(Command.START, Mode.BACKFILL, true, options.runId(), reader.upperId(), 10, 80);
        assertThatThrownBy(() -> maintenance.start(changed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 마이크로초_갱신기간은_최초와_반복_START에서_정확하게_유지된다() {
        long upper = original();
        Duration refresh = Duration.parse("PT3.000001S");
        var options = new LocationMaintenanceProperties(Command.START, Mode.BACKFILL, true,
                UUID.randomUUID(), after, upper, 50, 2, 10, 80, refresh, Duration.ofSeconds(2),
                false, Duration.ofSeconds(1));
        maintenance.start(options);
        var initial = maintenance.status(options.runId()).registration();
        assertThat(Duration.between(initial.asOf(), initial.refreshBefore())).isEqualTo(refresh);
        assertThat(maintenance.advance(options.runId())).isTrue();

        maintenance.start(options);
        var repeated = maintenance.status(options.runId()).registration();
        assertThat(repeated.asOf()).isEqualTo(initial.asOf());
        assertThat(repeated.refreshBefore()).isEqualTo(initial.refreshBefore());
        assertThat(repeated.enqueued()).isEqualTo(1);
        assertThat(count("restaurant_location_job")).isEqualTo(1);
    }

    @Test
    void 두실행과_두진행자가_경쟁해도_현재주소작업과_누적상한은_중복되지_않는다() throws Exception {
        for (int i = 0; i < 8; i++) { original(); }
        long upper = reader.upperId();
        var first = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), upper, 3, 16);
        var second = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), upper, 3, 16);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { await(start); runner.execute(first); });
            var b = executor.submit(() -> { await(start); runner.execute(first); });
            var c = executor.submit(() -> { await(start); runner.execute(second); });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            c.get(30, TimeUnit.SECONDS);
        }
        assertThat(maintenance.status(first.runId()).registration().enqueued()).isEqualTo(2);
        assertThat(maintenance.status(second.runId()).registration().enqueued()).isEqualTo(2);
        assertThat(count("restaurant_location_job")).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT restaurant_id) FROM restaurant_location_job
                """, Long.class)).isEqualTo(4);
        assertThat(maintenance.status(first.runId()).registration().state()).isEqualTo("LIMIT_REACHED");
    }

    @Test
    void 작은예산과_등록상한을_재개로_늘릴수_없고_실제여덟번시도이후_자동재등록하지_않는다() {
        long id = original();
        var small = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), id, 10, 7);
        runner.execute(small);
        assertThat(maintenance.status(small.runId()).registration().enqueued()).isZero();
        var one = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), id, 10, 8);
        runner.execute(one);
        provider.answer.set(address -> new GeocodingResult.Failure(GeocodingResult.FailureKind.TRANSIENT_ERROR, null));
        for (int attempt = 1; attempt <= 8; attempt++) {
            worker.process(target(id));
            if (attempt < 8) { makeDue(id); }
        }
        assertThat(provider.calls.get()).isEqualTo(8);
        assertThat(locations.get(id).locationStatus()).isEqualTo("FAILED");
        worker.process(target(id));
        maintenance.resume(one.runId());
        drain(one.runId());
        runner.execute(options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), id, 10, 80));
        runner.execute(options(Command.START, Mode.REFRESH, true, UUID.randomUUID(), id, 10, 80));
        assertThat(provider.calls.get()).isEqualTo(8);
        assertThat(count("restaurant_location_job")).isEqualTo(1);
        assertThat(maintenance.status(one.runId()).completion().reservedAttempts()).isEqualTo(8);
        // Administrator retry is a new, unlinked job and remains subject to the shared daily budget.
        locations.retry(id, 1);
        assertThat(count("restaurant_location_job")).isEqualTo(2);
        assertThat(maintenance.status(one.runId()).registration().enqueued()).isEqualTo(1);
        assertThat(maintenance.status(one.runId()).completion().reservedAttempts()).isEqualTo(8);
    }

    @Test
    void checkpoint_쓰기실패는_위치와_job과_연결을_함께롤백하고_같은지점에서_재개한다() {
        long id = original();
        var options = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), id, 10, 80);
        maintenance.start(options);
        jdbc.execute("""
                CREATE TRIGGER fail_location_checkpoint BEFORE UPDATE ON restaurant_location_maintenance_run
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic checkpoint failure'
                """);
        try {
            assertThatThrownBy(() -> maintenance.advance(options.runId())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER fail_location_checkpoint");
        }
        assertThat(locations.get(id).locationStatus()).isEqualTo("UNRESOLVED");
        assertThat(count("restaurant_location_job")).isZero();
        assertThat(count("restaurant_location_maintenance_job")).isZero();
        assertThat(maintenance.status(options.runId()).registration().cursorId()).isEqualTo(after);
        drain(options.runId());
        assertThat(maintenance.status(options.runId()).registration().enqueued()).isEqualTo(1);
    }

    @Test
    void 중단commit_뒤에는_등록하지않고_진행HTTP는_계속되며_전역disable은_새claim만_막는다() throws Exception {
        long first = original();
        long second = original();
        var options = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), second, 10, 80);
        maintenance.start(options);
        maintenance.advance(options.runId());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.answer.set(address -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            await(release);
            return new GeocodingResult.Candidates(List.of(candidate()));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> worker.process(target(first)));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            try {
                executor.submit(() -> maintenance.stop(options.runId())).get(5, TimeUnit.SECONDS);
                jdbc.update("UPDATE restaurant_geocoding_budget SET enabled=false WHERE id=1");
                assertThat(maintenance.advance(options.runId())).isFalse();
                // START cannot accidentally undo STOP.
                maintenance.start(options);
                assertThat(maintenance.advance(options.runId())).isFalse();
                maintenance.resume(options.runId());
                maintenance.advance(options.runId());
                worker.process(target(second));
                assertThat(provider.calls.get()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            running.get(15, TimeUnit.SECONDS);
        }
        assertThat(locations.get(first).locationStatus()).isEqualTo("READY");
        assertThat(locations.get(second).locationStatus()).isEqualTo("PENDING");
    }

    @Test
    void 중단은_경합중인_한건의_commit을_기다리고_그이후_등록은_차단한다() throws Exception {
        long first = original();
        long upper = original();
        var options = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), upper, 10, 80);
        maintenance.start(options);
        CountDownLatch parentLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holding = executor.submit(() -> tx.executeWithoutResult(status -> {
                restaurants.findByIdForUpdate(first).orElseThrow();
                parentLocked.countDown();
                await(release);
            }));
            assertThat(parentLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var advance = executor.submit(() -> maintenance.advance(options.runId()));
            // Observe the actual MySQL row-lock wait before submitting STOP.
            waitForDatabaseLockWait();
            var stop = executor.submit(() -> maintenance.stop(options.runId()));
            release.countDown();
            holding.get(15, TimeUnit.SECONDS);
            advance.get(15, TimeUnit.SECONDS);
            stop.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(maintenance.advance(options.runId())).isFalse();
        assertThat(maintenance.status(options.runId()).registration().enqueued()).isEqualTo(1);
    }

    @Test
    void 두worker와_lease복구도_run의_기존job과_누적attempt를_유지한다() throws Exception {
        long id = original();
        var options = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), id, 10, 80);
        runner.execute(options);
        var target = target(id);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { await(start); return workerTransactions.claim(target); });
            var b = executor.submit(() -> { await(start); return workerTransactions.claim(target); });
            start.countDown();
            var claims = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(java.util.Optional::isPresent)).hasSize(1);
            var old = claims.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
            jdbc.update("""
                    UPDATE restaurant_location_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,
                        reserved_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?
                    """, target.jobId());
            worker.process(target);
            assertThat(workerTransactions.complete(old, new LocationJobTransactions.Outcome(point(), null, null)))
                    .isFalse();
        }
        assertThat(maintenance.status(options.runId()).completion().reservedAttempts()).isEqualTo(2);
        assertThat(maintenance.status(options.runId()).completion().currentlyUsableLocations()).isEqualTo(1);
        assertThat(count("restaurant_location_maintenance_job")).isEqualTo(1);
    }

    @Test
    void 잠금대기중_삭제와_주소수정이_일어나면_최신상태를_다시확인한다() throws Exception {
        long deleted = original();
        long updated = original();
        var options = options(Command.START, Mode.BACKFILL, true, UUID.randomUUID(), updated, 10, 80);
        maintenance.start(options);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var changing = executor.submit(() -> tx.executeWithoutResult(status -> {
                var restaurant = restaurants.findByIdForUpdate(deleted).orElseThrow();
                restaurant.softDelete();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var scanning = executor.submit(() -> maintenance.advance(options.runId()));
            waitForDatabaseLockWait();
            release.countDown();
            changing.get(15, TimeUnit.SECONDS);
            scanning.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        tx.executeWithoutResult(status -> {
            var restaurant = restaurants.findByIdForUpdate(updated).orElseThrow();
            restaurant.updateBasicInfo(null, null, null, null, ADDRESS + "別館", null, null, null, null, null, null, null);
            locations.enqueue(restaurant);
        });
        drain(options.runId());
        assertThat(maintenance.status(options.runId()).registration().enqueued()).isZero();
        assertThat(count("restaurant_location_job")).isEqualTo(1);
    }

    @Test
    void 갱신은_기존좌표를_즉시지우고_실패나_재실행으로_옛수명을_연장하지않는다() {
        long id = ready(Duration.ofHours(3), false);
        var options = options(Command.START, Mode.REFRESH, true, UUID.randomUUID(), id, 10, 80);
        runner.execute(options);
        assertCleared(id, "PENDING");
        provider.answer.set(address -> new GeocodingResult.NoResults());
        worker.process(target(id));
        assertCleared(id, "REVIEW_REQUIRED");
        runner.execute(options(Command.START, Mode.REFRESH, true, UUID.randomUUID(), id, 10, 80));
        assertThat(count("restaurant_location_job")).isEqualTo(1);
        assertThat(maintenance.status(options.runId()).completion().currentlyUsableLocations()).isZero();
    }

    @Test
    void provider와_예산이_꺼져도_삭제식당까지_기한전에_물리정리하고_원본과자식을_보존한다() {
        long active = ready(Duration.ofMinutes(20), false);
        long deleted = ready(Duration.ofMinutes(10), true);
        long future = ready(Duration.ofDays(3), false);
        jdbc.update("UPDATE restaurant_geocoding_budget SET enabled=false WHERE id=1");
        var result = retention.purge(options(Command.PURGE, Mode.BACKFILL, true, null, null, 10, 80));
        assertThat(result.purged()).isEqualTo(2);
        assertThat(result.dueRemaining()).isZero();
        assertCleared(active, "REVIEW_REQUIRED");
        assertCleared(deleted, "REVIEW_REQUIRED");
        assertThat(locations.get(future).locationStatus()).isEqualTo("READY");
        assertThat(provider.calls.get()).isZero();
        tx.executeWithoutResult(status -> {
            for (long id : List.of(active, deleted)) {
                var restaurant = restaurants.findById(id).orElseThrow();
                assertThat(restaurant.getName()).isEqualTo("합성 식당");
                assertThat(restaurant.getAddress()).isEqualTo(ADDRESS);
                assertThat(restaurant.getMapRegionId()).isEqualTo(42);
                assertThat(restaurant.getHashtags()).containsExactly("보존");
            }
        });
    }

    @Test
    void 오래된정리snapshot은_주소수정과_새결과를_지우지않고_백업의만료값도_노출하지않는다() {
        long id = ready(Duration.ofMinutes(20), false);
        var old = reader.purgeCandidates(reader.now().plusHours(1), 100).getFirst();
        tx.executeWithoutResult(status -> {
            var restaurant = restaurants.findByIdForUpdate(id).orElseThrow();
            restaurant.updateBasicInfo(null, null, null, null, ADDRESS + "別館", null, null, null, null, null, null, null);
            var location = restaurant.getLocation();
            var now = reader.now();
            restaurant.completeLocation(location.getAddressRevision(), location.getRequestId(), point(),
                    RestaurantLocationSource.GOOGLE_GEOCODING, now, now.plusDays(2), clock(now));
        });
        assertThat(purge.purge(old, Duration.ofHours(1))).isFalse();
        assertThat(locations.get(id).locationStatus()).isEqualTo("READY");
        // Synthetic backup restore: reinsert expired calendar values, while the scheduler is stopped.
        jdbc.update("""
                UPDATE restaurant_location l JOIN restaurant r ON r.location_id=l.id
                SET l.obtained_at=UTC_TIMESTAMP(6)-INTERVAL 2 DAY,
                    l.valid_until=UTC_TIMESTAMP(6)-INTERVAL 1 DAY WHERE r.id=?
                """, id);
        tx.executeWithoutResult(status -> assertThat(restaurants.findById(id).orElseThrow()
                .hasUsableMapLocation(clock(reader.now()))).isFalse());
        assertThat(retention.purge(options(Command.PURGE, Mode.BACKFILL, true, null, null, 10, 80)).purged()).isEqualTo(1);
        assertCleared(id, "REVIEW_REQUIRED");
    }

    @Test
    void JVM_UTC와_JDBC_서울에서도_기준시각과_갱신조건과_상태집계가_UTC를_유지한다() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Integer.class))
                .isEqualTo(9 * 3600);
        long id = ready(Duration.ofHours(3), false);
        var options = options(Command.START, Mode.REFRESH, true, UUID.randomUUID(), id, 10, 80);
        maintenance.start(options);
        var run = maintenance.status(options.runId()).registration();
        String raw = jdbc.queryForObject("""
                SELECT DATE_FORMAT(as_of, '%Y-%m-%d %H:%i:%s.%f') FROM restaurant_location_maintenance_run WHERE id=?
                """, String.class, options.runId().toString());
        assertThat(raw).isEqualTo(LocationMaintenanceReader.sqlTime(run.asOf()));
        assertThat(Duration.between(run.asOf(), reader.now()).abs()).isLessThan(Duration.ofSeconds(5));
        drain(options.runId());
        worker.process(target(id));
        assertThat(maintenance.status(options.runId()).completion().currentlyUsableLocations()).isEqualTo(1);
        assertThat(reader.purgeCandidates(reader.now().plusHours(1), 100)).isEmpty();
    }

    @Test
    void 겹치는갱신run은_방금완료한_새결과를_다시등록하지않는다() {
        long id = ready(Duration.ofHours(3), false);
        var first = options(Command.START, Mode.REFRESH, true, UUID.randomUUID(), id, 10, 80);
        var second = options(Command.START, Mode.REFRESH, true, UUID.randomUUID(), id, 10, 80);
        maintenance.start(first);
        maintenance.start(second);
        drain(first.runId());
        worker.process(target(id));
        drain(second.runId());
        assertThat(maintenance.status(first.runId()).registration().enqueued()).isEqualTo(1);
        assertThat(maintenance.status(second.runId()).registration().enqueued()).isZero();
        assertThat(count("restaurant_location_job")).isEqualTo(1);
        // Default refresh window equals this synthetic worker's full 1-day lifetime: fail closed.
        var later = options(Command.START, Mode.REFRESH, true, UUID.randomUUID(), id, 10, 80);
        runner.execute(later);
        assertThat(maintenance.status(later.runId()).registration().enqueued()).isZero();
    }

    @Test
    void 정리쿼리는_UTC문자열경계의_1마이크로초를_구분한다() {
        long id = ready(Duration.ofMinutes(20), false);
        LocalDateTime boundary = reader.now().plusHours(1);
        jdbc.update("""
                UPDATE restaurant_location l JOIN restaurant r ON r.location_id=l.id
                SET l.valid_until=? WHERE r.id=?
                """, LocationMaintenanceReader.sqlTime(boundary), id);
        assertThat(reader.purgeCandidates(boundary.minusNanos(1000), 10)).isEmpty();
        assertThat(reader.purgeCandidates(boundary, 10)).extracting(LocationMaintenanceReader.Candidate::restaurantId)
                .containsExactly(id);
        tx.executeWithoutResult(status -> {
            var restaurant = restaurants.findById(id).orElseThrow();
            assertThat(restaurant.hasUsableMapLocation(clock(boundary.minusNanos(1000)))).isTrue();
            assertThat(restaurant.hasUsableMapLocation(clock(boundary))).isFalse();
        });
    }

    @Test
    void 정리잠금대기중_새결과가저장돼도_오래된snapshot은_삭제하지않는다() throws Exception {
        long id = ready(Duration.ofMinutes(20), false);
        var old = reader.purgeCandidates(reader.now().plusHours(1), 100).getFirst();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var replacing = executor.submit(() -> tx.executeWithoutResult(status -> {
                var restaurant = restaurants.findByIdForUpdate(id).orElseThrow();
                restaurant.refreshLocation();
                var now = reader.now();
                var location = restaurant.getLocation();
                restaurant.completeLocation(location.getAddressRevision(), location.getRequestId(), point(),
                        RestaurantLocationSource.GOOGLE_GEOCODING, now, now.plusDays(2), clock(now));
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var purging = executor.submit(() -> purge.purge(old, Duration.ofHours(1)));
            waitForDatabaseLockWait();
            release.countDown();
            replacing.get(15, TimeUnit.SECONDS);
            assertThat(purging.get(15, TimeUnit.SECONDS)).isFalse();
        } finally {
            release.countDown();
        }
        assertThat(locations.get(id).locationStatus()).isEqualTo("READY");
    }

    @Test
    void 합성대량자료에서도_keyset조회수는_batch수에만_비례하고_EXPLAIN은_PK정렬읽기를_사용한다() throws Exception {
        for (int i = 0; i < 120; i++) { original(); }
        var options = new LocationMaintenanceProperties(Command.DRY_RUN, Mode.BACKFILL, false, null, after,
                reader.upperId(), 25, 2, 10, 80, null, null, false, null);
        inspection.inspect(options); // Warm the owned pool before counting server statements.
        var root = rootJdbc();
        root.execute("SET GLOBAL general_log=OFF");
        root.execute("SET GLOBAL log_output='TABLE'");
        root.execute("TRUNCATE TABLE mysql.general_log");
        LocationMaintenanceInspection.Report report;
        root.execute("SET GLOBAL general_log=ON");
        try {
            report = inspection.inspect(options);
        } finally {
            root.execute("SET GLOBAL general_log=OFF");
        }
        List<String> selects = root.queryForList("""
                SELECT argument FROM mysql.general_log
                WHERE user_host LIKE 'hashi[%' AND command_type='Query'
                    AND UPPER(argument) LIKE 'SELECT %'
                """, String.class);
        var businessSelects = selects.stream().filter(sql -> !sql.startsWith("SELECT @@")).toList();
        assertThat(report.inspected()).isEqualTo(50);
        assertThat(report.partial()).isTrue();
        assertThat(businessSelects).hasSize(4); // DB clock + 2 pages + one lookahead; zero per-item SELECTs.
        assertThat(selects.size() - businessSelects.size()).isLessThanOrEqualTo(1); // Driver session read-only check.
        String plan = jdbc.queryForObject("EXPLAIN FORMAT=JSON " + LocationMaintenanceReader.PAGE_SQL,
                String.class, after, reader.upperId(), 25);
        var tablePlan = new ObjectMapper().readTree(plan).findValues("table").stream()
                .filter(table -> "r".equals(table.path("table_name").asText())).findFirst().orElseThrow();
        assertThat(tablePlan.path("key").asText()).isEqualTo("PRIMARY");
        assertThat(tablePlan.path("access_type").asText()).isIn("range", "index");
        assertThat(plan).doesNotContain("\"using_filesort\": true");
        System.out.println("Synthetic inspection: rows=120 inspected=50 pages=2 application SELECTs="
                + businessSelects.size() + " total SELECTs=" + selects.size()
                + " partial=true; EXPLAIN primary-key " + tablePlan.path("access_type").asText()
                + ". Not an operational performance guarantee.");
    }

    @Test
    void 배포jar의_실제launcher는_SELECT전용계정으로_확인하고_새프로세스에서_재개한다() throws Exception {
        original();
        long upper = original();
        var root = rootJdbc();
        root.execute("CREATE USER IF NOT EXISTS 'maintenance_reader'@'%' IDENTIFIED BY 'fixture_only'");
        root.execute("GRANT SELECT ON location_maintenance.* TO 'maintenance_reader'@'%'");
        long migrations = count("flyway_schema_history");
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var originalLogLevel = rootLogger.getLevel();
        String output = runCli("maintenance_reader", "fixture_only",
                "--hashi.map.maintenance.after-id=" + after, "--hashi.map.maintenance.upper-id=" + upper);
        assertThat(output).contains("inspected=2", "partial=false").doesNotContain(ADDRESS, "latitude", "longitude");
        assertThat(count("flyway_schema_history")).isEqualTo(migrations);
        assertThat(count("restaurant_location_maintenance_run")).isZero();
        assertThat(count("restaurant_location_job")).isZero();
        assertThat(provider.calls.get()).isZero();

        UUID runId = UUID.randomUUID();
        runCli("hashi", "hashi", "--hashi.map.maintenance.command=START",
                "--hashi.map.maintenance.execute=true", "--hashi.map.maintenance.run-id=" + runId,
                "--hashi.map.maintenance.after-id=" + after, "--hashi.map.maintenance.upper-id=" + upper,
                "--hashi.map.maintenance.batch-size=1", "--hashi.map.maintenance.max-batches=1");
        assertThat(maintenance.status(runId).registration().enqueued()).isEqualTo(1);
        runCli("hashi", "hashi", "--hashi.map.maintenance.command=RESUME",
                "--hashi.map.maintenance.execute=true", "--hashi.map.maintenance.run-id=" + runId);
        assertThat(maintenance.status(runId).registration().enqueued()).isEqualTo(2);
        assertThat(maintenance.status(runId).registration().state()).isEqualTo("SCANNED");

        // Inspect the same bootstrap without SpringApplication's JVM-global logging initialization.
        new ApplicationContextRunner().withUserConfiguration(LocationMaintenanceCli.CliConfiguration.class)
                .withPropertyValues("spring.profiles.active=location-maintenance-cli",
                        "spring.datasource.url=" + MYSQL.getJdbcUrl(), "spring.datasource.username=hashi",
                        "spring.datasource.password=hashi", "spring.jpa.hibernate.ddl-auto=validate")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(GeocodingProvider.class)).isEmpty();
                    assertThat(context.getBeansOfType(RestaurantLocationWorker.class)).isEmpty();
                    assertThat(context.getBeansOfType(LocationRetentionScheduler.class)).isEmpty();
                    assertThat(context.getBeansOfType(org.flywaydb.core.Flyway.class)).isEmpty();
                });
        assertThat(rootLogger.getLevel()).isEqualTo(originalLogLevel);
    }

    private String runCli(String username, String password, String... args) throws Exception {
        String jar = System.getProperty("location.maintenance.jar");
        assertThat(jar).isNotBlank();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dloader.main=" + LocationMaintenanceCli.class.getName(), "-cp", jar,
                "org.springframework.boot.loader.launch.PropertiesLauncher",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--logging.config=" + Path.of("src/test/resources/logback-maintenance-test.xml").toAbsolutePath()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private LocationMaintenanceProperties options(Command command, Mode mode, boolean execute, UUID runId,
                                                   Long upper, int registrations, int calls) {
        return new LocationMaintenanceProperties(command, mode, execute, runId, after, upper, 50, 2,
                registrations, calls, null, null, false, null);
    }

    private long original() {
        return tx.execute(status -> {
            Restaurant restaurant = Restaurant.create("합성 식당", "試験", "요약", "설명", ADDRESS, "표시 지역",
                    RestaurantGenre.SUSHI, "초밥", RestaurantPlaceType.RESTAURANT, PriceCurrency.JPY,
                    BigDecimal.ONE, BigDecimal.TEN);
            restaurant.assignMapRegion(42L);
            restaurant.replaceHashtags(List.of("보존"));
            return restaurants.saveAndFlush(restaurant).getId();
        });
    }

    private long ready(Duration remaining, boolean deleted) {
        long id = original();
        tx.executeWithoutResult(status -> {
            var restaurant = restaurants.findByIdForUpdate(id).orElseThrow();
            restaurant.requestLocationResolution();
            var now = reader.now();
            var location = restaurant.getLocation();
            // Start with a valid result; an expired fixture is written below as a restored DB value.
            restaurant.completeLocation(location.getAddressRevision(), location.getRequestId(), point(),
                    RestaurantLocationSource.GOOGLE_GEOCODING, now.minusDays(1), now.plusDays(4), clock(now));
            if (deleted) { restaurant.softDelete(); }
        });
        jdbc.update("""
                UPDATE restaurant_location l JOIN restaurant r ON r.location_id=l.id SET l.valid_until=? WHERE r.id=?
                """, LocationMaintenanceReader.sqlTime(reader.now().plus(remaining)), id);
        return id;
    }

    private void enqueue(long id) {
        tx.executeWithoutResult(status -> locations.enqueue(restaurants.findByIdForUpdate(id).orElseThrow()));
    }

    private void drain(UUID id) {
        for (int i = 0; i < 100 && maintenance.advance(id); i++) { /* fixture upper bound */ }
    }

    private LocationJobTransactions.Target target(long id) {
        return tx.execute(status -> {
            var restaurant = restaurants.findById(id).orElseThrow();
            var job = jobs.findByRestaurantIdAndRequestId(id, restaurant.getLocation().getRequestId()).orElseThrow();
            return new LocationJobTransactions.Target(id, job.getId());
        });
    }

    private void makeDue(long id) {
        jdbc.update("UPDATE restaurant_location_job SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE restaurant_id=?", id);
        jdbc.update("""
                UPDATE restaurant_location l JOIN restaurant r ON r.location_id=l.id
                SET l.next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE r.id=?
                """, id);
    }

    private void assertCleared(long id, String state) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT l.status, l.latitude, l.longitude, l.source, l.obtained_at, l.valid_until
                FROM restaurant r JOIN restaurant_location l ON l.id=r.location_id WHERE r.id=?
                """, id);
        assertThat(row.get("status")).isEqualTo(state);
        for (String column : List.of("latitude", "longitude", "source", "obtained_at", "valid_until")) {
            assertThat(row.get(column)).as(column).isNull();
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private JdbcTemplate rootJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
    }

    private void waitForDatabaseLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var root = rootJdbc();
        while (System.nanoTime() < deadline) {
            if (root.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits", Long.class) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected an actual MySQL row-lock wait");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) { throw new AssertionError("Fixture latch timed out"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static Clock clock(LocalDateTime now) { return Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
    private static MapCoordinates point() { return MapCoordinates.of(new BigDecimal("10.5"), new BigDecimal("20.5")); }

    private static GeocodingCandidate candidate() {
        var components = List.of(component("日本", "JP", "country"),
                component("東京都", "東京都", "administrative_area_level_1"), component("試験区", "試験区", "locality"),
                component("架空町", "架空町", "sublocality_level_1"), component("1丁目", "1丁目", "sublocality_level_2"),
                component("2番", "2", "sublocality_level_3"), component("3号", "3", "sublocality_level_4"));
        return new GeocodingCandidate(new BigDecimal("10.5"), new BigDecimal("20.5"),
                GeocodingCandidate.Granularity.ROOFTOP, "JP", "東京都", components, List.of("street_address"));
    }

    private static GeocodingCandidate.AddressComponent component(String value, String shortValue, String type) {
        return new GeocodingCandidate.AddressComponent(value, shortValue, List.of(type));
    }

    static class FakeProvider implements GeocodingProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Function<String, GeocodingResult>> answer = new AtomicReference<>();
        public GeocodingResult geocode(String address) {
            calls.incrementAndGet();
            return answer.get().apply(address);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean LocationJobProperties jobs() {
            return new LocationJobProperties(true, Duration.ofDays(1), BigDecimal.TEN, new BigDecimal("11"),
                    new BigDecimal("20"), new BigDecimal("21"), 8);
        }
        @Bean FakeProvider provider() { return new FakeProvider(); }
    }
}
