package org.sopt.hashi.restaurant.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantLocation;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestaurantLocationService {
    private final RestaurantRepository restaurants;
    private final RestaurantLocationJobRepository jobs;
    private final Clock clock;

    public RestaurantLocationService(RestaurantRepository restaurants, RestaurantLocationJobRepository jobs,
                                     @Qualifier("japanClock") Clock clock) {
        this.restaurants = restaurants;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** 호출자가 식당을 먼저 잠근다. 신규 식당은 아직 다른 transaction에서 보이지 않는다. */
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

    @Transactional
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
        if (location == null || location.getStatus() != RestaurantLocationStatus.PENDING) {
            enqueue(restaurant);
        }
        return info(restaurant);
    }

    private RestaurantLocationInfo info(Restaurant restaurant) {
        RestaurantLocation location = restaurant.getLocation();
        if (location == null) {
            return new RestaurantLocationInfo(restaurant.getId(), "UNRESOLVED", 0, null, 0, null, null, true);
        }
        RestaurantLocationJob job = jobs.findByRestaurantIdAndRequestId(restaurant.getId(), location.getRequestId())
                .orElse(null);
        boolean canRetry = location.getStatus() != RestaurantLocationStatus.PENDING
                && location.getStatus() != RestaurantLocationStatus.READY;
        return new RestaurantLocationInfo(restaurant.getId(), location.getStatus().name(), location.getAddressRevision(),
                location.getValidUntil() == null ? null : location.getValidUntil().toInstant(ZoneOffset.UTC),
                job == null ? 0 : job.getAttempt(),
                location.getNextAttemptAt() == null ? null : location.getNextAttemptAt().toInstant(ZoneOffset.UTC),
                job == null ? null : job.getFailureCode(), canRetry);
    }

    private void supersede(Long restaurantId, LocalDateTime now) {
        jobs.findActiveForUpdate(restaurantId).forEach(job -> job.supersede(now));
    }
}
