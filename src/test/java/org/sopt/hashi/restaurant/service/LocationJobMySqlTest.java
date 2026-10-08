package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.sopt.hashi.config.TimeConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.BusinessHourCommand;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.sopt.hashi.restaurant.internal.map.RestaurantLocationWorker;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Claim;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Outcome;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Target;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
@Import({RestaurantService.class, RestaurantPortImpl.class, RestaurantLocationService.class, LocationJobTransactions.class,
        LocationAdoptionPolicy.class, LocationRetryPolicy.class, RestaurantLocationWorker.class,
        TimeConfig.class, LocationJobMySqlTest.Fixtures.class})
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocationJobMySqlTest {
    private static final AtomicInteger RESTAURANT_SEQUENCE = new AtomicInteger();
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("location_jobs").withUsername("hashi").withPassword("hashi")
            .withCommand("--log-bin-trust-function-creators=1")
            .withUrlParam("connectionTimeZone", "Asia/Seoul")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Autowired RestaurantService restaurants;
    @Autowired RestaurantPort restaurantPort;
    @Autowired RestaurantRepository restaurantRepository;
    @Autowired RestaurantLocationService locations;
    @MockitoSpyBean RestaurantLocationJobRepository jobs;
    @Autowired LocationJobTransactions transactions;
    @Autowired RestaurantLocationWorker worker;
    @MockitoSpyBean LocationJobProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired FakeProvider provider;
    @MockitoBean MediaPort mediaPort;
    @MockitoBean FileStorage fileStorage;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM restaurant_location_job");
        jdbc.update("""
                UPDATE restaurant_geocoding_budget SET enabled=true, daily_limit=100, max_concurrent=4,
                reserved_calls=0, budget_day=NULL, blocked_until=NULL WHERE id=1
                """);
        provider.answer.set(address -> new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate())));
    }

    @Test
    void 저장_작업_등록_자동_완료와_UTC_원시_시각이_실제_DB에서_일치한다() {
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Integer.class))
                .isEqualTo(9 * 60 * 60);
        var response = restaurants.createByAdmin(createCommand());
        assertThat(response.locationStatus()).isEqualTo("PENDING");
        assertThat(response.addressRevision()).isEqualTo(1);
        Target target = target(response.restaurantId());
        Claim claim = transactions.claim(target).orElseThrow();
        assertThat(Duration.between(claim.obtainedAt().toInstant(ZoneOffset.UTC), Instant.now()).abs())
                .isLessThan(Duration.ofSeconds(10));
        assertThat(rawTime("lease_until", target.jobId())).isEqualTo(claim.leaseUntil());
        assertThat(rawTime("reserved_until", target.jobId())).isEqualTo(claim.leaseUntil());
        assertThat(transactions.complete(claim, ready())).isTrue();
        var status = locations.get(response.restaurantId());
        assertThat(status.locationStatus()).isEqualTo("READY");
        assertThat(status.attempt()).isEqualTo(1);
        assertThat(status.validUntil()).isEqualTo(claim.obtainedAt().plusDays(1).toInstant(ZoneOffset.UTC));
        assertThat(jdbc.queryForObject("""
                SELECT DATE_FORMAT(l.obtained_at, '%Y-%m-%dT%H:%i:%s.%f') FROM restaurant r
                JOIN restaurant_location l ON l.id=r.location_id WHERE r.id=?
                """, String.class, response.restaurantId())).isEqualTo(format(claim.obtainedAt()));
        assertThat(used()).isEqualTo(1);
        assertThat(transactions.complete(claim, Outcome.failure(FailureKind.ACCESS_DENIED))).isFalse();
    }

    @Test
    void 명시_위치확인주소를_provider와_채택정책에_사용하고_표시주소는_보존한다() {
        AtomicReference<String> providerInput = new AtomicReference<>();
        provider.answer.set(address -> {
            providerInput.set(address);
            return new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate()));
        });
        var response = restaurants.createByAdmin(createCommandWithGeocodingAddress());

        worker.process(target(response.restaurantId()));

        assertThat(providerInput).hasValue(LocationAdoptionPolicyTest.ADDRESS);
        assertThat(response.address()).contains("架空ビル");
        assertThat(response.geocodingAddress()).isEqualTo(LocationAdoptionPolicyTest.ADDRESS);
        assertThat(locations.get(response.restaurantId()).locationStatus()).isEqualTo("READY");
    }

    @Test
    void 위치확인주소만_바꿔도_이전_job결과를_막고_공백으로_지우면_표시주소로_fallback한다() {
        var created = restaurants.createByAdmin(createCommandWithGeocodingAddress());
        Long id = created.restaurantId();
        Claim first = transactions.claim(target(id)).orElseThrow();

        var changed = restaurants.updateByAdmin(id,
                geocodingAddressCommand("  東京都試験区架空町1丁目2番4号  "));

        assertThat(changed.addressRevision()).isEqualTo(2);
        assertThat(changed.geocodingAddress()).isEqualTo("東京都試験区架空町1丁目2番4号");
        assertThat(transactions.complete(first, ready())).isFalse();
        Claim second = transactions.claim(target(id)).orElseThrow();
        assertThat(second.geocodingAddress()).isEqualTo("東京都試験区架空町1丁目2番4号");

        var cleared = restaurants.updateByAdmin(id, geocodingAddressCommand("\u00A0\u2007\u202F"));

        assertThat(cleared.addressRevision()).isEqualTo(3);
        assertThat(cleared.geocodingAddress()).isNull();
        assertThat(transactions.complete(second, ready())).isFalse();
        Claim fallback = transactions.claim(target(id)).orElseThrow();
        assertThat(fallback.geocodingAddress()).isEqualTo(cleared.address());
    }

    @Test
    void 같은_주소_갱신의_재시도와_실패도_기존_좌표와_만료를_보존한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        transactions.complete(transactions.claim(target(id)).orElseThrow(), ready());
        var before = readLocation(id);
        tx.executeWithoutResult(ignored -> {
            var restaurant = restaurantRepository.findByIdForUpdate(id).orElseThrow();
            restaurant.refreshLocation();
            locations.enqueue(restaurant);
        });
        Target refresh = target(id);
        transactions.complete(transactions.claim(refresh).orElseThrow(), Outcome.failure(FailureKind.TRANSIENT_ERROR));
        assertThat(locations.get(id).locationStatus()).isEqualTo("RETRY_WAIT");
        assertRetainedLocation(id, before);
        due(id, refresh.jobId());
        transactions.complete(transactions.claim(refresh).orElseThrow(), Outcome.failure(FailureKind.ACCESS_DENIED));
        assertThat(locations.get(id).locationStatus()).isEqualTo("FAILED");
        assertRetainedLocation(id, before);
        assertThat(readLocation(id).isUsable(
                java.time.Clock.fixed(before.getValidUntil().toInstant(ZoneOffset.UTC), ZoneOffset.UTC))).isFalse();
        restaurants.updateByAdmin(id, addressCommand("東京都試験区架空町1-2-4"));
        var changed = readLocation(id);
        assertThat(changed.getCoordinates()).isNull();
        assertThat(changed.getValidUntil()).isNull();
    }

    private org.sopt.hashi.restaurant.domain.RestaurantLocation readLocation(Long id) {
        return tx.execute(ignored -> {
            var location = restaurantRepository.findById(id).orElseThrow().getLocation();
            org.hibernate.Hibernate.initialize(location);
            return location;
        });
    }

    private void assertRetainedLocation(Long id, org.sopt.hashi.restaurant.domain.RestaurantLocation before) {
        var actual = readLocation(id);
        assertThat(actual.getCoordinates()).usingRecursiveComparison().isEqualTo(before.getCoordinates());
        assertThat(actual.getSource()).isEqualTo(before.getSource());
        assertThat(actual.getObtainedAt()).isEqualTo(before.getObtainedAt());
        assertThat(actual.getValidUntil()).isEqualTo(before.getValidUntil());
        assertThat(actual.isUsable(java.time.Clock.systemUTC())).isTrue();
    }

    @Test
    void 전역_호출_중단과_예산_소진은_후보를_읽기_전에_거르고_다음날_다시_허용한다() {
        Target target = target(restaurants.createByAdmin(createCommand()).restaurantId());
        assertThat(transactions.candidates()).contains(target);
        jdbc.update("UPDATE restaurant_geocoding_budget SET enabled=false WHERE id=1");
        assertThat(transactions.candidates()).isEmpty();
        jdbc.update("UPDATE restaurant_geocoding_budget SET enabled=true, blocked_until=UTC_TIMESTAMP(6)+INTERVAL 1 HOUR WHERE id=1");
        assertThat(transactions.candidates()).isEmpty();
        jdbc.update("UPDATE restaurant_geocoding_budget SET blocked_until=NULL, budget_day=UTC_DATE(), reserved_calls=100 WHERE id=1");
        assertThat(transactions.candidates()).isEmpty();
        jdbc.update("UPDATE restaurant_geocoding_budget SET budget_day=UTC_DATE()-INTERVAL 1 DAY WHERE id=1");
        assertThat(transactions.candidates()).contains(target);
        assertThat(used()).isEqualTo(100);
        assertThat(transactions.claim(target)).isPresent();
        assertThat(used()).isEqualTo(1);
        jdbc.update("UPDATE restaurant_geocoding_budget SET max_concurrent=1 WHERE id=1");
        assertThat(transactions.candidates()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 예산이_닫혀도_만료된_마지막_시도는_대기중인_50개_뒤에서_종료한다(boolean disabled) {
        doReturn(1).when(properties).maxAttempts();
        for (int i = 0; i < 51; i++) {
            restaurants.createByAdmin(createCommand());
        }
        jdbc.update("UPDATE restaurant_location_job SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 DAY");
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target last = target(id);
        jdbc.update("UPDATE restaurant_geocoding_budget SET daily_limit=1 WHERE id=1");
        transactions.claim(last).orElseThrow();
        expire(last.jobId());
        if (disabled) {
            jdbc.update("UPDATE restaurant_geocoding_budget SET enabled=false WHERE id=1");
        }
        provider.answer.set(address -> { throw new AssertionError("cleanup must not call provider"); });
        assertThat(transactions.candidates()).containsExactly(last);
        worker.runOnce();
        assertThat(locations.get(id).locationStatus()).isEqualTo("FAILED");
        assertThat(locations.get(id).failureCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(locations.get(id).canRetry()).isTrue();
        assertThat(used()).isEqualTo(1);
        assertThat(transactions.candidates()).isEmpty();
    }

    @Test
    void 설정_오류는_예산이_닫혀도_호출없이_실패로_정리한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        doReturn(false).when(properties).isConfigured();
        jdbc.update("UPDATE restaurant_geocoding_budget SET enabled=false, daily_limit=0 WHERE id=1");
        provider.answer.set(address -> { throw new AssertionError("invalid configuration must not call provider"); });
        worker.runOnce();
        assertThat(locations.get(id).locationStatus()).isEqualTo("FAILED");
        assertThat(locations.get(id).failureCode()).isEqualTo("CONFIGURATION_ERROR");
        assertThat(locations.get(id).canRetry()).isTrue();
        assertThat(used()).isZero();
    }

    @Test
    void 애플리케이션_시계가_앞서도_새_PENDING_작업은_즉시_처리한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target target = target(id);
        jdbc.update("UPDATE restaurant_location_job SET next_attempt_at=UTC_TIMESTAMP(6)+INTERVAL 9 HOUR WHERE id=?",
                target.jobId());
        assertThat(transactions.candidates()).contains(target);
        assertThat(transactions.claim(target)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(AdminOperation.class)
    void 빈_작업_테이블에서도_서로_다른_식당의_작업을_동시에_등록한다(AdminOperation operation) throws Exception {
        Long firstId = operation == AdminOperation.CREATE ? null
                : restaurantPort.createByAdmin(createCommand()).restaurantId();
        Long secondId = operation == AdminOperation.CREATE ? null
                : restaurantPort.createByAdmin(createCommand()).restaurantId();
        jdbc.update("DELETE FROM restaurant_location_job");
        if (operation == AdminOperation.RETRY) {
            jdbc.update("UPDATE restaurant SET location_id=NULL WHERE id IN (?, ?)", firstId, secondId);
        }
        CyclicBarrier selected = new CyclicBarrier(2);
        var realRepository = mockingDetails(jobs).getMockCreationSettings().getDefaultAnswer();
        // 실제 MySQL 조회를 마친 두 요청을 INSERT 직전에 맞춘다. DB 동작은 대체하지 않는다.
        doAnswer(invocation -> {
            Object result = realRepository.answer(invocation);
            selected.await(10, TimeUnit.SECONDS);
            return result;
        }).when(jobs).findActiveForUpdate(anyLong());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> saveLocationJob(operation, firstId));
            var second = executor.submit(() -> saveLocationJob(operation, secondId));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo("PENDING");
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo("PENDING");
        }
        assertThat(jobs.findAll()).hasSize(2);
    }

    @Test
    void 서로_다른_식당의_동시_claim도_전역_slot_한개를_초과하지_않는다() throws Exception {
        Target first = target(restaurants.createByAdmin(createCommand()).restaurantId());
        Target second = target(restaurants.createByAdmin(createCommand()).restaurantId());
        jdbc.update("UPDATE restaurant_geocoding_budget SET max_concurrent=1 WHERE id=1");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { await(start); return transactions.claim(first); });
            var b = executor.submit(() -> { await(start); return transactions.claim(second); });
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))
                    .stream().filter(java.util.Optional::isPresent)).hasSize(1);
        }
        assertThat(used()).isEqualTo(1);
    }

    @Test
    void worker는_transaction_밖에서_호출하고_HTTP_대기_중_주소수정이_완료된다() throws Exception {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.answer.set(address -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            await(release);
            return new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate()));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> worker.process(target(id)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                var updated = executor.submit(() -> restaurants.updateByAdmin(id, addressCommand("東京都試験区架空町1-2-4")));
                assertThat(updated.get(10, TimeUnit.SECONDS).addressRevision()).isEqualTo(2);
            } finally {
                release.countDown();
            }
            running.get(10, TimeUnit.SECONDS);
        }
        assertThat(locations.get(id).locationStatus()).isEqualTo("PENDING");
        assertThat(locations.get(id).addressRevision()).isEqualTo(2);
        assertThat(jobs.findAll()).hasSize(2);
    }

    @Test
    void 두_worker가_경쟁해도_하나만_유효한_lease와_호출예산을_얻는다() throws Exception {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target target = target(id);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { await(start); return transactions.claim(target); });
            var second = executor.submit(() -> { await(start); return transactions.claim(target); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .stream().filter(java.util.Optional::isPresent)).hasSize(1);
        }
        assertThat(used()).isEqualTo(1);
    }

    @Test
    void crash_후_lease를_재획득하고_옛_성공과_실패와_중복_완료를_모두_무시한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target target = target(id);
        Claim old = transactions.claim(target).orElseThrow();
        expire(target.jobId());
        Claim current = transactions.claim(target).orElseThrow();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(transactions.complete(old, ready())).isFalse();
        assertThat(transactions.complete(old, Outcome.failure(FailureKind.ACCESS_DENIED))).isFalse();
        assertThat(transactions.complete(current, ready())).isTrue();
        assertThat(transactions.complete(current, ready())).isFalse();
        assertThat(locations.get(id).attempt()).isEqualTo(2);
        assertThat(used()).isEqualTo(2);
    }

    @Test
    void 삭제와_주소변경_후_늦은_실패는_현재_위치를_오염시키지_않는다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Claim old = transactions.claim(target(id)).orElseThrow();
        restaurants.updateByAdmin(id, addressCommand("東京都試験区架空町1-2-4"));
        assertThat(transactions.complete(old, Outcome.failure(FailureKind.INVALID_REQUEST))).isFalse();
        Claim current = transactions.claim(target(id)).orElseThrow();
        restaurants.deleteByAdmin(id);
        assertThat(transactions.complete(current, ready())).isFalse();
        assertThatThrownBy(() -> locations.get(id)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 삭제된_식당의_주소_편집은_허용하되_위치와_작업은_변경하지_않는다(boolean isReady) {
        Long id = restaurantPort.createByAdmin(createCommand()).restaurantId();
        Claim old = transactions.claim(target(id)).orElseThrow();
        if (isReady) {
            assertThat(transactions.complete(old, ready())).isTrue();
        }
        restaurantPort.deleteByAdmin(id);
        String snapshotSql = "SELECT l.* FROM restaurant_location l JOIN restaurant r ON r.location_id=l.id WHERE r.id=?";
        var locationBefore = jdbc.queryForMap(snapshotSql, id);
        long jobCount = jobs.count();
        var command = addressCommand("東京都試験区架空町1-2-4");
        var response = restaurantPort.updateByAdmin(id, command);
        assertThat(response.address()).isEqualTo(command.address());
        assertThat(jobs.count()).isEqualTo(jobCount);
        assertThat(jdbc.queryForMap(snapshotSql, id)).isEqualTo(locationBefore);
        assertThat(response.locationStatus()).isEqualTo(isReady ? "READY" : "PENDING");
        assertThat(restaurantRepository.findById(id).orElseThrow().isDeleted()).isTrue();
        assertThat(transactions.complete(old, ready())).isFalse();
        assertThatThrownBy(() -> locations.get(id)).isInstanceOf(BusinessException.class);
    }

    @Test
    void provider_예외는_예산과_재시도를_유지하고_진단에는_클래스명만_남긴다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target target = target(id);
        provider.answer.set(address -> {
            throw new IllegalStateException("synthetic-secret-key " + address + " SELECT private_column",
                    new IllegalArgumentException("synthetic-secret-cause"));
        });
        Logger logger = (Logger) LoggerFactory.getLogger(RestaurantLocationWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            worker.process(target);
            var status = locations.get(id);
            assertThat(status.locationStatus()).isEqualTo("RETRY_WAIT");
            assertThat(status.failureCode()).isEqualTo("TRANSIENT_ERROR");
            assertThat(status.attempt()).isEqualTo(1);
            assertThat(status.nextAttemptAt()).isNotNull();
            assertThat(used()).isEqualTo(1);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Location worker failure operation=location-provider-call exceptionType=java.lang.IllegalStateException");
                assertThat(event.getArgumentArray()).containsExactly("java.lang.IllegalStateException");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @EnumSource(value = FailureKind.class, names = {"TRANSIENT_ERROR", "CONNECTION_ERROR", "TIMEOUT", "CAPACITY_EXCEEDED", "CANCELLED"})
    void 자동_재시도는_같은_작업에서_누적되고_여덟번째에_중단한다(FailureKind failure) {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target target = target(id);
        java.util.UUID previousRequest = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            Claim claim = transactions.claim(target).orElseThrow();
            assertThat(claim.requestId()).isNotEqualTo(previousRequest);
            previousRequest = claim.requestId();
            transactions.complete(claim, Outcome.failure(failure));
            assertThat(locations.get(id).attempt()).isEqualTo(attempt);
            if (attempt < 8) {
                assertThat(locations.get(id).locationStatus()).isEqualTo("RETRY_WAIT");
                assertThat(rawTime("next_attempt_at", target.jobId()))
                        .isEqualTo(LocalDateTime.ofInstant(locations.get(id).nextAttemptAt(), ZoneOffset.UTC));
                jdbc.update("UPDATE restaurant_location_job SET reserved_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                        target.jobId());
                due(id, target.jobId());
            }
        }
        assertThat(locations.get(id).locationStatus()).isEqualTo("FAILED");
        assertThat(locations.get(id).failureCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(transactions.claim(target)).isEmpty();
        assertThat(used()).isEqualTo(8);
        assertThat(jobs.findAll()).hasSize(1);
    }

    @Test
    void 마지막_시도의_429도_다른_식당과_관리자_재처리에_공유_대기를_적용한다() {
        Long firstId = restaurants.createByAdmin(createCommand()).restaurantId();
        Long secondId = restaurants.createByAdmin(createCommand()).restaurantId();
        Target first = target(firstId);
        for (int attempt = 1; attempt < 8; attempt++) {
            transactions.complete(transactions.claim(first).orElseThrow(), Outcome.failure(FailureKind.TRANSIENT_ERROR));
            due(firstId, first.jobId());
        }
        transactions.complete(transactions.claim(first).orElseThrow(), Outcome.failure(FailureKind.QUOTA_EXCEEDED));
        assertThat(locations.get(firstId).locationStatus()).isEqualTo("FAILED");
        assertThat(locations.get(firstId).failureCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(transactions.claim(target(secondId))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), blocked_until) FROM restaurant_geocoding_budget WHERE id=1",
                Long.class)).isBetween(86390L, 95040L);
        locations.retry(firstId, 1);
        assertThat(transactions.claim(target(firstId))).isEmpty();
        assertThat(used()).isEqualTo(8);
    }

    @Test
    void 로컬_slot_부족과_취소는_주소오류가_아니며_disabled는_자동반복하지_않는다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Claim claim = transactions.claim(target(id)).orElseThrow();
        transactions.complete(claim, Outcome.failure(FailureKind.CAPACITY_EXCEEDED));
        assertThat(locations.get(id).failureCode()).isEqualTo("CAPACITY_EXCEEDED");
        assertThat(locations.get(id).locationStatus()).isEqualTo("RETRY_WAIT");
        locations.retry(id, 1);
        assertThat(transactions.complete(claim, ready())).isFalse();
        Claim cancelled = transactions.claim(target(id)).orElseThrow();
        transactions.complete(cancelled, Outcome.failure(FailureKind.CANCELLED));
        assertThat(locations.get(id).locationStatus()).isEqualTo("RETRY_WAIT");
        assertThat(rawTime("reserved_until", cancelled.jobId())).isEqualTo(cancelled.leaseUntil());
        locations.retry(id, 1);
        Claim disabled = transactions.claim(target(id)).orElseThrow();
        transactions.complete(disabled, Outcome.failure(FailureKind.DISABLED));
        assertThat(locations.get(id).locationStatus()).isEqualTo("FAILED");
        assertThat(locations.get(id).failureCode()).isEqualTo("DISABLED");
    }

    @Test
    void timeout_후_재처리도_이전_실행_slot이_만료되기_전에_호출하지_않는다() {
        jdbc.update("UPDATE restaurant_geocoding_budget SET max_concurrent=1 WHERE id=1");
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Claim old = transactions.claim(target(id)).orElseThrow();
        transactions.complete(old, Outcome.failure(FailureKind.TIMEOUT));
        due(id, old.jobId());
        assertThat(transactions.claim(new Target(id, old.jobId()))).isEmpty();
        locations.retry(id, 1);
        assertThat(transactions.claim(target(id))).isEmpty();
        jdbc.update("UPDATE restaurant_location_job SET reserved_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
                old.jobId());
        assertThat(transactions.claim(target(id))).isPresent();
        assertThat(used()).isEqualTo(2);
    }

    @Test
    void 일일한도와_동시슬롯과_중단과_공유quota_대기를_재처리로_우회하지_못한다() {
        Long firstId = restaurants.createByAdmin(createCommand()).restaurantId();
        Long secondId = restaurants.createByAdmin(createCommand()).restaurantId();
        jdbc.update("UPDATE restaurant_geocoding_budget SET max_concurrent=1, daily_limit=1 WHERE id=1");
        Claim first = transactions.claim(target(firstId)).orElseThrow();
        assertThat(transactions.claim(target(secondId))).isEmpty();
        transactions.complete(first, Outcome.failure(FailureKind.QUOTA_EXCEEDED));
        locations.retry(firstId, 1);
        assertThat(transactions.claim(target(firstId))).isEmpty();
        assertThat(transactions.claim(target(secondId))).isEmpty();
        jdbc.update("UPDATE restaurant_geocoding_budget SET blocked_until=NULL WHERE id=1");
        assertThat(transactions.claim(target(secondId))).isEmpty();
        jdbc.update("UPDATE restaurant_geocoding_budget SET budget_day=UTC_DATE()-INTERVAL 1 DAY WHERE id=1");
        assertThat(transactions.claim(target(secondId))).isPresent();
        assertThat(used()).isEqualTo(1);
        jdbc.update("UPDATE restaurant_geocoding_budget SET enabled=false WHERE id=1");
        assertThat(transactions.claim(target(firstId))).isEmpty();
    }

    @Test
    void 숫자_premise_주소는_worker가_한번_확인하고_좌표와_READY를_저장한다() {
        provider.answer.set(address -> new GeocodingResult.Candidates(
                List.of(LocationAdoptionPolicyTest.numericPremiseCandidate("３"))));
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        assertThat(locations.get(id).locationStatus()).isEqualTo("PENDING");
        worker.process(target(id));
        assertThat(locations.get(id).locationStatus()).isEqualTo("READY");
        assertThat(locations.get(id).attempt()).isEqualTo(1);
        assertThat(locations.get(id).failureCode()).isNull();
        assertThat(readLocation(id).getCoordinates().getLatitude()).isEqualByComparingTo("10.123457");
        assertThat(readLocation(id).getCoordinates().getLongitude()).isEqualByComparingTo("20.765432");
        assertThat(used()).isEqualTo(1);
    }

    @Test
    void 관리자_재처리는_pending에서_멱등이고_revision과_ready_충돌을_거부한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target original = target(id);
        assertThat(locations.retry(id, 1).locationStatus()).isEqualTo("PENDING");
        assertThat(target(id)).isEqualTo(original);
        assertThatThrownBy(() -> locations.retry(id, 0)).isInstanceOf(BusinessException.class);
        worker.process(original);
        assertThat(locations.get(id).locationStatus()).isEqualTo("READY");
        assertThatThrownBy(() -> locations.retry(id, 1)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 작업_INSERT_실패는_신규식당과_주소변경과_관리자_재처리를_함께_rollback한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        String originalAddress = restaurantRepository.findById(id).orElseThrow().getAddress();
        Claim claim = transactions.claim(target(id)).orElseThrow();
        transactions.complete(claim, new Outcome(null, null, "NO_RESULTS"));
        long count = restaurantRepository.count();
        jdbc.execute("""
                CREATE TRIGGER reject_job BEFORE INSERT ON restaurant_location_job FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic job failure'
                """);
        try {
            assertThatThrownBy(() -> restaurants.createByAdmin(createCommand())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> restaurants.updateByAdmin(id, addressCommand("東京都試験区架空町1-2-4")))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> locations.retry(id, 1)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_job");
        }
        assertThat(restaurantRepository.count()).isEqualTo(count);
        assertThat(locations.get(id).locationStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(locations.get(id).addressRevision()).isEqualTo(1);
        assertThat(restaurantRepository.findById(id).orElseThrow().getAddress())
                .isEqualTo(originalAddress);
    }

    @Test
    void 완료_DB_실패는_lease를_보존하고_만료후_새_worker가_복구한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target target = target(id);
        Claim claim = transactions.claim(target).orElseThrow();
        jdbc.execute("""
                CREATE TRIGGER reject_location BEFORE UPDATE ON restaurant_location FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic completion failure'
                """);
        try {
            assertThatThrownBy(() -> transactions.complete(claim, ready())).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_location");
        }
        assertThat(locations.get(id).locationStatus()).isEqualTo("PENDING");
        expire(target.jobId());
        worker.process(target);
        assertThat(locations.get(id).locationStatus()).isEqualTo("READY");
        assertThat(locations.get(id).attempt()).isEqualTo(2);
    }

    @Test
    void 기존_transaction_호출과_lease_만료_직후의_완료를_거절한다() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> worker.runOnce()))
                .isInstanceOf(IllegalStateException.class);
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Claim claim = transactions.claim(target(id)).orElseThrow();
        expire(claim.jobId());
        assertThat(transactions.complete(claim, ready())).isFalse();
    }

    private String saveLocationJob(AdminOperation operation, Long id) {
        return switch (operation) {
            case CREATE -> restaurantPort.createByAdmin(createCommand()).locationStatus();
            case ADDRESS_UPDATE -> restaurantPort.updateByAdmin(id, addressCommand("東京都試験区架空町1-2-4"))
                    .locationStatus();
            case RETRY -> restaurantPort.retryLocationByAdmin(id, 0).locationStatus();
        };
    }

    private Target target(Long id) {
        return jobs.findAll().stream().filter(job -> job.getRestaurantId().equals(id))
                .max(java.util.Comparator.comparing(org.sopt.hashi.restaurant.domain.RestaurantLocationJob::getId))
                .map(job -> new Target(id, job.getId())).orElseThrow();
    }

    private Outcome ready() {
        return new Outcome(new LocationAdoptionPolicy(LocationAdoptionPolicyTest.properties())
                .evaluate(LocationAdoptionPolicyTest.ADDRESS,
                        new GeocodingResult.Candidates(List.of(LocationAdoptionPolicyTest.candidate()))).coordinates(), null, null);
    }

    private int used() {
        return jdbc.queryForObject("SELECT reserved_calls FROM restaurant_geocoding_budget WHERE id=1", Integer.class);
    }

    private LocalDateTime rawTime(String column, Long jobId) {
        return LocalDateTime.parse(jdbc.queryForObject("SELECT DATE_FORMAT(" + column
                + ", '%Y-%m-%dT%H:%i:%s.%f') FROM restaurant_location_job WHERE id=?", String.class, jobId));
    }

    private static String format(LocalDateTime time) {
        return time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"));
    }

    private void expire(Long jobId) {
        jdbc.update("""
                UPDATE restaurant_location_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,
                reserved_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?
                """, jobId);
    }

    private void due(Long restaurantId, Long jobId) {
        jdbc.update("UPDATE restaurant_location_job SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", jobId);
        jdbc.update("""
                UPDATE restaurant_location l JOIN restaurant r ON r.location_id=l.id
                SET l.next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE r.id=?
                """, restaurantId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("synthetic latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private AdminRestaurantCommand createCommand() {
        int sequence = RESTAURANT_SEQUENCE.incrementAndGet();
        return new AdminRestaurantCommand("합성 식당 " + sequence, "試験", "요약", "설명",
                LocationAdoptionPolicyTest.ADDRESS.replace("試験区", " ".repeat(sequence) + "試験区"),
                "표시 지역", "sushi", "초밥", "restaurant", "JPY", BigDecimal.ONE, BigDecimal.TEN,
                List.of("restaurants/synthetic.jpg"), null, null, null, List.of("합성"), List.of(),
                Arrays.stream(DayOfWeek.values()).map(day -> new BusinessHourCommand(day, null, null, null, null, true)).toList());
    }

    private AdminRestaurantCommand createCommandWithGeocodingAddress() {
        int sequence = RESTAURANT_SEQUENCE.incrementAndGet();
        String displayAddress = LocationAdoptionPolicyTest.ADDRESS.replace(
                "試験区", " ".repeat(sequence) + "試験区") + " 架空ビル" + sequence + " 1F";
        return new AdminRestaurantCommand("합성 식당 " + sequence, "試験", "요약", "설명",
                displayAddress, LocationAdoptionPolicyTest.ADDRESS, "표시 지역", "sushi", "초밥",
                "restaurant", "JPY", BigDecimal.ONE, BigDecimal.TEN,
                List.of("restaurants/synthetic.jpg"), null, null, null, List.of("합성"), List.of(),
                Arrays.stream(DayOfWeek.values())
                        .map(day -> new BusinessHourCommand(day, null, null, null, null, true)).toList());
    }

    private AdminRestaurantCommand geocodingAddressCommand(String geocodingAddress) {
        return new AdminRestaurantCommand(null, null, null, null, null, geocodingAddress,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private AdminRestaurantCommand addressCommand(String address) {
        String uniqueAddress = address.replace("試験区", " ".repeat(RESTAURANT_SEQUENCE.incrementAndGet()) + "試験区");
        return new AdminRestaurantCommand(null, null, null, null, uniqueAddress, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    enum AdminOperation { CREATE, ADDRESS_UPDATE, RETRY }

    static class FakeProvider implements GeocodingProvider {
        final AtomicReference<Function<String, GeocodingResult>> answer = new AtomicReference<>();

        @Override
        public GeocodingResult geocode(String address) {
            return answer.get().apply(address);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean LocationJobProperties jobProperties() { return LocationAdoptionPolicyTest.properties(); }
        @Bean FakeProvider fakeProvider() { return new FakeProvider(); }
    }
}
