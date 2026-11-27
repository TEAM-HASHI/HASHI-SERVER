package org.sopt.hashi.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.point.domain.PointTransactionRepository;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.reservation.service.ReservationService;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.CreateReviewRequest;
import org.sopt.hashi.review.service.ReviewWriteService;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.domain.User;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReviewWriteService.class, PointService.class, PointPortImpl.class,
        JpaAuditingConfig.class, ReviewRewardConcurrencyTest.PortAdapters.class})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewRewardConcurrencyTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("review_reward_test")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired ReservationRepository reservations;
    @Autowired ReviewWriteService reviewService;
    @Autowired ReviewRepository reviews;
    @Autowired RestaurantRepository restaurantRepository;
    @Autowired UserRepository users;
    @Autowired PointTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoSpyBean PointService points;
    @MockitoBean ReservationService reservationService;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean MediaPort media;
    @MockitoBean FileStorage storage;

    private ExecutorService executor;
    private Long userId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM review_image");
        jdbc.update("DELETE FROM review_keyword");
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM point_transaction");
        jdbc.update("DELETE FROM point_account");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM users");
        userId = users.saveAndFlush(User.onboard(
                "리뷰회원", "AUDIT USER", LocalDate.of(1998, 1, 1),
                "01011112222", "audit@example.invalid", null)).getId();
        given(currentUser.currentUserId()).willReturn(userId);
        given(restaurants.existsById(anyLong())).willReturn(true);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void 동시_리뷰는_예약별로_한번만_적립한다(boolean existingAccount, boolean duplicate) throws Exception {
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
        Long firstReservation = visitedReservation(9001L);
        Long secondReservation = duplicate ? firstReservation : visitedReservation(9002L);
        if (existingAccount) {
            points.earn(userId, 300L, "기존 잔액", PointSourceType.REVIEW, 99999L);
        }
        CountDownLatch firstReviewWritten = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            reviewService.create(reviewRequest(firstReservation));
            firstReviewWritten.countDown();
            await(allowFirstCommit, "first review commit");
        }));
        Future<?> second;
        try {
            assertThat(firstReviewWritten.await(10, TimeUnit.SECONDS)).isTrue();
            second = executor.submit(() -> reviewService.create(reviewRequest(secondReservation)));
            awaitUserRowLockWait();
        } finally {
            allowFirstCommit.countDown();
        }
        first.get(20, TimeUnit.SECONDS);
        Throwable secondFailure = failureOf(second);

        if (duplicate) {
            assertThat(secondFailure).isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ReviewErrorCode.ALREADY_REVIEWED);
        } else {
            assertThat(secondFailure).isNull();
        }
        int expectedReviews = duplicate ? 1 : 2;
        assertThat(reviews.count()).isEqualTo(expectedReviews);
        assertThat(transactions.count()).isEqualTo(expectedReviews + (existingAccount ? 1 : 0));
        assertThat(points.getBalance(userId)).isEqualTo(expectedReviews * 500L + (existingAccount ? 300 : 0));
    }

    @Test
    void 보상_실패시_리뷰와_식당_통계가_함께_롤백된다() {
        Restaurant restaurant = restaurantRepository.saveAndFlush(Restaurant.create("식당", "식당", "소개", "설명",
                "주소", "Tokyo", RestaurantGenre.NOODLE, "메뉴", RestaurantPlaceType.RESTAURANT,
                PriceCurrency.JPY, BigDecimal.ONE, BigDecimal.TEN));
        Long id = restaurant.getId();
        Long reservation = visitedReservation(id);
        doAnswer(call -> {
            assertThat(restaurantRepository.increaseReviewStatistics(id, 5)).isOne();
            return null;
        }).when(restaurants).increaseReviewStatistics(id, 5);
        doThrow(new IllegalStateException("적립 실패")).when(points).earnReviewReward(userId, reservation);

        assertThatThrownBy(() -> reviewService.create(reviewRequest(reservation)))
                .isInstanceOf(IllegalStateException.class).hasMessage("적립 실패");
        assertThat(reviews.count()).isZero();
        assertThat(transactions.count()).isZero();
        assertThat(restaurantRepository.findById(id).orElseThrow().getReviewCount()).isZero();
    }

    private Long visitedReservation(Long restaurantId) {
        Reservation reservation = Reservation.standard(userId, "테스트 예약자", restaurantId,
                LocalDateTime.of(2026, 1, 1, 12, 0), 1, 0, 0, null, 0L, 4000L);
        reservation.changeStatusByAdmin(ReservationStatus.VISITED);
        return reservations.saveAndFlush(reservation).getId();
    }

    private CreateReviewRequest reviewRequest(Long reservationId) {
        return new CreateReviewRequest(reservationId, 5, List.of("FOOD_IS_DELICIOUS"),
                "포인트 계정 생성 경쟁을 확인하는 합성 리뷰입니다.", List.of(), null);
    }

    private Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private void awaitUserRowLockWait() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM performance_schema.data_lock_waits waits
                        JOIN performance_schema.data_locks requested
                          ON requested.engine = waits.engine
                         AND requested.engine_lock_id = waits.requesting_engine_lock_id
                        WHERE requested.object_schema = DATABASE()
                          AND requested.object_name = 'users'
                        """)) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError("the second review did not wait on the real users row lock");
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError(description + " timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description + " interrupted", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {"org.sopt.hashi.user.service", "org.sopt.hashi.reservation.service"},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "org\\.sopt\\.hashi\\.(user|reservation)\\.service\\.(UserPortImpl|ReservationPortImpl)"))
    static class PortAdapters {
        // Load the existing package-private adapters without replacing their transaction behavior.
    }
}
