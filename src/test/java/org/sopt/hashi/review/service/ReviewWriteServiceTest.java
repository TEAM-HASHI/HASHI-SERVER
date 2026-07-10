package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.CreateReviewRequest;
import org.sopt.hashi.review.dto.CreateReviewResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReviewWriteServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long RESTAURANT_ID = 10L;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReservationPort reservationPort;

    @Mock
    private RestaurantPort restaurantPort;

    @Mock
    private PointPort pointPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private ReviewWriteService reviewWriteService;

    @BeforeEach
    void setUp() {
        reviewWriteService = new ReviewWriteService(
                reviewRepository,
                reservationPort,
                restaurantPort,
                pointPort,
                currentUserProvider
        );
    }

    @Test
    void 방문_완료된_본인_예약에_리뷰를_작성하고_포인트를_적립한다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.getReviewInfoByIdAndUserId(RESERVATION_ID, USER_ID))
                .willReturn(reservation(USER_ID, ReservationStatus.VISITED));
        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(true);
        given(reviewRepository.existsByReservationIdAndActiveTrue(RESERVATION_ID)).willReturn(false);
        given(reviewRepository.saveAndFlush(any(Review.class))).willAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            ReflectionTestUtils.setField(review, "id", 1L);
            return review;
        });
        given(pointPort.earnReviewReward(USER_ID, RESERVATION_ID)).willReturn(500L);

        CreateReviewResponse response = reviewWriteService.create(request());

        assertThat(response.reviewId()).isEqualTo(1L);
        assertThat(response.earnedPoint()).isEqualTo(500L);

        ArgumentCaptor<Review> reviewCaptor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).saveAndFlush(reviewCaptor.capture());
        Review savedReview = reviewCaptor.getValue();
        assertThat(savedReview.getReservationId()).isEqualTo(RESERVATION_ID);
        assertThat(savedReview.getRestaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(savedReview.getWriterId()).isEqualTo(USER_ID);
        assertThat(savedReview.getKeywords())
                .containsExactly("FOOD_IS_DELICIOUS", "GOOD_VALUE");
        assertThat(savedReview.getImages())
                .extracting(image -> image.getFileKey(), image -> image.getDisplayOrder())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("uploads/reviews/2026/07/10/review-1.jpg", 0)
                );
        verify(pointPort).earnReviewReward(USER_ID, RESERVATION_ID);
    }

    @Test
    void 방문_완료되지_않은_예약에는_리뷰를_작성할_수_없다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.getReviewInfoByIdAndUserId(RESERVATION_ID, USER_ID))
                .willReturn(reservation(USER_ID, ReservationStatus.CONFIRMED));

        assertThatThrownBy(() -> reviewWriteService.create(request()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.NOT_VISITED));

        verifyNoInteractions(restaurantPort, reviewRepository, pointPort);
    }

    @Test
    void 어디든_예약에는_리뷰를_작성할_수_없다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.getReviewInfoByIdAndUserId(RESERVATION_ID, USER_ID))
                .willReturn(anywhereReservation());

        assertThatThrownBy(() -> reviewWriteService.create(request()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReviewErrorCode.UNSUPPORTED_RESERVATION_TYPE));

        verifyNoInteractions(restaurantPort, reviewRepository, pointPort);
    }

    @Test
    void 활성_리뷰가_이미_있으면_중복_작성할_수_없다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.getReviewInfoByIdAndUserId(RESERVATION_ID, USER_ID))
                .willReturn(reservation(USER_ID, ReservationStatus.VISITED));
        given(restaurantPort.existsById(RESTAURANT_ID)).willReturn(true);
        given(reviewRepository.existsByReservationIdAndActiveTrue(RESERVATION_ID)).willReturn(true);

        assertThatThrownBy(() -> reviewWriteService.create(request()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.ALREADY_REVIEWED));

        verifyNoInteractions(pointPort);
    }

    private CreateReviewRequest request() {
        return new CreateReviewRequest(
                RESERVATION_ID,
                5,
                List.of("FOOD_IS_DELICIOUS", "GOOD_VALUE"),
                "음식이 맛있고 직원분들이 친절했습니다.",
                List.of("uploads/reviews/2026/07/10/review-1.jpg")
        );
    }

    private ReservationReviewInfo reservation(Long userId, ReservationStatus status) {
        return new ReservationReviewInfo(
                RESERVATION_ID,
                userId,
                ReservationType.STANDARD,
                RESTAURANT_ID,
                null,
                null,
                LocalDateTime.of(2026, 7, 1, 18, 0),
                2,
                0,
                0,
                status
        );
    }

    private ReservationReviewInfo anywhereReservation() {
        return new ReservationReviewInfo(
                RESERVATION_ID,
                USER_ID,
                ReservationType.ANYWHERE,
                null,
                "긴자 미등록 식당",
                "도쿄도 주오구 긴자",
                LocalDateTime.of(2026, 7, 1, 18, 0),
                2,
                0,
                0,
                ReservationStatus.VISITED
        );
    }
}
