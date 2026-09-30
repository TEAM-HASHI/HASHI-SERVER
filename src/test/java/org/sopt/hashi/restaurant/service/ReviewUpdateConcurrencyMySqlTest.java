package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.UpdateReviewRequest;
import org.sopt.hashi.review.service.MyReviewService;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MyReviewService.class, RestaurantPortImpl.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewUpdateConcurrencyMySqlTest {

    private static final Long USER_ID = 7L;

    @Container
    @ServiceConnection
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hashi")
            .withUsername("hashi")
            .withPassword("hashi");

    @Autowired
    private MyReviewService myReviewService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private ReservationPort reservationPort;

    @MockitoBean
    private UserPort userPort;

    @MockitoBean
    private FileStorage fileStorage;

    @MockitoBean
    private MediaPort mediaPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private RestaurantService restaurantService;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM review_image");
        jdbcTemplate.update("DELETE FROM review_keyword");
        jdbcTemplate.update("DELETE FROM review");
        jdbcTemplate.update("DELETE FROM restaurant");
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void 같은_리뷰의_동시_PATCH는_후행_요청이_최신_별점으로_통계를_갱신한다() throws Exception {
        Long restaurantId = saveRestaurantWithStatistics("동시 수정 식당", 5, 1);
        Long reviewId = saveReview(100L, restaurantId, 5, "첫 수정 전 리뷰 내용입니다.");
        CountDownLatch firstUpdateCompleted = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            myReviewService.updateMyReview(
                    reviewId,
                    updateRequest(3, "첫 번째 요청이 반영한 리뷰 내용입니다."));
            firstUpdateCompleted.countDown();
            await(allowFirstCommit, "첫 수정 transaction commit");
        }));
        assertThat(firstUpdateCompleted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> second = executor.submit(() -> myReviewService.updateMyReview(
                reviewId,
                updateRequest(4, "두 번째 요청이 반영한 리뷰 내용입니다.")));
        try {
            awaitMySqlRowLockCompetition("review");
        } finally {
            allowFirstCommit.countDown();
        }

        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        assertReview(reviewId, 4, false, "두 번째 요청이 반영한 리뷰 내용입니다.");
        assertRestaurantStatistics(restaurantId, 4, 1, "4.0");
    }

    @Test
    void 같은_리뷰의_PATCH와_DELETE는_삭제가_최신_별점을_차감한다() throws Exception {
        Long restaurantId = saveRestaurantWithStatistics("수정 삭제 경쟁 식당", 5, 1);
        Long reviewId = saveReview(101L, restaurantId, 5, "삭제와 경쟁하기 전 리뷰 내용입니다.");
        CountDownLatch updateCompleted = new CountDownLatch(1);
        CountDownLatch allowUpdateCommit = new CountDownLatch(1);

        Future<?> update = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            myReviewService.updateMyReview(
                    reviewId,
                    updateRequest(3, "삭제보다 먼저 반영되는 수정 리뷰 내용입니다."));
            updateCompleted.countDown();
            await(allowUpdateCommit, "수정 transaction commit");
        }));
        assertThat(updateCompleted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> delete = executor.submit(() -> myReviewService.deleteMyReview(reviewId));
        try {
            awaitMySqlRowLockCompetition("review");
        } finally {
            allowUpdateCommit.countDown();
        }

        update.get(20, TimeUnit.SECONDS);
        delete.get(20, TimeUnit.SECONDS);

        assertReview(reviewId, 3, true, "삭제보다 먼저 반영되는 수정 리뷰 내용입니다.");
        assertRestaurantStatistics(restaurantId, 0, 0, "0.0");
    }

    @Test
    void 서로_다른_리뷰의_동시_PATCH도_식당_평점_합계를_원자적으로_갱신한다() throws Exception {
        Long restaurantId = saveRestaurantWithStatistics("평점 합계 경쟁 식당", 10, 2);
        Long firstReviewId = saveReview(102L, restaurantId, 5, "첫 리뷰의 수정 전 내용입니다.");
        Long secondReviewId = saveReview(103L, restaurantId, 5, "두 번째 리뷰의 수정 전 내용입니다.");
        CountDownLatch firstUpdateCompleted = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            myReviewService.updateMyReview(
                    firstReviewId,
                    updateRequest(3, "첫 리뷰에 반영되는 수정 내용입니다."));
            firstUpdateCompleted.countDown();
            await(allowFirstCommit, "첫 수정 transaction commit");
        }));
        assertThat(firstUpdateCompleted.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> second = executor.submit(() -> myReviewService.updateMyReview(
                secondReviewId,
                updateRequest(4, "두 번째 리뷰에 반영되는 수정 내용입니다.")));
        try {
            awaitMySqlRowLockCompetition("restaurant");
        } finally {
            allowFirstCommit.countDown();
        }

        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        assertReview(firstReviewId, 3, false, "첫 리뷰에 반영되는 수정 내용입니다.");
        assertReview(secondReviewId, 4, false, "두 번째 리뷰에 반영되는 수정 내용입니다.");
        assertRestaurantStatistics(restaurantId, 7, 2, "3.5");
    }

    private Long saveRestaurantWithStatistics(String name, long ratingSum, long reviewCount) {
        Restaurant restaurant = restaurantRepository.saveAndFlush(Restaurant.create(
                name,
                name,
                "식당 소개",
                "식당 상세 설명",
                "도쿄도 신주쿠구",
                "도쿄",
                RestaurantGenre.SUSHI,
                "초밥",
                RestaurantPlaceType.RESTAURANT,
                PriceCurrency.JPY,
                BigDecimal.valueOf(1_000),
                BigDecimal.valueOf(3_000)
        ));
        jdbcTemplate.update("""
                UPDATE restaurant
                SET rating_sum = ?, review_count = ?, rating = ROUND(? * 1.0 / ?, 1)
                WHERE id = ?
                """, ratingSum, reviewCount, ratingSum, reviewCount, restaurant.getId());
        return restaurant.getId();
    }

    private Long saveReview(Long reservationId, Long restaurantId, int rating, String content) {
        Review review = Review.create(reservationId, restaurantId, USER_ID, rating, content);
        review.replaceKeywords(List.of("FOOD_IS_DELICIOUS"));
        return reviewRepository.saveAndFlush(review).getId();
    }

    private UpdateReviewRequest updateRequest(int rating, String content) {
        return new UpdateReviewRequest(
                rating,
                List.of("GOOD_VALUE"),
                content,
                List.of()
        );
    }

    private void assertReview(Long reviewId, int rating, boolean deleted, String content) {
        transactionTemplate.executeWithoutResult(status -> {
            Review review = reviewRepository.findById(reviewId).orElseThrow();
            assertThat(review.getRating()).isEqualTo(rating);
            assertThat(review.isDeleted()).isEqualTo(deleted);
            assertThat(review.getContent()).isEqualTo(content);
            assertThat(review.getKeywords()).containsExactly("GOOD_VALUE");
        });
    }

    private void assertRestaurantStatistics(
            Long restaurantId,
            long ratingSum,
            long reviewCount,
            String rating
    ) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId).orElseThrow();
        assertThat(restaurant.getRatingSum()).isEqualTo(ratingSum);
        assertThat(restaurant.getReviewCount()).isEqualTo(reviewCount);
        assertThat(restaurant.getRating()).isEqualByComparingTo(rating);
    }

    private void awaitMySqlRowLockCompetition(String tableName)
            throws SQLException, InterruptedException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM performance_schema.data_lock_waits waits
                        JOIN performance_schema.data_locks requested
                          ON requested.engine = waits.engine
                         AND requested.engine_lock_id = waits.requesting_engine_lock_id
                        WHERE requested.object_schema = DATABASE()
                          AND requested.object_name = '%s'
                        """.formatted(tableName))) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("MySQL %s row lock 경쟁이 제한 시간 안에 관찰되지 않았습니다"
                .formatted(tableName));
    }

    private void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(description + " 대기 시간이 초과되었습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " 대기가 중단되었습니다", exception);
        }
    }
}
