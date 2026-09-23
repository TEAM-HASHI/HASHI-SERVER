package org.sopt.hashi.review.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.domain.ReviewKeyword;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.dto.MyReviewCountResponse;
import org.sopt.hashi.review.dto.MyReviewDetailResponse;
import org.sopt.hashi.review.dto.MyReviewListResponse;
import org.sopt.hashi.review.dto.MyReviewListResponse.MyReviewSummaryResponse;
import org.sopt.hashi.review.dto.ReviewContextResponse;
import org.sopt.hashi.review.dto.ReviewEditContextResponse;
import org.sopt.hashi.review.dto.ReviewEditContextResponse.ReviewEditImageResponse;
import org.sopt.hashi.review.dto.UpdateReviewRequest;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.sopt.hashi.user.UserInfo;
import org.sopt.hashi.user.UserPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class MyReviewService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String WITHDRAWN_REVIEWER_NICKNAME = "탈퇴한 회원";
    private static final String REVIEW_IMAGE_PREFIX = "uploads/reviews/";

    private final ReviewRepository reviewRepository;
    private final ReservationPort reservationPort;
    private final RestaurantPort restaurantPort;
    private final MediaPort mediaPort;
    private final UserPort userPort;
    private final FileStorage fileStorage;
    private final CurrentUserProvider currentUserProvider;

    public MyReviewService(
            ReviewRepository reviewRepository,
            ReservationPort reservationPort,
            RestaurantPort restaurantPort,
            MediaPort mediaPort,
            UserPort userPort,
            FileStorage fileStorage,
            CurrentUserProvider currentUserProvider
    ) {
        this.reviewRepository = reviewRepository;
        this.reservationPort = reservationPort;
        this.restaurantPort = restaurantPort;
        this.mediaPort = mediaPort;
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
        MediaProjection mediaProjection = loadThumbnailProjection(
                restaurantsById.values().stream()
                        .map(RestaurantInfo::thumbnailImageReference)
                        .toList());

        List<MyReviewSummaryResponse> content = pageContent.stream()
                .map(review -> toSummary(
                        review,
                        resolveReservation(reservationsById, review.getReservationId()),
                        requiredRestaurant(restaurantsById, review.getRestaurantId()),
                        mediaProjection))
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
        MediaProjection mediaProjection = loadThumbnailProjection(
                referenceList(restaurant.thumbnailImageReference()));
        ProjectedImage thumbnail = projectThumbnail(
                restaurant.thumbnailImageReference(), mediaProjection);

        return new MyReviewDetailResponse(
                review.getId(),
                restaurant.id(),
                restaurant.name(),
                thumbnail.url(),
                thumbnail.image(),
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

    public ReviewEditContextResponse getMyReviewEditContext(Long reviewId) {
        Long userId = currentUserProvider.currentUserId();
        Review review = getOwnedActiveReview(reviewId, userId);
        ReservationReviewInfo reservation = reservationPort
                .getReviewInfoByIdAndUserId(review.getReservationId(), userId);
        RestaurantInfo restaurant = restaurantPort.findSummaryById(review.getRestaurantId())
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND));
        MediaProjection mediaProjection = loadThumbnailProjection(
                referenceList(restaurant.thumbnailImageReference()));
        ProjectedImage thumbnail = projectThumbnail(
                restaurant.thumbnailImageReference(), mediaProjection);

        return new ReviewEditContextResponse(
                review.getId(),
                reservation.id(),
                restaurant.id(),
                restaurant.name(),
                thumbnail.url(),
                thumbnail.image(),
                reservation.reservedAt(),
                reservation.adultCount(),
                reservation.teenCount(),
                reservation.childCount(),
                review.getRating(),
                review.getContent(),
                selectedKeywordCodes(review),
                ReviewContextResponse.keywordOptions(),
                editImages(review)
        );
    }

    public MyReviewCountResponse getMyReviewCount() {
        Long userId = currentUserProvider.currentUserId();
        return new MyReviewCountResponse(reviewRepository.countByUserIdAndDeletedFalse(userId));
    }

    @Transactional
    public void updateMyReview(Long reviewId, UpdateReviewRequest request) {
        Long userId = currentUserProvider.currentUserId();
        List<String> keywordCodes = parseKeywordCodes(request.keywordCodes());
        List<String> imageFileKeys = validateImageFileKeys(request.imageFileKeys());
        Review review = getOwnedActiveReviewForUpdate(reviewId, userId);
        int oldRating = review.getRating();
        List<ReviewImage> images = parseImages(imageFileKeys, review);

        review.updateRatingAndContent(request.rating(), request.content());
        replaceEditableCollectionsWithFlush(review, keywordCodes, images);

        if (oldRating != request.rating()) {
            restaurantPort.updateReviewRatingStatistics(
                    review.getRestaurantId(), oldRating, request.rating());
        }
        log.info("리뷰 수정. reviewId={}, restaurantId={}, oldRating={}, newRating={}",
                reviewId, review.getRestaurantId(), oldRating, request.rating());
    }

    @Transactional
    public void deleteMyReview(Long reviewId) {
        Long userId = currentUserProvider.currentUserId();
        Review review = getOwnedActiveReviewForUpdate(reviewId, userId);
        Long restaurantId = review.getRestaurantId();
        int rating = review.getRating();

        review.softDelete();
        reviewRepository.flush();
        restaurantPort.decreaseReviewStatistics(restaurantId, rating);
        // 삭제는 식당 통계 감소에 연쇄되고, 재작성 시 보상 미지급 판정의 근거가 되는 전이라 남긴다
        log.info("리뷰 삭제. reviewId={}, restaurantId={}", reviewId, restaurantId);
    }

    private Review getOwnedActiveReview(Long reviewId, Long userId) {
        return reviewRepository.findByIdAndUserIdAndDeletedFalse(reviewId, userId)
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.NOT_FOUND));
    }

    private Review getOwnedActiveReviewForUpdate(Long reviewId, Long userId) {
        return reviewRepository.findOwnedActiveForUpdate(reviewId, userId)
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.NOT_FOUND));
    }

    private MyReviewSummaryResponse toSummary(
            Review review,
            ReservationReviewInfo reservation,
            RestaurantInfo restaurant,
            MediaProjection mediaProjection
    ) {
        ProjectedImage thumbnail = projectThumbnail(
                restaurant.thumbnailImageReference(), mediaProjection);
        return new MyReviewSummaryResponse(
                review.getId(),
                restaurant.id(),
                restaurant.name(),
                thumbnail.url(),
                thumbnail.image(),
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

    private List<String> selectedKeywordCodes(Review review) {
        return review.getKeywords().stream()
                .map(storedValue -> ReviewKeyword.fromStoredValue(storedValue)
                        .orElseThrow(() -> new BusinessException(
                                ReviewErrorCode.UNSUPPORTED_KEYWORD)))
                .map(Enum::name)
                .toList();
    }

    private List<String> imageUrls(Review review) {
        return review.getImages().stream()
                .map(image -> fileStorage.resolveFileUrl(image.getFileKey()))
                .toList();
    }

    private List<ReviewEditImageResponse> editImages(Review review) {
        return review.getImages().stream()
                .map(image -> new ReviewEditImageResponse(
                        image.getFileKey(),
                        fileStorage.resolveFileUrl(image.getFileKey()),
                        image.getDisplayOrder()))
                .toList();
    }

    private List<String> parseKeywordCodes(List<String> requestedCodes) {
        boolean invalidSize = requestedCodes == null
                || requestedCodes.isEmpty()
                || requestedCodes.size() > 3;
        if (invalidSize || new HashSet<>(requestedCodes).size() != requestedCodes.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return requestedCodes.stream()
                .map(code -> ReviewKeyword.fromCode(code)
                        .orElseThrow(() -> new BusinessException(ReviewErrorCode.UNSUPPORTED_KEYWORD)))
                .map(Enum::name)
                .toList();
    }

    private List<String> validateImageFileKeys(List<String> imageFileKeys) {
        if (imageFileKeys == null
                || imageFileKeys.size() > 10
                || imageFileKeys.stream().anyMatch(
                        fileKey -> fileKey == null || fileKey.length() > 500)
                || new HashSet<>(imageFileKeys).size() != imageFileKeys.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return imageFileKeys;
    }

    private List<ReviewImage> parseImages(List<String> imageFileKeys, Review review) {
        Set<String> existingFileKeys = review.getImages().stream()
                .map(ReviewImage::getFileKey)
                .collect(Collectors.toCollection(HashSet::new));
        return IntStream.range(0, imageFileKeys.size())
                .mapToObj(index -> ReviewImage.create(
                        validateReviewImageKey(imageFileKeys.get(index), existingFileKeys),
                        index))
                .toList();
    }

    private String validateReviewImageKey(String fileKey, Set<String> existingFileKeys) {
        boolean invalidNewKey = !existingFileKeys.contains(fileKey)
                && (!fileKey.startsWith(REVIEW_IMAGE_PREFIX)
                || fileKey.contains("..")
                || fileKey.contains("\\"));
        if (invalidNewKey) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return fileKey;
    }

    // review_keyword PK와 review_image 순서 유니크 제약 때문에 delete를 먼저 flush한 뒤 재구성한다.
    private void replaceEditableCollectionsWithFlush(
            Review review,
            List<String> keywordCodes,
            List<ReviewImage> images
    ) {
        review.replaceKeywords(List.of());
        review.replaceImages(List.of());
        reviewRepository.flush();
        review.replaceKeywords(keywordCodes);
        review.replaceImages(images);
        reviewRepository.flush();
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

    private MediaProjection loadThumbnailProjection(Collection<ImageReference> references) {
        List<MediaImageRequest> requests = references.stream()
                .filter(Objects::nonNull)
                .map(ImageReference::assetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(
                        assetId, MediaImageRole.RESTAURANT_THUMBNAIL))
                .distinct()
                .toList();
        if (requests.isEmpty()) {
            return MediaProjection.empty();
        }
        return new MediaProjection(mediaPort.findImages(requests));
    }

    private List<ImageReference> referenceList(ImageReference reference) {
        return reference == null ? List.of() : List.of(reference);
    }

    private ProjectedImage projectThumbnail(
            ImageReference reference,
            MediaProjection mediaProjection
    ) {
        MediaImage mediaImage = reference == null || reference.assetId() == null
                ? null : mediaProjection.find(reference);
        MediaImageSelection selection = MediaImageSelection.from(reference, mediaImage);
        return new ProjectedImage(selection.url(), selection.image());
    }

    private record MediaProjection(Map<MediaImageRequest, MediaImage> images) {

        private MediaProjection {
            images = Map.copyOf(images);
        }

        private static MediaProjection empty() {
            return new MediaProjection(Map.of());
        }

        private MediaImage find(ImageReference reference) {
            return images.get(new MediaImageRequest(
                    reference.assetId(), MediaImageRole.RESTAURANT_THUMBNAIL));
        }
    }

    private record ProjectedImage(String url, MediaImage image) {

        private static ProjectedImage empty() {
            return new ProjectedImage(null, null);
        }
    }
}
