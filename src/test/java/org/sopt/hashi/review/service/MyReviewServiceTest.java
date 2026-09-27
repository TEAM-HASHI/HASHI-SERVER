package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
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
    private MediaPort mediaPort;

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
                mediaPort,
                userPort,
                fileStorage,
                currentUserProvider
        );
    }

    @Test
    void 내가_작성한_리뷰_목록을_예약과_식당_정보로_조합한다() {
        Review review = review(USER_ID);
        ReservationReviewInfo reservation = reservation();
        UUID assetId = UUID.randomUUID();
        RestaurantInfo restaurant = restaurant(
                new ImageReference(assetId, "https://legacy.example.com/restaurants/10.jpg"));
        MediaImage readyImage = readyImage(
                assetId, "https://cdn.example.com/restaurants/10/192.webp");

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByUserIdAndDeletedFalseOrderByIdDesc(
                USER_ID, PageRequest.of(0, 11)))
                .willReturn(List.of(review));
        given(reservationPort.findReviewInfos(List.of(RESERVATION_ID)))
                .willReturn(List.of(reservation));
        given(restaurantPort.findSummaries(List.of(RESTAURANT_ID)))
                .willReturn(List.of(restaurant));
        given(mediaPort.findImages(any())).willReturn(Map.of(request(assetId), readyImage));

        MyReviewListResponse response = myReviewService.getMyReviews(null, null);

        assertThat(response.content()).hasSize(1);
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.content().getFirst().reviewId()).isEqualTo(REVIEW_ID);
        assertThat(response.content().getFirst().restaurantName()).isEqualTo("야키토리 무사시");
        assertThat(response.content().getFirst().restaurantThumbnailUrl())
                .isEqualTo("https://cdn.example.com/restaurants/10/192.webp");
        assertThat(response.content().getFirst().restaurantThumbnailImage())
                .isEqualTo(readyImage);
        assertThat(response.content().getFirst().visitedAt()).isEqualTo(reservation.reservedAt());
        assertThat(response.content().getFirst().keywords()).containsExactly("음식이 맛있어요");
        verify(reservationPort).findReviewInfos(List.of(RESERVATION_ID));
        verify(restaurantPort).findSummaries(List.of(RESTAURANT_ID));
        verify(mediaPort).findImages(argThat(
                requests -> List.copyOf(requests).equals(List.of(request(assetId)))));
    }

    @Test
    void 내가_작성한_리뷰_상세는_전체_이미지를_반환한다() {
        Review review = review(USER_ID);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndUserIdAndDeletedFalse(REVIEW_ID, USER_ID))
                .willReturn(Optional.of(review));
        given(reservationPort.getReviewInfoByIdAndUserId(RESERVATION_ID, USER_ID))
                .willReturn(reservation());
        given(restaurantPort.findSummaryById(RESTAURANT_ID)).willReturn(Optional.of(restaurant()));
        given(userPort.findById(USER_ID)).willReturn(Optional.empty());
        given(fileStorage.resolveFileUrl("uploads/reviews/20/1.jpg"))
                .willReturn("https://cdn.example.com/reviews/20/1.jpg");

        MyReviewDetailResponse response = myReviewService.getMyReview(REVIEW_ID);

        assertThat(response.reviewId()).isEqualTo(REVIEW_ID);
        assertThat(response.reviewerNickname()).isEqualTo("탈퇴한 회원");
        assertThat(response.imageUrls())
                .containsExactly("https://cdn.example.com/reviews/20/1.jpg");
        verifyNoInteractions(mediaPort);
    }

    @Test
    void 내_리뷰_상세의_PROCESSING_식당_asset은_legacy_URL로_우회하지_않는다() {
        Review review = review(USER_ID);
        UUID assetId = UUID.randomUUID();
        MediaImage processingImage = statusImage(assetId, MediaImageStatus.PROCESSING);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndUserIdAndDeletedFalse(REVIEW_ID, USER_ID))
                .willReturn(Optional.of(review));
        given(reservationPort.getReviewInfoByIdAndUserId(RESERVATION_ID, USER_ID))
                .willReturn(reservation());
        given(restaurantPort.findSummaryById(RESTAURANT_ID)).willReturn(Optional.of(restaurant(
                new ImageReference(
                        assetId,
                        "https://legacy.example.com/restaurants/10/thumbnail.jpg"))));
        given(userPort.findById(USER_ID)).willReturn(Optional.empty());
        given(fileStorage.resolveFileUrl("uploads/reviews/20/1.jpg"))
                .willReturn("https://cdn.example.com/reviews/20/1.jpg");
        given(mediaPort.findImages(any()))
                .willReturn(Map.of(request(assetId), processingImage));

        MyReviewDetailResponse response = myReviewService.getMyReview(REVIEW_ID);

        assertThat(response.restaurantThumbnailUrl()).isNull();
        assertThat(response.restaurantThumbnailImage()).isEqualTo(processingImage);
        verify(mediaPort).findImages(argThat(
                requests -> List.copyOf(requests).equals(List.of(request(assetId)))));
    }

    @Test
    void 커서_다음_페이지가_있으면_마지막_응답_리뷰_ID를_다음_커서로_반환한다() {
        UUID assetId = UUID.randomUUID();
        Review firstReview = review(USER_ID, 30L, RESERVATION_ID, RESTAURANT_ID);
        Review extraReview = review(USER_ID, 20L, 200L, 20L);
        MediaImage readyImage = readyImage(assetId, "https://cdn.example.com/10/192.webp");

        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByUserIdAndDeletedFalseAndIdLessThanOrderByIdDesc(
                USER_ID, 40L, PageRequest.of(0, 2)))
                .willReturn(List.of(firstReview, extraReview));
        given(reservationPort.findReviewInfos(List.of(RESERVATION_ID)))
                .willReturn(List.of(reservation()));
        given(restaurantPort.findSummaries(List.of(RESTAURANT_ID)))
                .willReturn(List.of(restaurant(ImageReference.asset(assetId))));
        given(mediaPort.findImages(any())).willReturn(Map.of(request(assetId), readyImage));

        MyReviewListResponse response = myReviewService.getMyReviews(40L, 1);

        assertThat(response.content()).extracting(MyReviewListResponse.MyReviewSummaryResponse::reviewId)
                .containsExactly(30L);
        assertThat(response.nextCursor()).isEqualTo(30L);
        assertThat(response.hasNext()).isTrue();
        verify(reviewRepository).findByUserIdAndDeletedFalseAndIdLessThanOrderByIdDesc(
                USER_ID, 40L, PageRequest.of(0, 2));
        verify(reservationPort).findReviewInfos(List.of(RESERVATION_ID));
        verify(restaurantPort).findSummaries(List.of(RESTAURANT_ID));
        verify(mediaPort).findImages(argThat(requests ->
                Set.copyOf(requests).equals(Set.of(request(assetId)))));
    }

    @Test
    void 작성자는_리뷰를_soft_delete_할_수_있다() {
        Review review = review(USER_ID);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndUserIdAndDeletedFalse(REVIEW_ID, USER_ID))
                .willReturn(Optional.of(review));
        given(reviewRepository.softDeleteByIdAndUserId(REVIEW_ID, USER_ID)).willReturn(1);

        myReviewService.deleteMyReview(REVIEW_ID);

        verify(reviewRepository).softDeleteByIdAndUserId(REVIEW_ID, USER_ID);
        verify(restaurantPort).decreaseReviewStatistics(RESTAURANT_ID, 5);
    }

    @Test
    void 다른_사용자의_리뷰는_존재하지_않는_것처럼_처리한다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndUserIdAndDeletedFalse(REVIEW_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> myReviewService.deleteMyReview(REVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.NOT_FOUND));
    }

    @Test
    void 동시에_삭제된_리뷰는_식당_통계를_중복_차감하지_않는다() {
        Review review = review(USER_ID);
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.findByIdAndUserIdAndDeletedFalse(REVIEW_ID, USER_ID))
                .willReturn(Optional.of(review));
        given(reviewRepository.softDeleteByIdAndUserId(REVIEW_ID, USER_ID)).willReturn(0);

        assertThatThrownBy(() -> myReviewService.deleteMyReview(REVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.NOT_FOUND));

        verifyNoInteractions(restaurantPort);
    }

    @Test
    void 내가_작성한_활성_리뷰_개수를_조회한다() {
        given(currentUserProvider.currentUserId()).willReturn(USER_ID);
        given(reviewRepository.countByUserIdAndDeletedFalse(USER_ID)).willReturn(3L);

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

    private Review review(Long userId) {
        return review(userId, REVIEW_ID);
    }

    private Review review(Long userId, Long reviewId) {
        return review(userId, reviewId, RESERVATION_ID, RESTAURANT_ID);
    }

    private Review review(
            Long userId,
            Long reviewId,
            Long reservationId,
            Long restaurantId
    ) {
        Review review = Review.create(
                reservationId,
                restaurantId,
                userId,
                5,
                "직원분들이 친절하고 음식이 맛있었습니다."
        );
        review.replaceKeywords(List.of("FOOD_IS_DELICIOUS"));
        review.replaceImages(List.of(ReviewImage.create("uploads/reviews/20/1.jpg", 0)));
        ReflectionTestUtils.setField(review, "id", reviewId);
        ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.of(2026, 6, 28, 12, 34));
        return review;
    }

    private RestaurantInfo restaurant() {
        return restaurant(ImageReference.legacy(
                "https://cdn.example.com/restaurants/10/thumbnail.jpg"));
    }

    private RestaurantInfo restaurant(ImageReference imageReference) {
        return new RestaurantInfo(
                RESTAURANT_ID,
                "야키토리 무사시",
                "도쿄도 시부야구",
                imageReference
        );
    }

    private MediaImageRequest request(UUID assetId) {
        return new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_THUMBNAIL);
    }

    private MediaImage readyImage(UUID assetId, String url) {
        MediaImage.Source source = new MediaImage.Source(url, 192, 192, "image/webp");
        return new MediaImage(
                assetId,
                MediaImageRole.RESTAURANT_THUMBNAIL,
                MediaImageStatus.READY,
                source,
                List.of(new MediaImage.SourceSet(
                        "image/webp",
                        List.of(new MediaImage.Candidate(url, 192, 192)))));
    }

    private MediaImage statusImage(UUID assetId, MediaImageStatus status) {
        return new MediaImage(
                assetId,
                MediaImageRole.RESTAURANT_THUMBNAIL,
                status,
                null,
                List.of());
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
