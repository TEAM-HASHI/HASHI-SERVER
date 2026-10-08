package org.sopt.hashi.restaurant.service;

import java.util.List;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.RestaurantPlacesCandidateInfo;
import org.sopt.hashi.restaurant.RestaurantPlacesCandidateInfo.Attribution;
import org.sopt.hashi.restaurant.RestaurantPlacesSearchInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.sopt.hashi.restaurant.internal.map.PlacesSelectionToken;
import org.sopt.hashi.restaurant.internal.map.PlacesSelectionToken.Claims;
import org.sopt.hashi.restaurant.internal.map.places.GooglePlacesProperties;
import org.sopt.hashi.restaurant.internal.map.places.PlacesCandidate;
import org.sopt.hashi.restaurant.internal.map.places.PlacesProvider;
import org.sopt.hashi.restaurant.internal.map.places.PlacesSearchResult;
import org.sopt.hashi.restaurant.internal.map.places.PlacesSearchResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.places.PlacesSearchResult.Failure;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 관리자 Places 후보 검색과 서명 후보 선택을 조정한다. provider 대기 중에는 transaction이 없다. */
@Service
public class PlacesLocationService {
    private final PlacesLocationTransactions transactions;
    private final PlacesProvider provider;
    private final PlacesCandidatePolicy policy;
    private final PlacesSelectionToken tokens;
    private final GooglePlacesProperties providerProperties;
    private final LocationJobProperties jobProperties;
    private final RestaurantLocationService locations;

    public PlacesLocationService(PlacesLocationTransactions transactions, PlacesProvider provider,
                                 PlacesCandidatePolicy policy, PlacesSelectionToken tokens,
                                 GooglePlacesProperties providerProperties, LocationJobProperties jobProperties,
                                 RestaurantLocationService locations) {
        this.transactions = transactions;
        this.provider = provider;
        this.policy = policy;
        this.tokens = tokens;
        this.providerProperties = providerProperties;
        this.jobProperties = jobProperties;
        this.locations = locations;
    }

    public RestaurantPlacesSearchInfo search(Long restaurantId, long expectedAddressRevision) {
        requireNoTransaction();
        requireConfigured();
        var context = transactions.prepareSearch(restaurantId, expectedAddressRevision);
        PlacesSearchResult result;
        try {
            result = provider.search(context.query());
        } catch (RuntimeException exception) {
            throw new BusinessException(RestaurantErrorCode.PLACES_PROVIDER_FAILED);
        }
        if (result == null) {
            throw new BusinessException(RestaurantErrorCode.PLACES_PROVIDER_FAILED);
        }
        if (result instanceof Failure failure) {
            if (failure.kind() == org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind.QUOTA_EXCEEDED) {
                transactions.blockSearchForQuota();
            }
            throw providerFailure(failure);
        }
        transactions.requireCurrent(context);
        if (result instanceof PlacesSearchResult.NoResults) {
            return new RestaurantPlacesSearchInfo(restaurantId, context.addressRevision(), List.of());
        }
        List<RestaurantPlacesCandidateInfo> candidates = ((Candidates) result).candidates().stream()
                .filter(policy::accepts)
                .map(candidate -> toInfo(context, candidate))
                .toList();
        return new RestaurantPlacesSearchInfo(restaurantId, context.addressRevision(), candidates);
    }

    public RestaurantLocationInfo select(Long restaurantId, long expectedAddressRevision, String selectionToken) {
        requireNoTransaction();
        requireConfigured();
        Claims claims = tokens.verify(selectionToken)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.PLACE_SELECTION_INVALID));
        if (!restaurantId.equals(claims.restaurantId()) || expectedAddressRevision != claims.addressRevision()) {
            throw new BusinessException(RestaurantErrorCode.PLACE_SELECTION_CONFLICT);
        }
        transactions.select(restaurantId, expectedAddressRevision, claims.requestId(), claims.placeId());
        return locations.get(restaurantId);
    }

    private RestaurantPlacesCandidateInfo toInfo(PlacesLocationTransactions.SearchContext context,
                                                  PlacesCandidate candidate) {
        var issued = tokens.issue(context.restaurantId(), context.addressRevision(), context.requestId(),
                candidate.placeId());
        return new RestaurantPlacesCandidateInfo(candidate.displayName(), candidate.address(),
                candidate.latitude(), candidate.longitude(), candidate.countryCode(), candidate.administrativeArea(),
                candidate.types(), candidate.businessStatus(), candidate.attributions().stream()
                        .map(attribution -> new Attribution(attribution.displayName(), attribution.uri())).toList(),
                candidate.googleMapsUri(), issued.token(), issued.expiresAt());
    }

    private void requireConfigured() {
        if (!providerProperties.enabled() || !tokens.isConfigured() || !jobProperties.isConfigured()) {
            throw new BusinessException(RestaurantErrorCode.PLACES_UNAVAILABLE);
        }
    }

    private BusinessException providerFailure(Failure failure) {
        return switch (failure.kind()) {
            case QUOTA_EXCEEDED, CAPACITY_EXCEEDED ->
                    new BusinessException(RestaurantErrorCode.PLACES_BUDGET_EXHAUSTED);
            case DISABLED, CONFIGURATION_ERROR ->
                    new BusinessException(RestaurantErrorCode.PLACES_UNAVAILABLE);
            default -> new BusinessException(RestaurantErrorCode.PLACES_PROVIDER_FAILED);
        };
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Places provider calls must run outside a transaction");
        }
    }
}
