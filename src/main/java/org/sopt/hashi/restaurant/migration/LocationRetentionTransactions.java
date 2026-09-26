package org.sopt.hashi.restaurant.migration;

import java.time.Duration;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceReader.Candidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocationRetentionTransactions {
    private final RestaurantRepository restaurants;
    private final LocationMaintenanceReader reader;

    public LocationRetentionTransactions(RestaurantRepository restaurants, LocationMaintenanceReader reader) {
        this.restaurants = restaurants;
        this.reader = reader;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public boolean purge(Candidate candidate, Duration purgeAhead) {
        Restaurant restaurant = restaurants.findByIdForUpdate(candidate.restaurantId()).orElse(null);
        return restaurant != null && restaurant.purgeGoogleLocation(candidate.revision(), candidate.requestId(),
                candidate.obtainedAt(), candidate.validUntil(), reader.now().plus(purgeAhead));
    }
}
