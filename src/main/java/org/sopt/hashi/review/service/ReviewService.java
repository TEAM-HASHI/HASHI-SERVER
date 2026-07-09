package org.sopt.hashi.review.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.domain.ReviewRepository.RatingCount;
import org.sopt.hashi.review.domain.ReviewSort;
import org.sopt.hashi.review.dto.RestaurantReviewResponse;
import org.sopt.hashi.review.dto.RestaurantReviewResponse.RatingDistributionResponse;
import org.sopt.hashi.review.dto.RestaurantReviewResponse.ReviewSummaryResponse;
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
public class ReviewService {

    private static final int DEFAULT_PAGE_SIZE = 5;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String WITHDRAWN_USER_NICKNAME = "탈퇴한 회원";

    private final ReviewRepository reviewRepository;
    private final RestaurantPort restaurantPort;
    private final UserPort userPort;
    private final FileStorage fileStorage;

    public ReviewService(
            ReviewRepository reviewRepository,
            RestaurantPort restaurantPort,
            UserPort userPort,
            FileStorage fileStorage
    ) {
        this.reviewRepository = reviewRepository;
        this.restaurantPort = restaurantPort;
        this.userPort = userPort;
        this.fileStorage = fileStorage;
    }

    public RestaurantReviewResponse getRestaurantReviews(
            Long restaurantId,
            String sortValue,
            Long cursor,
            Integer size
    ) {
        validateRestaurantExists(restaurantId);
        ReviewSort sort = parseSort(sortValue);
        int pageSize = normalizeSize(size);
        Review cursorReview = findCursorReview(restaurantId, cursor);

        List<Review> reviews = findReviews(restaurantId, sort, cursorReview, pageSize);
        boolean hasNext = reviews.size() > pageSize;
        List<Review> pageContent = hasNext
                ? new ArrayList<>(reviews.subList(0, pageSize))
                : reviews;
        Long nextCursor = hasNext ? pageContent.getLast().getId() : null;
        Map<Long, String> writerNicknames = pageContent.stream()
                .map(Review::getWriterId)
                .distinct()
                .collect(Collectors.toMap(writerId -> writerId, this::writerNickname));

        return new RestaurantReviewResponse(
                restaurantId,
                averageRating(restaurantId),
                reviewRepository.countByRestaurantIdAndActiveTrue(restaurantId),
                ratingDistribution(restaurantId),
                pageContent.stream()
                        .map(review -> toReviewSummaryResponse(review, writerNicknames))
                        .toList(),
                nextCursor,
                hasNext
        );
    }

    private void validateRestaurantExists(Long restaurantId) {
        if (!restaurantPort.existsById(restaurantId)) {
            throw new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND);
        }
    }

    private ReviewSort parseSort(String value) {
        if (value == null || value.isBlank()) {
            return ReviewSort.LATEST;
        }
        return ReviewSort.from(value)
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.UNSUPPORTED_SORT));
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Review findCursorReview(Long restaurantId, Long cursor) {
        if (cursor == null) {
            return null;
        }
        return reviewRepository.findByIdAndRestaurantIdAndActiveTrue(cursor, restaurantId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT));
    }

    private List<Review> findReviews(Long restaurantId, ReviewSort sort, Review cursorReview, int pageSize) {
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
        return switch (sort) {
            case LATEST -> reviewRepository.findLatestPage(
                    restaurantId,
                    cursorReview == null ? null : cursorReview.getCreatedAt(),
                    cursorReview == null ? null : cursorReview.getId(),
                    pageRequest);
            case RATING_HIGH -> reviewRepository.findRatingHighPage(
                    restaurantId,
                    cursorReview == null ? null : cursorReview.getRating(),
                    cursorReview == null ? null : cursorReview.getId(),
                    pageRequest);
            case RATING_LOW -> reviewRepository.findRatingLowPage(
                    restaurantId,
                    cursorReview == null ? null : cursorReview.getRating(),
                    cursorReview == null ? null : cursorReview.getId(),
                    pageRequest);
        };
    }

    private double averageRating(Long restaurantId) {
        Double averageRating = reviewRepository.averageRatingByRestaurantId(restaurantId);
        if (averageRating == null) {
            return 0.0;
        }
        return Math.round(averageRating * 10.0) / 10.0;
    }

    private RatingDistributionResponse ratingDistribution(Long restaurantId) {
        Map<Integer, Long> ratingCounts = reviewRepository.countByRating(restaurantId).stream()
                .collect(Collectors.toMap(RatingCount::getRating, RatingCount::getCount));
        return new RatingDistributionResponse(
                ratingCounts.getOrDefault(5, 0L),
                ratingCounts.getOrDefault(4, 0L),
                ratingCounts.getOrDefault(3, 0L),
                ratingCounts.getOrDefault(2, 0L),
                ratingCounts.getOrDefault(1, 0L)
        );
    }

    private ReviewSummaryResponse toReviewSummaryResponse(Review review, Map<Long, String> writerNicknames) {
        return new ReviewSummaryResponse(
                review.getId(),
                writerNicknames.get(review.getWriterId()),
                review.getRating(),
                review.getContent(),
                List.copyOf(review.getKeywords()),
                review.getImages().stream()
                        .map(image -> fileStorage.resolveFileUrl(image.getFileKey()))
                        .toList(),
                review.getCreatedAt()
        );
    }

    private String writerNickname(Long writerId) {
        return userPort.findById(writerId)
                .map(UserInfo::nickname)
                .orElse(WITHDRAWN_USER_NICKNAME);
    }
}
