package org.sopt.hashi.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:review-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
class ReviewRepositoryTest {

    private static final Long RESTAURANT_ID = 1L;
    private long nextReservationId = 1L;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 최신순_첫_페이지는_생성일과_ID_내림차순으로_조회한다() {
        Review first = saveReview(5, LocalDateTime.of(2026, 7, 1, 12, 0));
        Review second = saveReview(4, LocalDateTime.of(2026, 7, 2, 12, 0));
        Review third = saveReview(3, LocalDateTime.of(2026, 7, 2, 12, 0));

        List<Review> result = reviewRepository.findLatestPage(
                RESTAURANT_ID,
                null,
                null,
                PageRequest.of(0, 3)
        );

        assertThat(result)
                .extracting(Review::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    void 최신순_커서는_이전_페이지_마지막_리뷰_이후부터_조회한다() {
        Review first = saveReview(5, LocalDateTime.of(2026, 7, 1, 12, 0));
        Review second = saveReview(4, LocalDateTime.of(2026, 7, 2, 12, 0));
        Review cursor = saveReview(3, LocalDateTime.of(2026, 7, 2, 12, 0));

        List<Review> result = reviewRepository.findLatestPage(
                RESTAURANT_ID,
                cursor.getCreatedAt(),
                cursor.getId(),
                PageRequest.of(0, 3)
        );

        assertThat(result)
                .extracting(Review::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void 높은_평점순_커서는_평점과_ID_기준으로_다음_리뷰를_조회한다() {
        Review lowest = saveReview(3, LocalDateTime.of(2026, 7, 1, 12, 0));
        Review lowerSameRating = saveReview(4, LocalDateTime.of(2026, 7, 1, 13, 0));
        Review cursor = saveReview(4, LocalDateTime.of(2026, 7, 1, 14, 0));
        saveReview(5, LocalDateTime.of(2026, 7, 1, 15, 0));

        List<Review> result = reviewRepository.findRatingHighPage(
                RESTAURANT_ID,
                cursor.getRating(),
                cursor.getId(),
                PageRequest.of(0, 3)
        );

        assertThat(result)
                .extracting(Review::getId)
                .containsExactly(lowerSameRating.getId(), lowest.getId());
    }

    @Test
    void 낮은_평점순_커서는_평점과_ID_기준으로_다음_리뷰를_조회한다() {
        saveReview(1, LocalDateTime.of(2026, 7, 1, 12, 0));
        Review lowerSameRating = saveReview(2, LocalDateTime.of(2026, 7, 1, 13, 0));
        Review cursor = saveReview(2, LocalDateTime.of(2026, 7, 1, 14, 0));
        Review highest = saveReview(4, LocalDateTime.of(2026, 7, 1, 15, 0));

        List<Review> result = reviewRepository.findRatingLowPage(
                RESTAURANT_ID,
                cursor.getRating(),
                cursor.getId(),
                PageRequest.of(0, 3)
        );

        assertThat(result)
                .extracting(Review::getId)
                .containsExactly(lowerSameRating.getId(), highest.getId());
    }

    @Test
    void 수정과_삭제용_잠금_조회는_소유한_활성_리뷰만_반환한다() {
        Review owned = saveReview(5, LocalDateTime.of(2026, 7, 1, 12, 0), 1L);
        Review deleted = saveReview(4, LocalDateTime.of(2026, 7, 1, 13, 0), 1L);
        deleted.softDelete();
        reviewRepository.flush();
        entityManager.clear();

        assertThat(reviewRepository.findOwnedActiveForUpdate(owned.getId(), 1L))
                .isPresent();
        assertThat(reviewRepository.findOwnedActiveForUpdate(owned.getId(), 2L))
                .isEmpty();
        assertThat(reviewRepository.findOwnedActiveForUpdate(deleted.getId(), 1L))
                .isEmpty();
    }

    private Review saveReview(int rating, LocalDateTime createdAt) {
        return saveReview(rating, createdAt, 1L);
    }

    private Review saveReview(int rating, LocalDateTime createdAt, Long userId) {
        Review review = Review.create(
                nextReservationId++, RESTAURANT_ID, userId, rating, "리뷰 내용입니다.");
        entityManager.persistAndFlush(review);
        jdbcTemplate.update(
                "update review set created_at = ?, updated_at = ? where id = ?",
                Timestamp.valueOf(createdAt),
                Timestamp.valueOf(createdAt),
                review.getId()
        );
        entityManager.clear();
        return reviewRepository.findById(review.getId()).orElseThrow();
    }
}
