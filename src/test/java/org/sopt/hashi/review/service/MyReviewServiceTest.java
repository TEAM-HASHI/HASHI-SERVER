package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.MyReviewCountResponse;
import org.sopt.hashi.review.dto.MyReviewDetailResponse;
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
        RestaurantInfo restaurant = restaurant();

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
    void 예약_연결이_없는_레거시_리뷰도_방문_정보를_제외하고_목록에_반환한다() {
        Review legacyReview = legacyReview(USER_ID);

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByWriterIdAndActiveTrueOrderByIdDesc(
                USER_ID, PageRequest.of(0, 11)))
                .willReturn(List.of(legacyReview));
        given(reservationPort.findReviewInfos(List.of())).willReturn(List.of());
        given(restaurantPort.findSummaries(List.of(RESTAURANT_ID)))
                .willReturn(List.of(restaurant()));

        MyReviewListResponse response = myReviewService.getMyReviews(null, null);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().visitedAt()).isNull();
        assertThat(response.content().getFirst().adultCount()).isNull();
        assertThat(response.content().getFirst().childCount()).isNull();
        verify(reservationPort).findReviewInfos(List.of());
    }

    @Test
    void 예약_연결이_없는_레거시_리뷰도_상세_조회할_수_있다() {
        Review legacyReview = legacyReview(USER_ID);

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndWriterIdAndActiveTrue(REVIEW_ID, USER_ID))
                .willReturn(Optional.of(legacyReview));
        given(restaurantPort.findSummaryById(RESTAURANT_ID)).willReturn(Optional.of(restaurant()));
        given(userPort.findById(USER_ID)).willReturn(Optional.empty());

        MyReviewDetailResponse response = myReviewService.getMyReview(REVIEW_ID);

        assertThat(response.reviewId()).isEqualTo(REVIEW_ID);
        assertThat(response.visitedAt()).isNull();
        assertThat(response.adultCount()).isNull();
        assertThat(response.childCount()).isNull();
        verifyNoInteractions(reservationPort);
    }

    @Test
    void 커서_다음_페이지가_있으면_마지막_응답_리뷰_ID를_다음_커서로_반환한다() {
        Review firstReview = legacyReview(USER_ID, 30L);
        Review extraReview = legacyReview(USER_ID, 20L);

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByWriterIdAndActiveTrueAndIdLessThanOrderByIdDesc(
                USER_ID, 40L, PageRequest.of(0, 2)))
                .willReturn(List.of(firstReview, extraReview));
        given(reservationPort.findReviewInfos(List.of())).willReturn(List.of());
        given(restaurantPort.findSummaries(List.of(RESTAURANT_ID)))
                .willReturn(List.of(restaurant()));

        MyReviewListResponse response = myReviewService.getMyReviews(40L, 1);

        assertThat(response.content()).extracting(MyReviewListResponse.MyReviewSummaryResponse::reviewId)
                .containsExactly(30L);
        assertThat(response.nextCursor()).isEqualTo(30L);
        assertThat(response.hasNext()).isTrue();
        verify(reviewRepository).findByWriterIdAndActiveTrueAndIdLessThanOrderByIdDesc(
                USER_ID, 40L, PageRequest.of(0, 2));
    }

    @Test
    void 작성자는_리뷰를_soft_delete_할_수_있다() {
        Review review = review(USER_ID);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndWriterIdAndActiveTrue(REVIEW_ID, USER_ID))
                .willReturn(Optional.of(review));

        myReviewService.deleteMyReview(REVIEW_ID);

        assertThat(review.isActive()).isFalse();
    }

    @Test
    void 다른_사용자의_리뷰는_존재하지_않는_것처럼_처리한다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndWriterIdAndActiveTrue(REVIEW_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> myReviewService.deleteMyReview(REVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.NOT_FOUND));
    }

    @Test
    void 내가_작성한_활성_리뷰_개수를_조회한다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.countByWriterIdAndActiveTrue(USER_ID)).willReturn(3L);

        MyReviewCountResponse response = myReviewService.getMyReviewCount();

        assertThat(response.reviewCount()).isEqualTo(3L);
    }

    @Test
    void 페이지_크기가_최대값을_초과하면_거부한다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);

        assertThatThrownBy(() -> myReviewService.getMyReviews(null, 51))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
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

    private Review legacyReview(Long writerId) {
        return legacyReview(writerId, REVIEW_ID);
    }

    private Review legacyReview(Long writerId, Long reviewId) {
        Review review = Review.create(
                RESTAURANT_ID,
                writerId,
                5,
                "예약 연결 전 작성된 리뷰입니다."
        );
        ReflectionTestUtils.setField(review, "id", reviewId);
        ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.of(2026, 6, 20, 12, 34));
        return review;
    }

    private RestaurantInfo restaurant() {
        return new RestaurantInfo(
                RESTAURANT_ID,
                "야키토리 무사시",
                "도쿄도 시부야구",
                "https://cdn.example.com/restaurants/10/thumbnail.jpg"
        );
    }

    private ReservationReviewInfo reservation() {
        return new ReservationReviewInfo(
                RESERVATION_ID,
                USER_ID,
                ReservationType.STANDARD,
                RESTAURANT_ID,
                null,
                null,
                LocalDateTime.of(2026, 6, 22, 17, 0),
                2,
                0,
                0,
                ReservationStatus.VISITED
        );
    }
}
