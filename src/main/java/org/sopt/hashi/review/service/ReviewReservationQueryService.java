package org.sopt.hashi.review.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationReviewInfo;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.sopt.hashi.review.domain.ReviewStatusFilter;
import org.sopt.hashi.review.domain.VisitedReservationSort;
import org.sopt.hashi.review.dto.ReviewContextResponse;
import org.sopt.hashi.review.dto.ReviewUnavailableReason;
import org.sopt.hashi.review.dto.VisitedReservationListResponse;
import org.sopt.hashi.review.dto.VisitedReservationListResponse.VisitedReservationResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReviewReservationQueryService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewRepository reviewRepository;
    private final ReservationPort reservationPort;
    private final RestaurantPort restaurantPort;
    private final PointPort pointPort;
    private final CurrentUserProvider currentUserProvider;

    public ReviewReservationQueryService(
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

    public ReviewContextResponse getContext(Long reservationId) {
        Long userId = currentUserProvider.currentUserId();
        ReservationReviewInfo reservation = reservationPort
                .getReviewInfoByIdAndUserId(reservationId, userId);

        RestaurantDisplay restaurant = findRestaurantDisplay(reservation);
        boolean reviewed = reservation.supportsReview()
                && reservation.reservationStatus() == ReservationStatus.VISITED
                && reviewRepository.existsByReservationIdAndActiveTrue(reservation.id());
        ReviewUnavailableReason unavailableReason = unavailableReason(reservation, reviewed);

        return new ReviewContextResponse(
                reservation.id(),
                restaurant.id(),
                restaurant.name(),
                restaurant.thumbnailUrl(),
                reservation.reservedAt(),
                reservation.adultCount(),
                reservation.teenCount(),
                reservation.childCount(),
                unavailableReason == null,
                unavailableReason,
                ReviewContextResponse.keywordOptions()
        );
    }

    public VisitedReservationListResponse getVisitedReservations(
            String reviewStatusValue,
            Long restaurantId,
            String sortValue,
            Long cursor,
            Integer size
    ) {
        Long userId = currentUserProvider.currentUserId();
        ReviewStatusFilter reviewStatus = parseReviewStatus(reviewStatusValue);
        VisitedReservationSort sort = parseSort(sortValue);
        int pageSize = normalizeSize(size);
        validateRestaurantFilter(restaurantId);

        List<ReservationReviewInfo> candidates = reservationPort.findVisitedReviewInfos(userId).stream()
                .filter(reservation -> restaurantId == null || restaurantId.equals(reservation.restaurantId()))
                .toList();
        if (candidates.isEmpty()) {
            if (cursor != null) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }
            return emptyResponse();
        }

        Map<Long, Review> reviewByReservationId = findReviewsByReservationId(candidates);
        List<ReservationReviewInfo> filteredReservations = candidates.stream()
                .filter(reservation -> matchesReviewStatus(
                        reviewStatus,
                        reservation,
                        reviewByReservationId.containsKey(reservation.id())))
                .sorted(comparator(sort))
                .toList();

        long totalCount = filteredReservations.size();
        List<ReservationReviewInfo> cursorApplied = applyCursor(filteredReservations, cursor);
        List<ReservationReviewInfo> page = cursorApplied.stream()
                .limit((long) pageSize + 1)
                .toList();
        boolean hasNext = page.size() > pageSize;
        List<ReservationReviewInfo> contentReservations = hasNext
                ? new ArrayList<>(page.subList(0, pageSize))
                : page;
        Long nextCursor = hasNext && !contentReservations.isEmpty()
                ? contentReservations.getLast().id()
                : null;

        Map<Long, RestaurantInfo> restaurantById = findRestaurants(contentReservations);
        Map<Long, Long> earnedPointByReservationId = findEarnedPoints(
                contentReservations,
                reviewByReservationId);
        List<VisitedReservationResponse> content = contentReservations.stream()
                .map(reservation -> toVisitedReservationResponse(
                        reservation,
                        restaurantById,
                        reviewByReservationId,
                        earnedPointByReservationId))
                .toList();

        return new VisitedReservationListResponse(content, totalCount, nextCursor, hasNext);
    }

    private RestaurantDisplay findRestaurantDisplay(ReservationReviewInfo reservation) {
        if (!reservation.supportsReview()) {
            return new RestaurantDisplay(null, reservation.restaurantName(), null);
        }
        RestaurantInfo restaurant = restaurantPort.findSummaryById(reservation.restaurantId())
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND));
        return new RestaurantDisplay(restaurant.id(), restaurant.name(), restaurant.imageUrl());
    }

    private ReviewUnavailableReason unavailableReason(
            ReservationReviewInfo reservation,
            boolean reviewed
    ) {
        if (!reservation.supportsReview()) {
            return ReviewUnavailableReason.UNSUPPORTED_RESERVATION_TYPE;
        }
        if (reservation.reservationStatus() != ReservationStatus.VISITED) {
            return ReviewUnavailableReason.NOT_VISITED;
        }
        if (reviewed) {
            return ReviewUnavailableReason.ALREADY_REVIEWED;
        }
        return null;
    }

    private ReviewStatusFilter parseReviewStatus(String value) {
        return ReviewStatusFilter.from(value)
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.UNSUPPORTED_STATUS));
    }

    private VisitedReservationSort parseSort(String value) {
        return VisitedReservationSort.from(value)
                .orElseThrow(() -> new BusinessException(ReviewErrorCode.UNSUPPORTED_SORT));
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

    private void validateRestaurantFilter(Long restaurantId) {
        if (restaurantId != null && !restaurantPort.existsById(restaurantId)) {
            throw new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND);
        }
    }

    private Map<Long, Review> findReviewsByReservationId(
            Collection<ReservationReviewInfo> reservations
    ) {
        List<Long> reservationIds = reservations.stream()
                .map(ReservationReviewInfo::id)
                .toList();
        return reviewRepository.findByReservationIdInAndActiveTrue(reservationIds).stream()
                .collect(Collectors.toMap(Review::getReservationId, Function.identity()));
    }

    private boolean matchesReviewStatus(
            ReviewStatusFilter status,
            ReservationReviewInfo reservation,
            boolean reviewed
    ) {
        return switch (status) {
            case ALL -> true;
            case UNREVIEWED -> reservation.supportsReview() && !reviewed;
            case REVIEWED -> reviewed;
        };
    }

    private Comparator<ReservationReviewInfo> comparator(VisitedReservationSort sort) {
        Comparator<ReservationReviewInfo> oldestFirst = Comparator
                .comparing(ReservationReviewInfo::reservedAt)
                .thenComparing(ReservationReviewInfo::id);
        return sort == VisitedReservationSort.OLDEST
                ? oldestFirst
                : oldestFirst.reversed();
    }

    private List<ReservationReviewInfo> applyCursor(
            List<ReservationReviewInfo> reservations,
            Long cursor
    ) {
        if (cursor == null) {
            return reservations;
        }
        for (int index = 0; index < reservations.size(); index++) {
            if (reservations.get(index).id().equals(cursor)) {
                return reservations.subList(index + 1, reservations.size());
            }
        }
        throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }

    private Map<Long, RestaurantInfo> findRestaurants(
            Collection<ReservationReviewInfo> reservations
    ) {
        if (reservations.isEmpty()) {
            return Map.of();
        }
        List<Long> restaurantIds = reservations.stream()
                .map(ReservationReviewInfo::restaurantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (restaurantIds.isEmpty()) {
            return Map.of();
        }
        return restaurantPort.findSummaries(restaurantIds).stream()
                .collect(Collectors.toMap(RestaurantInfo::id, Function.identity()));
    }

    private Map<Long, Long> findEarnedPoints(
            Collection<ReservationReviewInfo> reservations,
            Map<Long, Review> reviewByReservationId
    ) {
        List<Long> reviewedReservationIds = reservations.stream()
                .map(ReservationReviewInfo::id)
                .filter(reviewByReservationId::containsKey)
                .toList();
        if (reviewedReservationIds.isEmpty()) {
            return Map.of();
        }
        return pointPort.findEarnedAmounts(PointSourceType.REVIEW, reviewedReservationIds);
    }

    private VisitedReservationResponse toVisitedReservationResponse(
            ReservationReviewInfo reservation,
            Map<Long, RestaurantInfo> restaurantById,
            Map<Long, Review> reviewByReservationId,
            Map<Long, Long> earnedPointByReservationId
    ) {
        RestaurantDisplay restaurant = toRestaurantDisplay(reservation, restaurantById);
        Review review = reviewByReservationId.get(reservation.id());
        boolean reviewed = review != null;
        boolean reviewable = reservation.supportsReview() && !reviewed;
        ReviewUnavailableReason unavailableReason = unavailableReason(reservation, reviewed);
        return new VisitedReservationResponse(
                reservation.id(),
                restaurant.id(),
                restaurant.name(),
                restaurant.thumbnailUrl(),
                reservation.reservedAt(),
                reservation.adultCount(),
                reservation.teenCount(),
                reservation.childCount(),
                reviewed,
                reviewable,
                unavailableReason,
                reviewed ? review.getId() : null,
                reviewed ? review.getRating() : null,
                reviewed ? earnedPointByReservationId.getOrDefault(reservation.id(), 0L) : null
        );
    }

    private RestaurantDisplay toRestaurantDisplay(
            ReservationReviewInfo reservation,
            Map<Long, RestaurantInfo> restaurantById
    ) {
        if (!reservation.supportsReview()) {
            return new RestaurantDisplay(null, reservation.restaurantName(), null);
        }
        RestaurantInfo restaurant = restaurantById.get(reservation.restaurantId());
        if (restaurant == null) {
            throw new IllegalStateException(
                    "예약(id=%d)에 연결된 식당(id=%d)을 찾을 수 없습니다."
                            .formatted(reservation.id(), reservation.restaurantId()));
        }
        return new RestaurantDisplay(restaurant.id(), restaurant.name(), restaurant.imageUrl());
    }

    private VisitedReservationListResponse emptyResponse() {
        return new VisitedReservationListResponse(List.of(), 0L, null, false);
    }

    private record RestaurantDisplay(Long id, String name, String thumbnailUrl) {
    }
}
