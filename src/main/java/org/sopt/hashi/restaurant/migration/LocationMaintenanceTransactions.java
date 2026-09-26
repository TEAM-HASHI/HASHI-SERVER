package org.sopt.hashi.restaurant.migration;

import java.time.Duration;
import java.util.UUID;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantLocation;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceStore.Run;
import org.sopt.hashi.restaurant.migration.LocationMaintenanceStore.Status;
import org.sopt.hashi.restaurant.service.RestaurantLocationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One short commit per restaurant. Lock order: run -> restaurant -> job; no HTTP or budget lock. */
@Service
public class LocationMaintenanceTransactions {
    private final LocationMaintenanceStore store;
    private final LocationMaintenanceReader reader;
    private final RestaurantRepository restaurants;
    private final RestaurantLocationJobRepository jobs;
    private final RestaurantLocationService locations;

    public LocationMaintenanceTransactions(LocationMaintenanceStore store, LocationMaintenanceReader reader,
                                           RestaurantRepository restaurants, RestaurantLocationJobRepository jobs,
                                           RestaurantLocationService locations) {
        this.store = store;
        this.reader = reader;
        this.restaurants = restaurants;
        this.jobs = jobs;
        this.locations = locations;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public void start(LocationMaintenanceProperties options) {
        options.requireWriteOptIn();
        store.create(options, reader.now());
        Run run = store.read(options.requiredRunId(), true);
        boolean same = run.mode() == options.mode() && run.afterId() == options.afterId()
                && run.upperId() == options.requiredUpperId() && run.maxCalls() == options.maxCalls()
                && run.maxRegistrations() == options.maxRegistrations()
                && Duration.between(run.asOf(), run.refreshBefore()).equals(options.refreshAhead());
        if (!same) {
            throw new IllegalArgumentException("Existing run-id has a different immutable scope or limit");
        }
        // Repeating START never resets counts, scope, or an explicit STOP.
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public boolean advance(UUID id) {
        Run run = store.read(id, true);
        if (!"ACTIVE".equals(run.state())) {
            return false;
        }
        Long next = reader.nextId(run.cursorId(), run.upperId());
        if (next == null) {
            store.state(id, "SCANNED");
            return false;
        }
        if (!run.hasCapacity()) {
            store.state(id, "LIMIT_REACHED");
            return false;
        }
        Restaurant restaurant = restaurants.findByIdForUpdate(next).orElse(null);
        Long jobId = null;
        boolean eligible = restaurant != null && !restaurant.isDeleted() && eligible(restaurant.getLocation(), run);
        if (eligible && jobs.findActiveForUpdate(next).isEmpty()) {
            if (restaurant.getLocation() != null) {
                restaurant.refreshLocation();
            }
            locations.enqueue(restaurant);
            restaurants.flush();
            jobId = jobs.findByRestaurantIdAndRequestId(next, restaurant.getLocation().getRequestId())
                    .orElseThrow().getId();
        }
        // The checkpoint and job link roll back together with the entity/job writes on any failure.
        store.progress(id, next, jobId);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public void stop(UUID id) {
        Run run = store.read(id, true);
        if ("ACTIVE".equals(run.state())) {
            store.state(id, "STOPPED");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public void resume(UUID id) {
        Run run = store.read(id, true);
        if ("STOPPED".equals(run.state())) {
            store.state(id, "ACTIVE");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Status status(UUID id) {
        return new Status(store.read(id, false), store.completion(id, reader.now()));
    }

    private boolean eligible(RestaurantLocation location, Run run) {
        if (run.mode() == LocationMaintenanceProperties.Mode.BACKFILL) {
            return location == null;
        }
        return location != null && location.getStatus() == RestaurantLocationStatus.READY
                && location.getSource() == RestaurantLocationSource.GOOGLE_GEOCODING
                && !location.getObtainedAt().isAfter(run.asOf())
                && Duration.between(run.asOf(), run.refreshBefore())
                    .compareTo(Duration.between(location.getObtainedAt(), location.getValidUntil())) < 0
                && !location.getValidUntil().isAfter(run.refreshBefore());
    }
}
