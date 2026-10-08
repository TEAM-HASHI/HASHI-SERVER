package org.sopt.hashi.restaurant.service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.GeocodingBudgetRepository;
import org.sopt.hashi.restaurant.domain.PlacesBudget.Operation;
import org.sopt.hashi.restaurant.domain.PlacesBudgetRepository;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantLocation;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJobRepository;
import org.sopt.hashi.restaurant.domain.RestaurantLocationStatus;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.restaurant.internal.map.places.PlacesProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Places HTTP 전후의 짧은 DB 단계. 잠금 순서는 restaurant -> job -> operation budget이다. */
@Service
public class PlacesLocationTransactions {
    private static final Set<RestaurantLocationStatus> SELECTABLE = Set.of(
            RestaurantLocationStatus.READY, RestaurantLocationStatus.RETRY_WAIT,
            RestaurantLocationStatus.REVIEW_REQUIRED, RestaurantLocationStatus.FAILED);
    private final RestaurantRepository restaurants;
    private final RestaurantLocationJobRepository jobs;
    private final PlacesBudgetRepository budgets;
    private final GeocodingBudgetRepository databaseClock;

    public PlacesLocationTransactions(RestaurantRepository restaurants, RestaurantLocationJobRepository jobs,
                                      PlacesBudgetRepository budgets, GeocodingBudgetRepository databaseClock) {
        this.restaurants = restaurants;
        this.jobs = jobs;
        this.budgets = budgets;
        this.databaseClock = databaseClock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public SearchContext prepareSearch(Long restaurantId, long expectedAddressRevision) {
        Restaurant restaurant = activeForUpdate(restaurantId);
        RestaurantLocation location = requireSelectable(restaurant, expectedAddressRevision);
        String query = (restaurant.getLocalName() + " " + restaurant.geocodingAddressForResolution()).strip();
        if (query.isEmpty() || query.codePointCount(0, query.length()) > PlacesProvider.MAX_SEARCH_QUERY_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var budget = budgets.findByOperationForUpdate(Operation.SEARCH).orElse(null);
        // Read the clock after waiting for the shared budget lock, so minute/day counters use admission time.
        if (budget == null || !budget.reserve(now())) {
            throw new BusinessException(RestaurantErrorCode.PLACES_BUDGET_EXHAUSTED);
        }
        return new SearchContext(restaurant.getId(), location.getAddressRevision(), location.getRequestId(),
                query);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireCurrent(SearchContext context) {
        Restaurant restaurant = restaurants.findByIdAndDeletedFalse(context.restaurantId())
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.PLACE_SELECTION_CONFLICT));
        RestaurantLocation location = restaurant.getLocation();
        if (location == null || !SELECTABLE.contains(location.getStatus())
                || location.getAddressRevision() != context.addressRevision()
                || !location.getRequestId().equals(context.requestId())) {
            throw new BusinessException(RestaurantErrorCode.PLACE_SELECTION_CONFLICT);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Restaurant select(Long restaurantId, long expectedAddressRevision, UUID expectedRequestId,
                             String googlePlaceId) {
        Restaurant restaurant = activeForUpdate(restaurantId);
        RestaurantLocation location = requireSelectable(restaurant, expectedAddressRevision);
        if (!location.getRequestId().equals(expectedRequestId)) {
            throw new BusinessException(RestaurantErrorCode.PLACE_SELECTION_CONFLICT);
        }
        LocalDateTime now = now();
        jobs.findActiveForUpdate(restaurantId).forEach(job -> job.supersede(now));
        restaurant.selectPlaceForLocation();
        jobs.save(RestaurantLocationJob.pendingDetails(restaurant, googlePlaceId, now));
        return restaurant;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void blockSearchForQuota() {
        var budget = budgets.findByOperationForUpdate(Operation.SEARCH).orElse(null);
        if (budget != null) {
            budget.blockUntil(now().plusMinutes(1));
        }
    }

    private Restaurant activeForUpdate(Long restaurantId) {
        return restaurants.findByIdForUpdate(restaurantId)
                .filter(restaurant -> !restaurant.isDeleted())
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
    }

    private RestaurantLocation requireSelectable(Restaurant restaurant, long expectedAddressRevision) {
        RestaurantLocation location = restaurant.getLocation();
        if (expectedAddressRevision < 1 || location == null
                || location.getAddressRevision() != expectedAddressRevision
                || !SELECTABLE.contains(location.getStatus())) {
            throw new BusinessException(RestaurantErrorCode.PLACE_SELECTION_CONFLICT);
        }
        return location;
    }

    private LocalDateTime now() {
        return LocalDateTime.parse(databaseClock.databaseUtcTime());
    }

    public record SearchContext(Long restaurantId, long addressRevision, UUID requestId, String query) {
        @Override
        public String toString() {
            return "PlacesSearchContext[restaurantId=" + restaurantId + ",addressRevision=" + addressRevision + "]";
        }
    }
}
