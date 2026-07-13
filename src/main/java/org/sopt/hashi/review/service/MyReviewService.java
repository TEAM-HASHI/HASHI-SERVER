package org.sopt.hashi.review.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewKeyword;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.MyReviewCountResponse;
import org.sopt.hashi.review.dto.MyReviewDetailResponse;
import org.sopt.hashi.review.dto.MyReviewListResponse;
import org.sopt.hashi.review.dto.MyReviewListResponse.MyReviewSummaryResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserInfo;
import org.sopt.hashi.user.UserPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MyReviewService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String WITHDRAWN_REVIEWER_NICKNAME = "탈퇴한 회원";

    private final ReviewRepository reviewRepository;
    private final ReservationPort reservationPort;
    private final RestaurantPort restaurantPort;
    private final UserPort userPort;
    private final FileStorage fileStorage;
    private final CurrentUserProvider currentUserProvider;

    public MyReviewService(
            ReviewRepository reviewRepository,
            ReservationPort reservationPort,
            RestaurantPort restaurantPort,
            UserPort userPort,
            FileStorage fileStorage,
            CurrentUserProvider currentUserProvider
    ) {
        this.reviewRepository = reviewRepository;
        this.reservationPort = reservationPort;
        this.restaurantPort = restaurantPort;
        this.userPort = userPort;
        this.fileStorage = fileStorage;
        this.currentUserProvider = currentUserProvider;
    }

    public MyReviewListResponse getMyReviews(Long cursor, Integer size) {
        Long userId = currentUserProvider.currentUserId();
        validateCursor(cursor);
        int pageSize = normalizeSize(size);
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);

        List<Review> reviews = cursor == null
                ? reviewRepository.findByUserIdAndDeletedFalseOrderByIdDesc(userId, pageRequest)
                : reviewRepository.findByUserIdAndDeletedFalseAndIdLessThanOrderByIdDesc(
                        userId, cursor, pageRequest);

        boolean hasNext = reviews.size() > pageSize;
        List<Review> pageContent = hasNext
                ? new ArrayList<>(reviews.subList(0, pageSize))
                : reviews;
        Long nextCursor = hasNext ? pageContent.getLast().getId() : null;

        List<Long> reservationIds = pageContent.stream()
                .map(Review::getReservationId)
                .distinct()
                .toList();
        Map<Long, ReservationReviewInfo> reservationsById = reservationPort.findReviewInfos(reservationIds)
                .stream()
                .collect(Collectors.toMap(ReservationReviewInfo::id, Function.identity()));
        Map<Long, RestaurantInfo> restaurantsById = restaurantPort.findSummaries(
                        pageContent.stream()
                                .map(Review::getRestaurantId)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(RestaurantInfo::id, Function.identity()));

        List<MyReviewSummaryResponse> content = pageContent.stream()
                .map(review -> toSummary(
                        review,
                        resolveReservation(reservationsById, review.getReservationId()),
                        requiredRestaurant(restaurantsById, review.getRestaurantId())))
                .toList();
        return new MyReviewListResponse(content, nextCursor, hasNext);
    }

    public MyReviewDetailResponse getMyReview(Long reviewId) {
        Long userId = currentUserProvider.currentUserId();
        Review review = getOwnedActiveReview(reviewId, userId);
        ReservationReviewInfo reservation = reservationPort
                .getReviewInfoByIdAndUserId(review.getReservationId(), userId);
        RestaurantInfo restaurant = restaurantPort.findSummaryById(review.getRestaurantId())
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND));
        String reviewerNickname = userPort.findById(userId)
                .map(UserInfo::nickname)
                .orElse(WITHDRAWN_REVIEWER_NICKNAME);

        return new MyReviewDetailResponse(
                review.getId(),
                restaurant.id(),
                restaurant.name(),
                restaurant.imageUrl(),
                reservation.reservedAt(),
                reservation.adultCount(),
                reservation.childCount(),
                reviewerNickname,
                review.getRating(),
                review.getContent(),
                keywordLabels(review),
                imageUrls(review),
                review.getCreatedAt()
        );
    }

    public MyReviewCountResponse getMyReviewCount() {
        Long userId = currentUserProvider.currentUserId();
        return new MyReviewCountResponse(reviewRepository.countByUserIdAndDeletedFalse(userId));
    }

    @Transactional
    public void deleteMyReview(Long reviewId) {
        Long userId = currentUserProvider.currentUserId();
        Review review = getOwnedActiveReview(reviewId, userId);
        int deletedCount = reviewRepository.softDeleteByIdAndUserId(reviewId, userId);
        if (deletedCount == 0) {
            throw new BusinessException(ReviewErrorCode.NOT_FOUND);
        }
        restaurantPort.decreaseReviewStatistics(review.getRestaurantId(), review.getRating());
    }

    private Review getOwnedActiveReview(Long reviewId, Long userId) {
        return reviewRepository.findByIdAndUserIdAndDeletedFalse(reviewId, userId)
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.NOT_FOUND));
    }

    private MyReviewSummaryResponse toSummary(
            Review review,
            ReservationReviewInfo reservation,
            RestaurantInfo restaurant
    ) {
        return new MyReviewSummaryResponse(
                review.getId(),
                restaurant.id(),
                restaurant.name(),
                restaurant.imageUrl(),
                reservation == null ? null : reservation.reservedAt(),
                reservation == null ? null : reservation.adultCount(),
                reservation == null ? null : reservation.childCount(),
                review.getRating(),
                review.getContent(),
                keywordLabels(review),
                review.getCreatedAt()
        );
    }

    private List<String> keywordLabels(Review review) {
        return review.getKeywords().stream()
                .map(ReviewKeyword::labelOfStoredValue)
                .toList();
    }

    private List<String> imageUrls(Review review) {
        return review.getImages().stream()
                .map(image -> fileStorage.resolveFileUrl(image.getFileKey()))
                .toList();
    }

    private ReservationReviewInfo resolveReservation(
            Map<Long, ReservationReviewInfo> reservationsById,
            Long reservationId
    ) {
        ReservationReviewInfo reservation = reservationsById.get(reservationId);
        if (reservation == null) {
            throw new IllegalStateException("리뷰에 연결된 예약 정보를 찾을 수 없습니다.");
        }
        return reservation;
    }

    private RestaurantInfo requiredRestaurant(Map<Long, RestaurantInfo> restaurantsById, Long restaurantId) {
        RestaurantInfo restaurant = restaurantsById.get(restaurantId);
        if (restaurant == null) {
            throw new IllegalStateException("리뷰에 연결된 식당 정보를 찾을 수 없습니다.");
        }
        return restaurant;
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return size;
    }

    private void validateCursor(Long cursor) {
        if (cursor != null && cursor < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }
}
