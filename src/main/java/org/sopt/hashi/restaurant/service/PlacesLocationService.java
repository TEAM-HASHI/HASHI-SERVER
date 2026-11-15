package org.sopt.hashi.restaurant.service;

import java.util.List;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.restaurant.RestaurantLocationPreviewCommand;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.RestaurantPlacesCandidateInfo;
import org.sopt.hashi.restaurant.RestaurantPlacesCandidateInfo.Attribution;
import org.sopt.hashi.restaurant.RestaurantPlacesSearchInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
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
import org.sopt.hashi.shared.error.CommonErrorCode;
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
    private final CurrentActorProvider currentActorProvider;

    public PlacesLocationService(PlacesLocationTransactions transactions, PlacesProvider provider,
                                 PlacesCandidatePolicy policy, PlacesSelectionToken tokens,
                                 GooglePlacesProperties providerProperties, LocationJobProperties jobProperties,
                                 RestaurantLocationService locations, CurrentActorProvider currentActorProvider) {
        this.transactions = transactions;
        this.provider = provider;
        this.policy = policy;
        this.tokens = tokens;
        this.providerProperties = providerProperties;
        this.jobProperties = jobProperties;
        this.locations = locations;
        this.currentActorProvider = currentActorProvider;
    }

    public RestaurantPlacesSearchInfo search(Long restaurantId, long expectedAddressRevision) {
        requireNoTransaction();
        requireConfigured();
        Long adminId = requireAdmin();
        var context = transactions.prepareSearch(restaurantId, expectedAddressRevision);
        PlacesSearchResult result = searchProvider(context.query());
        transactions.requireCurrent(context);
        if (result instanceof PlacesSearchResult.NoResults) {
            return new RestaurantPlacesSearchInfo(restaurantId, context.addressRevision(), List.of());
        }
        List<RestaurantPlacesCandidateInfo> candidates = ((Candidates) result).candidates().stream()
                .filter(policy::accepts)
                .map(candidate -> toInfo(candidate, tokens.issue(context.restaurantId(), context.addressRevision(),
                        context.requestId(), candidate.placeId(), adminId)))
                .toList();
        return new RestaurantPlacesSearchInfo(restaurantId, context.addressRevision(), candidates);
    }

    public RestaurantLocationInfo select(Long restaurantId, long expectedAddressRevision, String selectionToken) {
        requireNoTransaction();
        requireConfigured();
        Long adminId = requireAdmin();
        Claims claims = tokens.verify(selectionToken)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.PLACE_SELECTION_INVALID));
        if (claims.preview() || !adminId.equals(claims.adminId())
                || !restaurantId.equals(claims.restaurantId()) || expectedAddressRevision != claims.addressRevision()) {
            throw new BusinessException(RestaurantErrorCode.PLACE_SELECTION_CONFLICT);
        }
        transactions.select(restaurantId, expectedAddressRevision, claims.requestId(), claims.placeId(), adminId);
        return locations.get(restaurantId);
    }

    public List<RestaurantPlacesCandidateInfo> preview(RestaurantLocationPreviewCommand input) {
        requireNoTransaction();
        requireConfigured();
        Long adminId = requireAdmin();
        validatePreviewInput(input);
        String address = Restaurant.normalizeGeocodingAddress(input.geocodingAddress());
        String query = (input.localName() + " " + (address == null ? input.address() : address)).strip();
        transactions.reservePreviewSearch(query);
        PlacesSearchResult result = searchProvider(query);
        if (result instanceof PlacesSearchResult.NoResults) {
            return List.of();
        }
        return ((Candidates) result).candidates().stream()
                .filter(policy::accepts)
                .map(candidate -> toInfo(candidate, tokens.issuePreview(input, candidate.placeId(), adminId)))
                .toList();
    }

    /** 최종 저장 직전 서명·입력·관리자 검증만 수행한다. 이 단계는 외부 API를 호출하지 않는다. */
    public CreationSelection verifyForCreate(RestaurantLocationPreviewCommand input, String selectionToken) {
        requireConfigured();
        Long adminId = requireAdmin();
        validatePreviewInput(input);
        Claims claims = tokens.verify(selectionToken)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.PLACE_SELECTION_INVALID));
        if (!claims.preview() || !adminId.equals(claims.adminId())
                || !PlacesSelectionToken.fingerprint(input).equals(claims.fingerprint())) {
            throw new BusinessException(RestaurantErrorCode.PLACE_SELECTION_CONFLICT);
        }
        return new CreationSelection(claims.placeId(), adminId);
    }

    private PlacesSearchResult searchProvider(String query) {
        PlacesSearchResult result;
        try {
            result = provider.search(query);
        } catch (RuntimeException exception) {
            throw new BusinessException(RestaurantErrorCode.PLACES_PROVIDER_FAILED);
        }
        if (result == null) {
            throw new BusinessException(RestaurantErrorCode.PLACES_PROVIDER_FAILED);
        }
        if (result instanceof Failure failure) {
            if (failure.kind() == FailureKind.QUOTA_EXCEEDED) {
                transactions.blockSearchForQuota();
            }
            throw providerFailure(failure);
        }
        return result;
    }

    private Long requireAdmin() {
        var actor = currentActorProvider.currentActor();
        if (actor.type() != ActorType.ADMIN || actor.subjectId() < 1) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return actor.subjectId();
    }

    private void validatePreviewInput(RestaurantLocationPreviewCommand input) {
        if (input == null || invalidRequired(input.name(), 100) || invalidRequired(input.localName(), 100)
                || invalidRequired(input.address(), 255)
                || (input.geocodingAddress() != null && invalidRequired(input.geocodingAddress(), 255))) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private boolean invalidRequired(String value, int maxLength) {
        return value == null || value.length() > maxLength
                || value.codePoints().allMatch(codePoint -> Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint));
    }

    private RestaurantPlacesCandidateInfo toInfo(PlacesCandidate candidate, PlacesSelectionToken.Issued issued) {
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

    public record CreationSelection(String placeId, Long adminId) {
        @Override
        public String toString() {
            return "CreationSelection[redacted]";
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Places provider calls must run outside a transaction");
        }
    }
}
