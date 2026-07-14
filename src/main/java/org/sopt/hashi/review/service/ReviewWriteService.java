package org.sopt.hashi.review.service;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewKeyword;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.CreateReviewRequest;
import org.sopt.hashi.review.dto.CreateReviewResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ReviewWriteService {

    private static final String REVIEW_IMAGE_PREFIX = "uploads/reviews/";

    private final ReviewRepository reviewRepository;
    private final ReservationPort reservationPort;
    private final RestaurantPort restaurantPort;
    private final PointPort pointPort;
    private final CurrentUserProvider currentUserProvider;

    public ReviewWriteService(
            ReviewRepository reviewRepository,
            ReservationPort reservationPort,
            RestaurantPort restaurantPort,
            PointPort pointPort,
            CurrentUserProvider currentUserProvider
    ) {
        this.reviewRepository = reviewRepository;
        this.reservationPort = reservationPort;
        this.restaurantPort = restaurantPort;
        this.pointPort = pointPort;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public CreateReviewResponse create(CreateReviewRequest request) {
        Long userId = currentUserProvider.currentUserId();
        ReservationReviewInfo reservation = reservationPort
                .getReviewInfoByIdAndUserId(request.reservationId(), userId);

        validateReviewableReservation(reservation);
        validateRestaurant(reservation.restaurantId());
        validateNoReviewHistory(reservation.id());

        List<String> keywordCodes = parseKeywordCodes(request.keywordCodes());
        List<ReviewImage> images = parseImages(request.imageFileKeys());

        Review review = Review.create(
                reservation.id(),
                reservation.restaurantId(),
                userId,
                request.rating(),
                request.content()
        );
        review.replaceKeywords(keywordCodes);
        review.replaceImages(images);

        Review savedReview = save(review);
        restaurantPort.increaseReviewStatistics(reservation.restaurantId(), request.rating());
        long earnedPoint = pointPort.earnReviewReward(userId, reservation.id());
        // 리뷰는 식당 통계·포인트 보상에 연쇄되는 상태 전이 — 보상 지급 여부(재작성이면 0)까지 남긴다
        log.info("리뷰 작성. reviewId={}, reservationId={}, restaurantId={}, rating={}, earnedPoint={}",
                savedReview.getId(), reservation.id(), reservation.restaurantId(), request.rating(), earnedPoint);
        return new CreateReviewResponse(savedReview.getId(), earnedPoint);
    }

    private void validateReviewableReservation(ReservationReviewInfo reservation) {
        if (!reservation.supportsReview()) {
            throw new BusinessException(ReviewErrorCode.UNSUPPORTED_RESERVATION_TYPE);
        }
        if (reservation.reservationStatus() != ReservationStatus.VISITED) {
            throw new BusinessException(ReviewErrorCode.NOT_VISITED);
        }
    }

    private void validateRestaurant(Long restaurantId) {
        if (restaurantId == null || !restaurantPort.existsById(restaurantId)) {
            throw new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND);
        }
    }

    private void validateNoReviewHistory(Long reservationId) {
        if (reviewRepository.existsByReservationId(reservationId)) {
            throw new BusinessException(ReviewErrorCode.ALREADY_REVIEWED);
        }
    }

    private List<String> parseKeywordCodes(List<String> requestedCodes) {
        if (new HashSet<>(requestedCodes).size() != requestedCodes.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return requestedCodes.stream()
                .map(code -> ReviewKeyword.fromCode(code)
                        .orElseThrow(() -> new BusinessException(ReviewErrorCode.UNSUPPORTED_KEYWORD)))
                .map(Enum::name)
                .toList();
    }

    private List<ReviewImage> parseImages(List<String> imageFileKeys) {
        if (imageFileKeys == null) {
            return List.of();
        }
        return IntStream.range(0, imageFileKeys.size())
                .mapToObj(index -> ReviewImage.create(
                        validateReviewImageKey(imageFileKeys.get(index)),
                        index))
                .toList();
    }

    private String validateReviewImageKey(String fileKey) {
        if (!fileKey.startsWith(REVIEW_IMAGE_PREFIX)
                || fileKey.contains("..")
                || fileKey.contains("\\")) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return fileKey;
    }

    private Review save(Review review) {
        try {
            return reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException exception) {
            String causeMessage = exception.getMostSpecificCause().getMessage();
            if (causeMessage != null && causeMessage.contains("uk_review_reservation_id")) {
                throw new BusinessException(ReviewErrorCode.ALREADY_REVIEWED, exception);
            }
            throw exception;
        }
    }
}
