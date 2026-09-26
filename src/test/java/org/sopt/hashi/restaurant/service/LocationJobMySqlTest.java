package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    @Test
    void 삭제된_식당의_주소_편집은_허용하되_새_위치_작업은_만들지_않는다() {
        Long id = restaurantPort.createByAdmin(createCommand()).restaurantId();
        Claim old = transactions.claim(target(id)).orElseThrow();
        restaurantPort.deleteByAdmin(id);
        long jobCount = jobs.count();
        var response = restaurantPort.updateByAdmin(id, addressCommand("東京都試験区架空町1-2-4"));
        assertThat(response.address()).isEqualTo("東京都試験区架空町1-2-4");
        assertThat(jobs.count()).isEqualTo(jobCount);
        assertThat(restaurantRepository.findById(id).orElseThrow().isDeleted()).isTrue();
        assertThat(transactions.complete(old, ready())).isFalse();
    }

    @Test
    void 자동_재시도에서_requestId는_변하지만_attempt는_누적되고_네번째에_중단한다() {
        Long id = restaurants.createByAdmin(createCommand()).restaurantId();
        Target target = target(id);
        java.util.UUID previousRequest = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            Claim claim = transactions.claim(target).orElseThrow();
            assertThat(claim.requestId()).isNotEqualTo(previousRequest);
            previousRequest = claim.requestId();
            transactions.complete(claim, Outcome.failure(FailureKind.TRANSIENT_ERROR));
            assertThat(locations.get(id).attempt()).isEqualTo(attempt);
            if (attempt < 4) {
                assertThat(locations.get(id).locationStatus()).isEqualTo("RETRY_WAIT");
                assertThat(rawTime("next_attempt_at", target.jobId()))
                        .isEqualTo(LocalDateTime.ofInstant(locations.get(id).nextAttemptAt(), ZoneOffset.UTC));
                due(id, target.jobId());
            }
        }
        assertThat(locations.get(id).locationStatus()).isEqualTo("FAILED");
        assertThat(locations.get(id).failureCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(transactions.claim(target)).isEmpty();
        assertThat(used()).isEqualTo(4);
    }

    @Test
    void 마지막_시도의_429도_다른_식당과_관리자_재처리에_공유_대기를_적용한다() {
        Long firstId = restaurants.createByAdmin(createCommand()).restaurantId();
        Long secondId = restaurants.createByAdmin(createCommand()).restaurantId();
        Target first = target(firstId);
        for (int attempt = 1; attempt < 4; attempt++) {
            transactions.complete(transactions.claim(first).orElseThrow(), Outcome.failure(FailureKind.TRANSIENT_ERROR));
            due(firstId, first.jobId());
        }
        transactions.complete(transactions.claim(first).orElseThrow(), Outcome.failure(FailureKind.QUOTA_EXCEEDED));
        assertThat(locations.get(firstId).locationStatus()).isEqualTo("FAILED");
        assertThat(locations.get(firstId).failureCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(transactions.claim(target(secondId))).isEmpty();
        locations.retry(firstId, 1);
        assertThat(transactions.claim(target(firstId))).isEmpty();
        assertThat(used()).isEqualTo(4);
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
        assertThat(restaurantRepository.findById(id).orElseThrow().getAddress()).isEqualTo(LocationAdoptionPolicyTest.ADDRESS);
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
        return new AdminRestaurantCommand("합성 식당", "試験", "요약", "설명", LocationAdoptionPolicyTest.ADDRESS,
                "표시 지역", "sushi", "초밥", "restaurant", "JPY", BigDecimal.ONE, BigDecimal.TEN,
                List.of("restaurants/synthetic.jpg"), null, null, null, List.of("합성"), List.of(),
                Arrays.stream(DayOfWeek.values()).map(day -> new BusinessHourCommand(day, null, null, null, null, true)).toList());
    }

    private AdminRestaurantCommand addressCommand(String address) {
        return new AdminRestaurantCommand(null, null, null, null, address, null, null, null, null, null,
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
