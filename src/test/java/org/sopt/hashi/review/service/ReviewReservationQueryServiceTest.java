package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewLifecycleStatus;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.ReviewContextResponse;
import org.sopt.hashi.review.dto.ReviewUnavailableReason;
import org.sopt.hashi.review.dto.VisitedReservationListResponse;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReviewReservationQueryServiceTest {

    private static final Long USER_ID = 7L;

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

    private ReviewReservationQueryService reviewReservationQueryService;

    @BeforeEach
    void setUp() {
        reviewReservationQueryService = new ReviewReservationQueryService(
                reviewRepository,
                reservationPort,
                restaurantPort,
                pointPort,
                currentUserProvider
        );
    }

    @Test
    void 방문_완료된_미작성_예약의_리뷰_작성_정보를_조회한다() {
        ReservationReviewInfo reservation = reservation(100L, 10L, 22, ReservationStatus.VISITED);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.getReviewInfoByIdAndUserId(100L, USER_ID)).willReturn(reservation);
        given(restaurantPort.findSummaryById(10L))
                .willReturn(Optional.of(restaurant(10L, "아키토리 무사시")));
        given(reviewRepository.existsByReservationId(100L)).willReturn(false);

        ReviewContextResponse response = reviewReservationQueryService.getContext(100L);

        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.restaurantId()).isEqualTo(10L);
        assertThat(response.restaurantName()).isEqualTo("아키토리 무사시");
        assertThat(response.teenCount()).isZero();
        assertThat(response.reviewable()).isTrue();
        assertThat(response.reviewUnavailableReason()).isNull();
        assertThat(response.reviewKeywordOptions())
                .extracting(ReviewContextResponse.ReviewKeywordOption::code)
                .contains("FOOD_IS_DELICIOUS", "STAFF_IS_KIND", "GOOD_VALUE");
    }

    @Test
    void 방문_완료_예약을_최신순으로_조회하고_리뷰와_포인트를_조합한다() {
        List<ReservationReviewInfo> reservations = List.of(
                reservation(100L, 10L, 20, ReservationStatus.VISITED),
                reservation(101L, 11L, 22, ReservationStatus.VISITED),
                reservation(102L, 12L, 21, ReservationStatus.VISITED)
        );
        Review review100 = review(50L, 100L, 10L, 5);
        Review review102 = review(52L, 102L, 12L, 4);

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.findVisitedReviewInfos(USER_ID)).willReturn(reservations);
        given(reviewRepository.findByReservationIdIn(List.of(100L, 101L, 102L)))
                .willReturn(List.of(review100, review102));
        given(restaurantPort.findSummaries(List.of(11L, 12L)))
                .willReturn(List.of(
                        restaurant(11L, "돈카츠 하지메"),
                        restaurant(12L, "스시 하루")));
        given(pointPort.findEarnedAmounts(PointSourceType.REVIEW, List.of(102L)))
                .willReturn(Map.of(102L, 500L));

        VisitedReservationListResponse response = reviewReservationQueryService
                .getVisitedReservations("all", null, "latest", null, 2);

        assertThat(response.totalCount()).isEqualTo(3L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(102L);
        assertThat(response.content())
                .extracting(item -> item.reservationId())
                .containsExactly(101L, 102L);
        assertThat(response.content().getFirst().reviewStatus()).isEqualTo(
                ReviewLifecycleStatus.UNREVIEWED);
        assertThat(response.content().getFirst().teenCount()).isZero();
        assertThat(response.content().getFirst().reviewable()).isTrue();
        assertThat(response.content().getFirst().reviewId()).isNull();
        assertThat(response.content().get(1).reviewStatus()).isEqualTo(
                ReviewLifecycleStatus.REVIEWED);
        assertThat(response.content().get(1).reviewable()).isFalse();
        assertThat(response.content().get(1).reviewUnavailableReason())
                .isEqualTo(ReviewUnavailableReason.ALREADY_REVIEWED);
        assertThat(response.content().get(1).reviewId()).isEqualTo(52L);
        assertThat(response.content().get(1).rating()).isEqualTo(4);
        assertThat(response.content().get(1).earnedPoint()).isEqualTo(500L);
    }

    @Test
    void 식당_상세에서는_리뷰_미작성_예약_중_가장_오래된_예약을_먼저_조회한다() {
        List<ReservationReviewInfo> reservations = List.of(
                reservation(100L, 10L, 20, ReservationStatus.VISITED),
                reservation(101L, 10L, 22, ReservationStatus.VISITED),
                reservation(102L, 11L, 21, ReservationStatus.VISITED)
        );

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(restaurantPort.existsById(10L)).willReturn(true);
        given(reservationPort.findVisitedReviewInfos(USER_ID)).willReturn(reservations);
        given(reviewRepository.findByReservationIdIn(List.of(100L, 101L)))
                .willReturn(List.of());
        given(restaurantPort.findSummaries(List.of(10L)))
                .willReturn(List.of(restaurant(10L, "아키토리 무사시")));

        VisitedReservationListResponse response = reviewReservationQueryService
                .getVisitedReservations("unreviewed", 10L, "oldest", null, 1);

        assertThat(response.totalCount()).isEqualTo(2L);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().reservationId()).isEqualTo(100L);
        assertThat(response.content().getFirst().reviewStatus()).isEqualTo(
                ReviewLifecycleStatus.UNREVIEWED);
        assertThat(response.nextCursor()).isEqualTo(100L);
        assertThat(response.hasNext()).isTrue();
        verify(restaurantPort).existsById(10L);
    }

    @Test
    void 어디든_예약은_방문_완료_전체에는_포함하고_리뷰_쓰기에서는_제외한다() {
        ReservationReviewInfo anywhere = anywhereReservation(200L, 23);

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.findVisitedReviewInfos(USER_ID)).willReturn(List.of(anywhere));
        given(reviewRepository.findByReservationIdIn(List.of(200L)))
                .willReturn(List.of());

        VisitedReservationListResponse allResponse = reviewReservationQueryService
                .getVisitedReservations("all", null, "latest", null, 10);
        VisitedReservationListResponse unreviewedResponse = reviewReservationQueryService
                .getVisitedReservations("unreviewed", null, "latest", null, 10);

        assertThat(allResponse.content()).hasSize(1);
        assertThat(allResponse.content().getFirst().restaurantId()).isNull();
        assertThat(allResponse.content().getFirst().restaurantName()).isEqualTo("긴자 미등록 식당");
        assertThat(allResponse.content().getFirst().reviewStatus())
                .isEqualTo(ReviewLifecycleStatus.UNREVIEWED);
        assertThat(allResponse.content().getFirst().reviewable()).isFalse();
        assertThat(allResponse.content().getFirst().reviewUnavailableReason())
                .isEqualTo(ReviewUnavailableReason.UNSUPPORTED_RESERVATION_TYPE);
        assertThat(unreviewedResponse.content()).isEmpty();
    }

    @Test
    void 방문_완료_목록을_조합할_때_연결된_식당이_없으면_정합성_오류로_처리한다() {
        ReservationReviewInfo reservation = reservation(
                100L,
                10L,
                22,
                ReservationStatus.VISITED);

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.findVisitedReviewInfos(USER_ID)).willReturn(List.of(reservation));
        given(reviewRepository.findByReservationIdIn(List.of(100L)))
                .willReturn(List.of());
        given(restaurantPort.findSummaries(List.of(10L))).willReturn(List.of());

        assertThatThrownBy(() -> reviewReservationQueryService
                .getVisitedReservations("all", null, "latest", null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약(id=100)")
                .hasMessageContaining("식당(id=10)");
    }

    @Test
    void 삭제한_리뷰는_전체_방문_목록에서_삭제_상태로_반환한다() {
        ReservationReviewInfo reservation = reservation(
                100L,
                10L,
                22,
                ReservationStatus.VISITED);
        Review deletedReview = review(50L, 100L, 10L, 5);
        deletedReview.softDelete();

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.findVisitedReviewInfos(USER_ID)).willReturn(List.of(reservation));
        given(reviewRepository.findByReservationIdIn(List.of(100L)))
                .willReturn(List.of(deletedReview));
        given(restaurantPort.findSummaries(List.of(10L)))
                .willReturn(List.of(restaurant(10L, "야키토리 무사시")));
        given(pointPort.findEarnedAmounts(PointSourceType.REVIEW, List.of(100L)))
                .willReturn(Map.of(100L, 500L));

        VisitedReservationListResponse response = reviewReservationQueryService
                .getVisitedReservations("all", null, "latest", null, 10);

        VisitedReservationListResponse.VisitedReservationResponse item = response.content().getFirst();
        assertThat(item.reviewStatus()).isEqualTo(ReviewLifecycleStatus.DELETED);
        assertThat(item.reviewable()).isFalse();
        assertThat(item.reviewId()).isNull();
        assertThat(item.rating()).isNull();
        assertThat(item.earnedPoint()).isEqualTo(500L);
    }

    @Test
    void 커서_예약의_리뷰_상태가_바뀌어도_다음_작성완료_예약을_조회한다() {
        List<ReservationReviewInfo> reservations = List.of(
                reservation(103L, 13L, 23, ReservationStatus.VISITED),
                reservation(102L, 12L, 22, ReservationStatus.VISITED),
                reservation(101L, 11L, 21, ReservationStatus.VISITED)
        );
        Review review103 = review(53L, 103L, 13L, 5);
        Review deletedReview102 = review(52L, 102L, 12L, 4);
        deletedReview102.softDelete();
        Review review101 = review(51L, 101L, 11L, 3);

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reservationPort.findVisitedReviewInfos(USER_ID)).willReturn(reservations);
        given(reviewRepository.findByReservationIdIn(List.of(103L, 102L, 101L)))
                .willReturn(List.of(review103, deletedReview102, review101));
        given(restaurantPort.findSummaries(List.of(11L)))
                .willReturn(List.of(restaurant(11L, "스시 하루")));
        given(pointPort.findEarnedAmounts(PointSourceType.REVIEW, List.of(101L)))
                .willReturn(Map.of(101L, 500L));

        VisitedReservationListResponse response = reviewReservationQueryService
                .getVisitedReservations("reviewed", null, "latest", 102L, 10);

        assertThat(response.totalCount()).isEqualTo(2L);
        assertThat(response.content())
                .extracting(VisitedReservationListResponse.VisitedReservationResponse::reservationId)
                .containsExactly(101L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    private ReservationReviewInfo reservation(
            Long reservationId,
            Long restaurantId,
            int day,
            ReservationStatus status
    ) {
        return new ReservationReviewInfo(
                reservationId,
                USER_ID,
                ReservationType.STANDARD,
                restaurantId,
                null,
                null,
                LocalDateTime.of(2026, 6, day, 17, 0),
                2,
                0,
                0,
                status
        );
    }

    private ReservationReviewInfo anywhereReservation(Long reservationId, int day) {
        return new ReservationReviewInfo(
                reservationId,
                USER_ID,
                ReservationType.ANYWHERE,
                null,
                "긴자 미등록 식당",
                "도쿄도 주오구 긴자",
                LocalDateTime.of(2026, 6, day, 17, 0),
                2,
                0,
                0,
                ReservationStatus.VISITED
        );
    }

    private RestaurantInfo restaurant(Long restaurantId, String name) {
        return new RestaurantInfo(
                restaurantId,
                name,
                "도쿄도",
                "https://cdn.example.com/restaurants/%d/thumbnail.jpg".formatted(restaurantId)
        );
    }

    private Review review(
            Long reviewId,
            Long reservationId,
            Long restaurantId,
            int rating
    ) {
        Review review = Review.create(
                reservationId,
                restaurantId,
                USER_ID,
                rating,
                "리뷰 내용입니다."
        );
        ReflectionTestUtils.setField(review, "id", reviewId);
        return review;
    }
}
