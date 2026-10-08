package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.service.RestaurantLocationService;
import org.sopt.hashi.restaurant.internal.map.LocationRetentionReader.Candidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationRetentionTransactions {
    private final RestaurantRepository restaurants;
    private final LocationRetentionReader reader;

    private final RestaurantLocationJobRepository jobs;
    private final RestaurantLocationService locations;

    public LocationRetentionTransactions(RestaurantRepository restaurants, LocationRetentionReader reader,
                                         RestaurantLocationJobRepository jobs, RestaurantLocationService locations) {
        this.restaurants = restaurants;
        this.reader = reader;
        this.jobs = jobs;
        this.locations = locations;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public boolean refresh(Candidate candidate, Duration ahead) {
        Restaurant restaurant = restaurants.findByIdForUpdate(candidate.restaurantId()).orElse(null);
        if (restaurant == null || restaurant.isDeleted() || restaurant.getLocation() == null) {
            return false;
        }
        var location = restaurant.getLocation();
        var now = reader.now();
        RestaurantLocationSource source = location.getSource();
        boolean current = location.getStatus() == RestaurantLocationStatus.READY
                && (source == RestaurantLocationSource.GOOGLE_GEOCODING
                    || source == RestaurantLocationSource.GOOGLE_PLACES)
                && location.getAddressRevision() == candidate.revision()
                && location.getRequestId().equals(candidate.requestId())
                && !location.getValidUntil().isAfter(now.plus(ahead))
                && Duration.between(location.getObtainedAt(), location.getValidUntil()).compareTo(ahead) > 0;
        if (!current || !jobs.findActiveForUpdate(restaurant.getId()).isEmpty()) {
            return false;
        }
        String googlePlaceId = location.getGooglePlaceId();
        restaurant.refreshLocation();
        if (source == RestaurantLocationSource.GOOGLE_PLACES) {
            jobs.save(RestaurantLocationJob.pendingDetails(restaurant, googlePlaceId, now));
        } else {
            locations.enqueue(restaurant);
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public boolean purge(Candidate candidate, Duration purgeAhead) {
        Restaurant restaurant = restaurants.findByIdForUpdate(candidate.restaurantId()).orElse(null);
        return restaurant != null && restaurant.purgeGoogleLocation(candidate.revision(), candidate.requestId(),
                candidate.obtainedAt(), candidate.validUntil(), reader.now().plus(purgeAhead));
    }
}
