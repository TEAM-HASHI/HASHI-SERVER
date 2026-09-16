package org.sopt.hashi.review.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewKeyword;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.domain.ReviewRepository.RatingCount;
import org.sopt.hashi.review.domain.ReviewSort;
import org.sopt.hashi.review.dto.RestaurantReviewResponse;
import org.sopt.hashi.review.dto.RestaurantReviewResponse.RatingDistributionResponse;
import org.sopt.hashi.review.dto.RestaurantReviewResponse.ReviewSummaryResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserPort;
import org.sopt.hashi.user.UserProfileInfo;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReviewService {

    private static final int DEFAULT_PAGE_SIZE = 5;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String WITHDRAWN_REVIEWER_NICKNAME = "탈퇴한 회원";

    private final ReviewRepository reviewRepository;
    private final RestaurantPort restaurantPort;
    private final UserPort userPort;
    private final FileStorage fileStorage;
    private final MediaPort mediaPort;

    public ReviewService(
            ReviewRepository reviewRepository,
            RestaurantPort restaurantPort,
            UserPort userPort,
            FileStorage fileStorage,
            MediaPort mediaPort
    ) {
        this.reviewRepository = reviewRepository;
        this.restaurantPort = restaurantPort;
        this.userPort = userPort;
        this.fileStorage = fileStorage;
        this.mediaPort = mediaPort;
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
        List<Long> reviewerIds = pageContent.stream()
                .map(Review::getUserId)
                .distinct()
                .toList();
        Map<Long, UserProfileInfo> reviewerProfiles = userPort.findProfiles(reviewerIds).stream()
                .collect(Collectors.toMap(UserProfileInfo::id, Function.identity()));
        MediaProjection profileProjection = loadProfileProjection(reviewerProfiles.values().stream()
                .map(UserProfileInfo::profileImageReference)
                .toList());

        return new RestaurantReviewResponse(
                restaurantId,
                averageRating(restaurantId),
                reviewRepository.countByRestaurantIdAndDeletedFalse(restaurantId),
                ratingDistribution(restaurantId),
                pageContent.stream()
                        .map(review -> toReviewSummaryResponse(
                                review, reviewerProfiles, profileProjection))
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
        return reviewRepository.findByIdAndRestaurantIdAndDeletedFalse(cursor, restaurantId)
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

    private ReviewSummaryResponse toReviewSummaryResponse(
            Review review,
            Map<Long, UserProfileInfo> reviewerProfiles,
            MediaProjection profileProjection
    ) {
        UserProfileInfo reviewerProfile = reviewerProfiles.get(review.getUserId());
        ProjectedImage profileImage = reviewerProfile == null
                ? ProjectedImage.empty()
                : projectProfileImage(
                        reviewerProfile.profileImageReference(), profileProjection);
        return new ReviewSummaryResponse(
                review.getId(),
                reviewerProfile == null ? WITHDRAWN_REVIEWER_NICKNAME : reviewerProfile.nickname(),
                profileImage.url(),
                profileImage.image(),
                review.getRating(),
                review.getContent(),
                review.getKeywords().stream()
                        .map(ReviewKeyword::labelOfStoredValue)
                        .toList(),
                review.getImages().stream()
                        .map(image -> fileStorage.resolveFileUrl(image.getFileKey()))
                        .toList(),
                review.getImages().size(),
                review.getCreatedAt()
        );
    }

    private MediaProjection loadProfileProjection(List<ImageReference> references) {
        List<MediaImageRequest> requests = references.stream()
                .filter(Objects::nonNull)
                .map(ImageReference::assetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, MediaImageRole.PROFILE_AVATAR))
                .distinct()
                .toList();
        return requests.isEmpty()
                ? MediaProjection.empty()
                : new MediaProjection(mediaPort.findImages(requests));
    }

    private ProjectedImage projectProfileImage(
            ImageReference reference,
            MediaProjection projection
    ) {
        if (reference == null) {
            return ProjectedImage.empty();
        }
        if (reference.assetId() == null) {
            return new ProjectedImage(reference.legacyUrl(), null);
        }
        MediaImage image = projection.find(reference.assetId());
        String url = image != null && image.status() == MediaImageStatus.READY
                ? image.defaultSource().url()
                : null;
        return new ProjectedImage(url, image);
    }

    private record MediaProjection(Map<MediaImageRequest, MediaImage> images) {

        private MediaProjection {
            images = Map.copyOf(images);
        }

        private static MediaProjection empty() {
            return new MediaProjection(Map.of());
        }

        private MediaImage find(UUID assetId) {
            return images.get(new MediaImageRequest(assetId, MediaImageRole.PROFILE_AVATAR));
        }
    }

    private record ProjectedImage(String url, MediaImage image) {

        private static ProjectedImage empty() {
            return new ProjectedImage(null, null);
        }
    }
}
