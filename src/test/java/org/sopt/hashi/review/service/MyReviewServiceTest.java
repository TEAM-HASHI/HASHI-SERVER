package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.MyReviewListResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MyReviewServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long REVIEW_ID = 20L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long RESTAURANT_ID = 10L;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReservationPort reservationPort;

    @Mock
    private RestaurantPort restaurantPort;

    @Mock
    private UserPort userPort;

    @Mock
    private FileStorage fileStorage;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private MyReviewService myReviewService;

    @BeforeEach
    void setUp() {
        myReviewService = new MyReviewService(
                reviewRepository,
                reservationPort,
                restaurantPort,
                userPort,
                fileStorage,
                currentUserProvider
        );
    }

    @Test
    void 내가_작성한_리뷰_목록을_예약과_식당_정보로_조합한다() {
        Review review = review(USER_ID);
        ReservationReviewInfo reservation = reservation();
        RestaurantInfo restaurant = new RestaurantInfo(
                RESTAURANT_ID,
                "야키토리 무사시",
                "도쿄도 시부야구",
                "https://cdn.example.com/restaurants/10/thumbnail.jpg"
        );

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByWriterIdAndActiveTrueOrderByIdDesc(
                USER_ID, PageRequest.of(0, 11)))
                .willReturn(List.of(review));
        given(reservationPort.findReviewInfos(List.of(RESERVATION_ID)))
                .willReturn(List.of(reservation));
        given(restaurantPort.findSummaries(List.of(RESTAURANT_ID)))
                .willReturn(List.of(restaurant));
        given(fileStorage.resolveFileUrl("uploads/reviews/20/1.jpg"))
                .willReturn("https://cdn.example.com/reviews/20/1.jpg");

        MyReviewListResponse response = myReviewService.getMyReviews(null, null);

        assertThat(response.content()).hasSize(1);
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.content().getFirst().reviewId()).isEqualTo(REVIEW_ID);
        assertThat(response.content().getFirst().restaurantName()).isEqualTo("야키토리 무사시");
        assertThat(response.content().getFirst().visitedAt()).isEqualTo(reservation.reservedAt());
        assertThat(response.content().getFirst().keywords()).containsExactly("음식이 맛있어요");
        assertThat(response.content().getFirst().imageUrls())
                .containsExactly("https://cdn.example.com/reviews/20/1.jpg");

        verify(reservationPort).findReviewInfos(List.of(RESERVATION_ID));
        verify(restaurantPort).findSummaries(List.of(RESTAURANT_ID));
    }

    @Test
    void 작성자는_리뷰를_soft_delete_할_수_있다() {
        Review review = review(USER_ID);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findById(REVIEW_ID)).willReturn(Optional.of(review));

        myReviewService.deleteMyReview(REVIEW_ID);

        assertThat(review.isActive()).isFalse();
    }

    @Test
    void 다른_사용자의_리뷰는_삭제할_수_없다() {
        Review review = review(OTHER_USER_ID);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findById(REVIEW_ID)).willReturn(Optional.of(review));

        assertThatThrownBy(() -> myReviewService.deleteMyReview(REVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));

        assertThat(review.isActive()).isTrue();
    }

    private Review review(Long writerId) {
        Review review = Review.create(
                RESERVATION_ID,
                RESTAURANT_ID,
                writerId,
                5,
                "직원분들이 친절하고 음식이 맛있었습니다."
        );
        review.replaceKeywords(List.of("FOOD_IS_DELICIOUS"));
        review.replaceImages(List.of(ReviewImage.create("uploads/reviews/20/1.jpg", 0)));
        ReflectionTestUtils.setField(review, "id", REVIEW_ID);
        ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.of(2026, 6, 28, 12, 34));
        return review;
    }

    private ReservationReviewInfo reservation() {
        return new ReservationReviewInfo(
                RESERVATION_ID,
                USER_ID,
                RESTAURANT_ID,
                LocalDateTime.of(2026, 6, 22, 17, 0),
                2,
                0,
                0,
                ReservationStatus.VISITED
        );
    }
}
