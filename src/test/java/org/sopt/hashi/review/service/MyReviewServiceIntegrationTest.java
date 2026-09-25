package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.UpdateReviewRequest;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import(MyReviewService.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:my-review-service-integration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MyReviewServiceIntegrationTest {

    @Autowired
    private MyReviewService myReviewService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private ReservationPort reservationPort;

    @MockitoBean
    private RestaurantPort restaurantPort;

    @MockitoBean
    private MediaPort mediaPort;

    @MockitoBean
    private UserPort userPort;

    @MockitoBean
    private FileStorage fileStorage;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    void 운영_이미지_순서_제약에서도_기존_이미지를_삭제한_뒤_같은_순서로_교체한다() {
        jdbcTemplate.execute("""
                alter table review_image
                add constraint uk_test_review_image_display_order unique (review_id, display_order)
                """);
        Review review = Review.create(100L, 10L, 7L, 5, "기존 리뷰 내용입니다.");
        review.replaceKeywords(List.of("FOOD_IS_DELICIOUS"));
        review.replaceImages(List.of(
                ReviewImage.create("uploads/reviews/old-1.jpg", 0),
                ReviewImage.create("uploads/reviews/old-2.jpg", 1)
        ));
        reviewRepository.saveAndFlush(review);
        Long reviewId = review.getId();
        given(currentUserProvider.currentUserId()).willReturn(7L);

        myReviewService.updateMyReview(reviewId, new UpdateReviewRequest(
                5,
                List.of("GOOD_VALUE"),
                "수정 후에도 충분히 긴 리뷰 내용입니다.",
                List.of(
                        "uploads/reviews/new-1.jpg",
                        "uploads/reviews/new-2.jpg")
        ));
        transactionTemplate.executeWithoutResult(status -> {
            Review updated = reviewRepository.findById(reviewId).orElseThrow();
            assertThat(updated.getKeywords()).containsExactly("GOOD_VALUE");
            assertThat(updated.getImages())
                    .extracting(ReviewImage::getFileKey)
                    .containsExactly(
                            "uploads/reviews/new-1.jpg",
                            "uploads/reviews/new-2.jpg");
            assertThat(updated.getImages())
                    .extracting(ReviewImage::getDisplayOrder)
                    .containsExactly(0, 1);
        });
    }

    @Test
    void 식당_통계_갱신이_실패하면_앞서_flush한_리뷰_변경도_롤백한다() {
        Review review = Review.create(101L, 11L, 7L, 5, "롤백 전 기존 리뷰 내용입니다.");
        review.replaceKeywords(List.of("FOOD_IS_DELICIOUS"));
        review.replaceImages(List.of(ReviewImage.create("uploads/reviews/original.jpg", 0)));
        reviewRepository.saveAndFlush(review);
        Long reviewId = review.getId();
        given(currentUserProvider.currentUserId()).willReturn(7L);
        willThrow(new IllegalStateException("통계 갱신 실패"))
                .given(restaurantPort)
                .updateReviewRatingStatistics(11L, 5, 3);

        assertThatThrownBy(() -> myReviewService.updateMyReview(
                reviewId,
                new UpdateReviewRequest(
                        3,
                        List.of("GOOD_VALUE"),
                        "식당 통계 실패로 저장되면 안 되는 수정 내용입니다.",
                        List.of("uploads/reviews/changed.jpg")
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("통계 갱신 실패");

        transactionTemplate.executeWithoutResult(status -> {
            Review unchanged = reviewRepository.findById(reviewId).orElseThrow();
            assertThat(unchanged.getRating()).isEqualTo(5);
            assertThat(unchanged.getContent()).isEqualTo("롤백 전 기존 리뷰 내용입니다.");
            assertThat(unchanged.getKeywords()).containsExactly("FOOD_IS_DELICIOUS");
            assertThat(unchanged.getImages())
                    .extracting(ReviewImage::getFileKey)
                    .containsExactly("uploads/reviews/original.jpg");
        });
    }

    @Test
    void 식당_통계_차감이_실패하면_앞서_flush한_리뷰_삭제도_롤백한다() {
        Review review = Review.create(102L, 12L, 7L, 4, "삭제 롤백 전 기존 리뷰 내용입니다.");
        review.replaceKeywords(List.of("FOOD_IS_DELICIOUS"));
        reviewRepository.saveAndFlush(review);
        Long reviewId = review.getId();
        given(currentUserProvider.currentUserId()).willReturn(7L);
        willThrow(new IllegalStateException("통계 차감 실패"))
                .given(restaurantPort)
                .decreaseReviewStatistics(12L, 4);

        assertThatThrownBy(() -> myReviewService.deleteMyReview(reviewId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("통계 차감 실패");

        transactionTemplate.executeWithoutResult(status -> {
            Review unchanged = reviewRepository.findById(reviewId).orElseThrow();
            assertThat(unchanged.isDeleted()).isFalse();
            assertThat(unchanged.getRating()).isEqualTo(4);
            assertThat(unchanged.getContent()).isEqualTo("삭제 롤백 전 기존 리뷰 내용입니다.");
        });
    }
}
