package org.sopt.hashi.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.config.JpaAuditingConfig;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.service.MyReviewService;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserPort;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({MyReviewService.class, PointService.class, PointPortImpl.class, JpaAuditingConfig.class,
        ReviewRewardReclaimTest.Adapters.class})
class ReviewRewardReclaimTest {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired MyReviewService reviews;
    @Autowired ReviewRepository repository;
    @Autowired PointService points;
    @Autowired UserRepository users;
    @Autowired UserPort userPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @MockitoBean CurrentUserProvider current;
    @MockitoBean MediaPort media;
    @MockitoBean FileStorage storage;
    @MockitoBean RestaurantPort restaurants;
    @MockitoBean ReservationPort reservations;
    private Long member;
    private Long review;

    @BeforeEach
    void setUp() {
        for (String table : new String[]{"review", "point_transaction", "point_account", "users"}) {
            jdbc.update("DELETE FROM " + table);
        }
        member = users.saveAndFlush(User.onboard("리뷰회원", "회원", LocalDate.of(1990, 1, 1),
                "01000000000", "member@example.invalid", null)).getId();
        given(current.currentUserId()).willReturn(member);
        review = repository.saveAndFlush(Review.create(101L, 1L, member, 5, "방문 리뷰")).getId();
    }

    @Test
    void 부족액은_복원금에는_적용하지_않고_다음_적립부터_나눠_상계한다() {
        earn(101L, 500);
        points.use(member, 400, "예약", PointSourceType.RESERVATION, 20L);
        reviews.deleteMyReview(review);
        assertAccount(0, 400);
        assertLedger(101L, "EARN:500", "RECLAIM:100", "DEFER_RECLAIM:400");
        points.restore(member, PointSourceType.RESERVATION, 20L);
        assertAccount(400, 400);
        earn(102L, 150);
        assertAccount(400, 250);
        assertLedger(102L, "EARN:150", "OFFSET_RECLAIM:150");
        earn(103L, 500);
        assertAccount(650, 0);
        assertLedger(103L, "EARN:500", "OFFSET_RECLAIM:250");
    }

    @Test
    void 잔액이_충분하면_실제_지급액만_즉시_회수한다() {
        earn(101L, 350);
        earn(102L, 200);
        reviews.deleteMyReview(review);
        assertAccount(200, 0);
        assertLedger(101L, "EARN:350", "RECLAIM:350");
    }

    @Test
    void 적립_이력이_없으면_회수하지_않는다() {
        reviews.deleteMyReview(review);
        assertLedger(101L);
        assertThat(points.getBalance(member)).isZero();
    }

    @Test
    void 잔액이_없으면_기존_적립액_전부를_이월하고_롤백시_삭제와_원장도_복구한다() {
        earn(101L, 350);
        points.use(member, 350, "예약", PointSourceType.RESERVATION, 20L);
        transaction.executeWithoutResult(status -> {
            reviews.deleteMyReview(review);
            status.setRollbackOnly();
        });
        assertThat(repository.findById(review).orElseThrow().isDeleted()).isFalse();
        assertAccount(0, 0);
        assertLedger(101L, "EARN:350");
        reviews.deleteMyReview(review);
        assertAccount(0, 350);
        assertLedger(101L, "EARN:350", "DEFER_RECLAIM:350");
    }

    @Test
    void 동시_삭제와_적립은_삭제한_보상을_한번만_회수한다() throws Exception {
        earn(101L, 500);
        points.use(member, 400, "예약", PointSourceType.RESERVATION, 20L);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var deletion = pool.submit(() -> deleteAfter(start));
            var duplicate = pool.submit(() -> deleteAfter(start));
            var earning = pool.submit(() -> {
                start.await();
                transaction.executeWithoutResult(status -> {
                    userPort.lockActiveUser(member);
                    earn(102L, 500);
                });
                return null;
            });
            start.countDown();
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isNotEqualTo(duplicate.get(20, TimeUnit.SECONDS));
            earning.get(20, TimeUnit.SECONDS);
        }
        assertAccount(100, 0);
        assertThat(repository.findById(review).orElseThrow().isDeleted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM point_transaction WHERE type IN ('RECLAIM','OFFSET_RECLAIM')",
                Long.class)).isEqualTo(500L);
    }

    private boolean deleteAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            reviews.deleteMyReview(review);
            return true;
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(ReviewErrorCode.NOT_FOUND);
            return false;
        }
    }

    private void earn(Long reservationId, long amount) {
        points.earn(member, amount, "리뷰", PointSourceType.REVIEW, reservationId);
    }

    private void assertAccount(long balance, long pending) {
        assertThat(points.getBalance(member)).isEqualTo(balance);
        assertThat(jdbc.queryForObject("SELECT pending_reclaim FROM point_account WHERE user_id=?", Long.class, member))
                .isEqualTo(pending);
    }

    private void assertLedger(Long reservationId, String... entries) {
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(type, ':', amount) FROM point_transaction
                WHERE source_type='REVIEW' AND source_id=? ORDER BY id
                """, String.class, reservationId)).containsExactly(entries);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "org.sopt.hashi.user.service", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.UserPortImpl"))
    static class Adapters {}
}