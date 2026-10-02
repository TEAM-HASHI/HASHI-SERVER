package org.sopt.hashi.restaurant.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.sopt.hashi.restaurant.domain.GeocodingBudget;
import org.sopt.hashi.restaurant.domain.GeocodingBudgetRepository;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob.State;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationSource;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 짧은 DB 단계만 담당한다. 쓰기 잠금 순서: restaurant -> job -> 전역 budget. */
@Service
public class LocationJobTransactions {
    private final RestaurantRepository restaurants;
    private final RestaurantLocationJobRepository jobs;
    private final GeocodingBudgetRepository budgets;
    private final LocationJobProperties properties;
    private final LocationRetryPolicy retries;

    public LocationJobTransactions(RestaurantRepository restaurants, RestaurantLocationJobRepository jobs,
                                   GeocodingBudgetRepository budgets, LocationJobProperties properties,
                                   LocationRetryPolicy retries) {
        this.restaurants = restaurants;
        this.jobs = jobs;
        this.budgets = budgets;
        this.properties = properties;
        this.retries = retries;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Target> candidates() {
        if (!properties.enabled()) {
            return List.of();
        }
        return jobs.findDue(now(), PageRequest.of(0, 50)).stream()
                .map(job -> new Target(job.getRestaurantId(), job.getId())).toList();
    }

    // READ_COMMITTED makes the reservation count fresh after acquiring the singleton budget lock.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Claim> claim(Target target) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        Restaurant restaurant = restaurants.findByIdForUpdate(target.restaurantId()).orElse(null);
        RestaurantLocationJob job = jobs.findByIdForUpdate(target.jobId()).orElse(null);
        LocalDateTime now = now();
        if (job == null || restaurant == null || !job.getRestaurantId().equals(target.restaurantId())) {
            return Optional.empty();
        }
        if (!job.isCurrent(restaurant)) {
            job.supersede(now);
            return Optional.empty();
        }
        if (!job.isDue(now)) {
            return Optional.empty();
        }
        if (!properties.isConfigured()) {
            restaurant.retryLocationWhenDue(at(now));
            job.followRequest(restaurant.getLocation().getRequestId());
            restaurant.rejectLocation(job.getAddressRevision(), job.getRequestId(), RestaurantLocationStatus.FAILED);
            job.finish(State.FAILED, "CONFIGURATION_ERROR", now);
            return Optional.empty();
        }
        if (job.getAttempt() >= properties.maxAttempts()) {
            restaurant.retryLocationWhenDue(at(now));
            job.followRequest(restaurant.getLocation().getRequestId());
            restaurant.rejectLocation(job.getAddressRevision(), restaurant.getLocation().getRequestId(),
                    RestaurantLocationStatus.FAILED);
            job.finish(State.FAILED, "ATTEMPTS_EXHAUSTED", now);
            return Optional.empty();
        }
        GeocodingBudget budget = budgets.findControlForUpdate().orElse(null);
        // Re-read DB time after waiting for locks. A missing control row fails closed.
        now = now();
        if (budget == null || !budget.reserve(now, jobs.countReservations(now))) {
            return Optional.empty();
        }
        restaurant.retryLocationWhenDue(at(now));
        job.claim(restaurant.getLocation().getRequestId(), now, now.plus(LocationJobProperties.LEASE));
        return Optional.of(new Claim(restaurant.getId(), job.getId(), job.getAddressRevision(), job.getRequestId(),
                job.getLeaseToken(), job.getLeaseUntil(), now, restaurant.getAddress()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean complete(Claim claim, Outcome outcome) {
        Restaurant restaurant = restaurants.findByIdForUpdate(claim.restaurantId()).orElse(null);
        RestaurantLocationJob job = jobs.findByIdForUpdate(claim.jobId()).orElse(null);
        // Complete can change the shared quota pause. Acquire this last lock before validating the deadline.
        budgets.findControlForUpdate();
        LocalDateTime now = now();
        boolean current = restaurant != null && job != null && job.isCurrent(restaurant)
                && job.getAddressRevision() == claim.addressRevision() && job.getRequestId().equals(claim.requestId())
                && job.ownsLease(claim.leaseToken(), now) && job.getLeaseUntil().equals(claim.leaseUntil());
        if (!current) {
            return false;
        }
        if (outcome.coordinates() != null) {
            LocalDateTime until = claim.obtainedAt().plus(properties.retention());
            if (!until.isAfter(now)) {
                deferOrFail(restaurant, job, FailureKind.TRANSIENT_ERROR, "RESULT_EXPIRED", now);
            } else {
                boolean applied = restaurant.completeLocation(claim.addressRevision(), claim.requestId(),
                        outcome.coordinates(), RestaurantLocationSource.GOOGLE_GEOCODING,
                        claim.obtainedAt(), until, at(now));
                if (!applied) {
                    return false;
                }
                job.finish(State.SUCCEEDED, null, now);
            }
        } else if (outcome.failureKind() != null) {
            deferOrFail(restaurant, job, outcome.failureKind(), outcome.failureCode(), now);
        } else {
            restaurant.rejectLocation(claim.addressRevision(), claim.requestId(), RestaurantLocationStatus.REVIEW_REQUIRED);
            job.finish(State.REVIEW_REQUIRED, outcome.failureCode(), now);
        }
        return true;
    }

    private void deferOrFail(Restaurant restaurant, RestaurantLocationJob job, FailureKind kind,
                             String code, LocalDateTime now) {
        boolean retryable = retries.canRetry(kind);
        boolean exhausted = job.getAttempt() >= properties.maxAttempts();
        LocalDateTime next = retryable ? now.plus(retries.delay(kind, job.getAttempt())) : null;
        // Provider quota applies to all jobs even when this job has no automatic attempts left.
        if (kind == FailureKind.QUOTA_EXCEEDED) {
            budgets.findControlForUpdate().ifPresent(budget -> budget.blockUntil(next));
        }
        // Cancellation is a resumable interruption. The next claim still enforces the durable attempt cap.
        if (retryable && (!exhausted || kind == FailureKind.CANCELLED)) {
            restaurant.deferLocation(job.getAddressRevision(), job.getRequestId(), next, at(now));
            job.defer(code, next, now);
        } else {
            restaurant.rejectLocation(job.getAddressRevision(), job.getRequestId(), RestaurantLocationStatus.FAILED);
            job.finish(State.FAILED, retryable ? "ATTEMPTS_EXHAUSTED" : code, now);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.parse(budgets.databaseUtcTime());
    }

    private static Clock at(LocalDateTime now) {
        return Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    public record Target(Long restaurantId, Long jobId) {
    }

    public record Claim(Long restaurantId, Long jobId, long addressRevision, UUID requestId, UUID leaseToken,
                        LocalDateTime leaseUntil, LocalDateTime obtainedAt, String address) {
        @Override
        public String toString() {
            return "LocationClaim[restaurantId=" + restaurantId + ",jobId=" + jobId + "]";
        }
    }

    public record Outcome(MapCoordinates coordinates, FailureKind failureKind, String failureCode) {
        public static Outcome failure(FailureKind kind) {
            return new Outcome(null, kind, kind.name());
        }

        @Override
        public String toString() {
            return "LocationOutcome[failureCode=" + failureCode + "]";
        }
    }
}
