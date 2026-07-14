package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.domain.ReviewRepository.RatingCount;
import org.sopt.hashi.review.dto.RestaurantReviewResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserPort;
import org.sopt.hashi.user.UserProfileInfo;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final Long RESTAURANT_ID = 1L;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private RestaurantPort restaurantPort;

    @Mock
    private UserPort userPort;

    @Mock
    private FileStorage fileStorage;

    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(
                reviewRepository,
                restaurantPort,
                userPort,
                fileStorage);
    }

    @Test
    void 식당_리뷰_목록을_조회하면_리뷰와_다음_커서를_반환한다() {
        List<Review> reviews = LongStream.rangeClosed(1, 6)
                .mapToObj(id -> createReview(id, id, 5, LocalDateTime.of(2026, 7, (int) id, 12, 0)))
                .toList();

        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(true);
        given(reviewRepository.findLatestPage(RESTAURANT_ID, null, null, PageRequest.of(0, 6)))
                .willReturn(reviews);
        given(reviewRepository.averageRatingByRestaurantId(RESTAURANT_ID)).willReturn(3.75);
        given(reviewRepository.countByRestaurantIdAndDeletedFalse(RESTAURANT_ID)).willReturn(6L);
        given(reviewRepository.countByRating(RESTAURANT_ID)).willReturn(List.of(
                ratingCount(5, 2L),
                ratingCount(4, 3L),
                ratingCount(3, 1L)
        ));
        given(userPort.findProfiles(List.of(1L, 2L, 3L, 4L, 5L))).willReturn(List.of(
                userProfile(1L, "하루", "https://cdn.example.com/users/1/profile.jpg"),
                userProfile(2L, "소라", null),
                userProfile(4L, "민", "https://cdn.example.com/users/4/profile.jpg"),
                userProfile(5L, "유나", "https://cdn.example.com/users/5/profile.jpg")
        ));
        given(fileStorage.resolveFileUrl(anyString()))
                .willAnswer(invocation -> "https://cdn.example.com/" + invocation.getArgument(0));

        RestaurantReviewResponse response = reviewService.getRestaurantReviews(
                RESTAURANT_ID,
                null,
                null,
                null
        );

        assertThat(response.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(response.averageRating()).isEqualTo(3.8);
        assertThat(response.reviewCount()).isEqualTo(6L);
        assertThat(response.ratingDistribution().five()).isEqualTo(2L);
        assertThat(response.ratingDistribution().four()).isEqualTo(3L);
        assertThat(response.ratingDistribution().three()).isEqualTo(1L);
        assertThat(response.ratingDistribution().two()).isZero();
        assertThat(response.ratingDistribution().one()).isZero();
        assertThat(response.content()).hasSize(5);
        assertThat(response.content().getFirst().reviewerNickname()).isEqualTo("하루");
        assertThat(response.content().getFirst().reviewerProfileImageUrl())
                .isEqualTo("https://cdn.example.com/users/1/profile.jpg");
        assertThat(response.content().get(1).reviewerProfileImageUrl()).isNull();
        assertThat(response.content().get(2).reviewerNickname()).isEqualTo("탈퇴한 회원");
        assertThat(response.content().get(2).reviewerProfileImageUrl()).isNull();
        assertThat(response.content().getFirst().keywords()).containsExactly("친절해요", "음식이 빨리 나와요");
        assertThat(response.content().getFirst().previewImageUrls())
                .containsExactly(
                        "https://cdn.example.com/uploads/reviews/1/1.jpg",
                        "https://cdn.example.com/uploads/reviews/1/2.jpg",
                        "https://cdn.example.com/uploads/reviews/1/3.jpg",
                        "https://cdn.example.com/uploads/reviews/1/4.jpg");
        assertThat(response.content().getFirst().imageCount()).isEqualTo(4);
        assertThat(response.nextCursor()).isEqualTo(5L);
        assertThat(response.hasNext()).isTrue();
        verify(userPort).findProfiles(List.of(1L, 2L, 3L, 4L, 5L));
        verify(userPort, never()).findById(anyLong());
    }

    @Test
    void 높은_평점순으로_조회하면_커서_평점과_ID로_다음_페이지를_조회한다() {
        Review cursorReview = createReview(10L, 1L, 4, LocalDateTime.of(2026, 7, 1, 12, 0));

        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(true);
        given(reviewRepository.findByIdAndRestaurantIdAndDeletedFalse(10L, RESTAURANT_ID))
                .willReturn(Optional.of(cursorReview));
        given(reviewRepository.findRatingHighPage(RESTAURANT_ID, 4, 10L, PageRequest.of(0, 4)))
                .willReturn(List.of());
        given(reviewRepository.averageRatingByRestaurantId(RESTAURANT_ID)).willReturn(null);
        given(reviewRepository.countByRestaurantIdAndDeletedFalse(RESTAURANT_ID)).willReturn(0L);
        given(reviewRepository.countByRating(RESTAURANT_ID)).willReturn(List.of());

        RestaurantReviewResponse response = reviewService.getRestaurantReviews(
                RESTAURANT_ID,
                "rating-high",
                10L,
                3
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.averageRating()).isZero();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        verify(reviewRepository).findRatingHighPage(RESTAURANT_ID, 4, 10L, PageRequest.of(0, 4));
    }

    @Test
    void 낮은_평점순으로_조회하면_커서_평점과_ID로_다음_페이지를_조회한다() {
        Review cursorReview = createReview(10L, 1L, 2, LocalDateTime.of(2026, 7, 1, 12, 0));

        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(true);
        given(reviewRepository.findByIdAndRestaurantIdAndDeletedFalse(10L, RESTAURANT_ID))
                .willReturn(Optional.of(cursorReview));
        given(reviewRepository.findRatingLowPage(RESTAURANT_ID, 2, 10L, PageRequest.of(0, 4)))
                .willReturn(List.of());
        given(reviewRepository.averageRatingByRestaurantId(RESTAURANT_ID)).willReturn(null);
        given(reviewRepository.countByRestaurantIdAndDeletedFalse(RESTAURANT_ID)).willReturn(0L);
        given(reviewRepository.countByRating(RESTAURANT_ID)).willReturn(List.of());

        RestaurantReviewResponse response = reviewService.getRestaurantReviews(
                RESTAURANT_ID,
                "rating-low",
                10L,
                3
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.averageRating()).isZero();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        verify(reviewRepository).findRatingLowPage(RESTAURANT_ID, 2, 10L, PageRequest.of(0, 4));
    }

    @Test
    void 존재하지_않는_식당의_리뷰를_조회하면_예외가_발생한다() {
        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(false);

        assertThatThrownBy(() -> reviewService.getRestaurantReviews(RESTAURANT_ID, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.RESTAURANT_NOT_FOUND));

        verifyNoInteractions(reviewRepository);
    }

    @Test
    void 지원하지_않는_정렬값으로_조회하면_예외가_발생한다() {
        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(true);

        assertThatThrownBy(() -> reviewService.getRestaurantReviews(RESTAURANT_ID, "invalid", null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.UNSUPPORTED_SORT));

        verifyNoInteractions(reviewRepository);
    }

    @Test
    void 유효하지_않은_커서로_조회하면_예외가_발생한다() {
        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(true);
        given(reviewRepository.findByIdAndRestaurantIdAndDeletedFalse(99L, RESTAURANT_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getRestaurantReviews(RESTAURANT_ID, "latest", 99L, 5))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    private Review createReview(Long id, Long reviewerId, int rating, LocalDateTime createdAt) {
        Review review = Review.create(id, RESTAURANT_ID, reviewerId, rating, "리뷰 내용입니다.");
        review.replaceKeywords(List.of("친절해요", "음식이 빨리 나와요"));
        review.replaceImages(List.of(
                ReviewImage.create("uploads/reviews/%d/1.jpg".formatted(id), 0),
                ReviewImage.create("uploads/reviews/%d/2.jpg".formatted(id), 1),
                ReviewImage.create("uploads/reviews/%d/3.jpg".formatted(id), 2),
                ReviewImage.create("uploads/reviews/%d/4.jpg".formatted(id), 3)));
        ReflectionTestUtils.setField(review, "id", id);
        ReflectionTestUtils.setField(review, "createdAt", createdAt);
        return review;
    }

    private UserProfileInfo userProfile(Long id, String nickname, String profileImageUrl) {
        return new UserProfileInfo(id, nickname, profileImageUrl);
    }

    private RatingCount ratingCount(Integer rating, Long count) {
        return new RatingCount() {
            @Override
            public Integer getRating() {
                return rating;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }
}
