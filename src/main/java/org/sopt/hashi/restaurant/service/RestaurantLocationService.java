package org.sopt.hashi.restaurant.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.RestaurantLocationReviewInfo;
import org.sopt.hashi.restaurant.RestaurantLocationReviewPage;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantLocation;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantRepository.RestaurantLocationReviewProjection;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestaurantLocationService {
    private static final int MAX_REVIEW_PAGE_SIZE = 100;
    private static final Set<String> REVIEW_STATUSES = Set.of(
            "UNRESOLVED", "PENDING", "READY", "RETRY_WAIT", "REVIEW_REQUIRED", "FAILED");
    private static final Set<String> LOCATION_SOURCES = Set.of("GOOGLE_GEOCODING", "GOOGLE_PLACES", "ADMIN");
    private final RestaurantRepository restaurants;
    private final RestaurantLocationJobRepository jobs;
    private final Clock clock;

    public RestaurantLocationService(RestaurantRepository restaurants, RestaurantLocationJobRepository jobs,
                                     @Qualifier("japanClock") Clock clock) {
        this.restaurants = restaurants;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** 호출자는 READ_COMMITTED transaction에서 식당을 먼저 잠근다. 신규 식당은 아직 외부에서 보이지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Restaurant restaurant) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        supersede(restaurant.getId(), now);
        restaurant.requestLocationResolution();
        jobs.save(RestaurantLocationJob.pending(restaurant, now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Restaurant restaurant) {
        supersede(restaurant.getId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public RestaurantLocationInfo get(Long restaurantId) {
        return info(restaurants.findByIdAndDeletedFalse(restaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RestaurantLocationReviewPage findReviews(String status, String source, int page, int size) {
        validateReviewQuery(status, source, page, size);
        Page<RestaurantLocationReviewProjection> rows = restaurants.findLocationReviews(
                status, source, PageRequest.of(page, size));
        return new RestaurantLocationReviewPage(
                rows.getContent().stream().map(this::toReviewInfo).toList(),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements(),
                rows.getTotalPages());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RestaurantLocationInfo retry(Long restaurantId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        Restaurant restaurant = restaurants.findByIdForUpdate(restaurantId)
                .filter(value -> !value.isDeleted())
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
        RestaurantLocation location = restaurant.getLocation();
        long revision = location == null ? 0 : location.getAddressRevision();
        if (revision != expectedRevision || (location != null && location.getStatus() == RestaurantLocationStatus.READY)) {
            throw new BusinessException(RestaurantErrorCode.LOCATION_RETRY_CONFLICT);
        }
        if (location == null) {
            enqueue(restaurant);
            return info(restaurant);
        }
        RestaurantLocationJob current = jobs.findCurrentForUpdate(restaurantId, location.getRequestId())
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.LOCATION_RETRY_CONFLICT));
        if (!current.isCurrent(restaurant)) {
            throw new BusinessException(RestaurantErrorCode.LOCATION_RETRY_CONFLICT);
        }
        if (location.getStatus() != RestaurantLocationStatus.PENDING) {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            supersede(restaurant.getId(), now);
            restaurant.requestLocationResolution();
            RestaurantLocationJob retry = current.getOperation() == RestaurantLocationJob.Operation.PLACE_DETAILS
                    ? RestaurantLocationJob.pendingDetails(restaurant, requirePlaceId(current), now)
                    : RestaurantLocationJob.pending(restaurant, now);
            jobs.save(retry);
        }
        return info(restaurant);
    }

    private RestaurantLocationInfo info(Restaurant restaurant) {
        RestaurantLocation location = restaurant.getLocation();
        if (location == null) {
            return new RestaurantLocationInfo(restaurant.getId(), "UNRESOLVED", 0, null, null,
                    null, 0, null, null, true);
        }
        RestaurantLocationJob job = jobs.findByRestaurantIdAndRequestId(restaurant.getId(), location.getRequestId())
                .orElse(null);
        boolean canRetry = location.getStatus() != RestaurantLocationStatus.PENDING
                && location.getStatus() != RestaurantLocationStatus.READY;
        return new RestaurantLocationInfo(restaurant.getId(), location.getStatus().name(), location.getAddressRevision(),
                location.getSource() == null ? null : location.getSource().name(),
                job == null ? null : job.getOperation().name(),
                location.getValidUntil() == null ? null : location.getValidUntil().toInstant(ZoneOffset.UTC),
                job == null ? 0 : job.getAttempt(),
                location.getNextAttemptAt() == null ? null : location.getNextAttemptAt().toInstant(ZoneOffset.UTC),
                job == null ? null : job.getFailureCode(), canRetry);
    }

    private RestaurantLocationReviewInfo toReviewInfo(RestaurantLocationReviewProjection row) {
        boolean canRetry = !"PENDING".equals(row.getLocationStatus()) && !"READY".equals(row.getLocationStatus());
        return new RestaurantLocationReviewInfo(
                row.getRestaurantId(),
                row.getRestaurantName(),
                row.getAddress(),
                row.getGeocodingAddress(),
                row.getLocationStatus(),
                row.getLocationSource(),
                row.getVerificationMode(),
                row.getAddressRevision(),
                parseUtc(row.getValidUntilUtc()),
                row.getAttempt(),
                parseUtc(row.getNextAttemptAtUtc()),
                row.getFailureCode(),
                canRetry);
    }

    private void validateReviewQuery(String status, String source, int page, int size) {
        if (status == null || !REVIEW_STATUSES.contains(status)
                || (source != null && !LOCATION_SOURCES.contains(source))
                || page < 0
                || (long) page * size > Integer.MAX_VALUE
                || size < 1 || size > MAX_REVIEW_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private Instant parseUtc(String value) {
        return value == null ? null : LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
    }

    private void supersede(Long restaurantId, LocalDateTime now) {
        jobs.findActiveForUpdate(restaurantId).forEach(job -> job.supersede(now));
    }

    private String requirePlaceId(RestaurantLocationJob job) {
        if (job.getGooglePlaceId() == null) {
            throw new BusinessException(RestaurantErrorCode.LOCATION_RETRY_CONFLICT);
        }
        return job.getGooglePlaceId();
    }
}
