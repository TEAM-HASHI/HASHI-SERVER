package org.sopt.hashi.restaurant.internal.map;

import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.service.LocationAdoptionPolicy;
import org.sopt.hashi.restaurant.service.LocationJobTransactions;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Outcome;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.PlacesOutcome;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Target;
import org.sopt.hashi.restaurant.service.PlacesCandidatePolicy;
import org.sopt.hashi.restaurant.domain.RestaurantLocationJob.Operation;
import org.sopt.hashi.restaurant.internal.map.places.PlaceDetailsResult;
import org.sopt.hashi.restaurant.internal.map.places.PlacesProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** HTTP 대기 구간에는 transaction이 없다. DB 단계는 별도 proxy Bean을 호출한다. */
@Component
@Slf4j
public class RestaurantLocationWorker {
    private final LocationJobTransactions transactions;
    private final GeocodingProvider provider;
    private final LocationAdoptionPolicy adoption;
    private final PlacesProvider placesProvider;
    private final PlacesCandidatePolicy placesPolicy;

    public RestaurantLocationWorker(LocationJobTransactions transactions, GeocodingProvider provider,
                                     LocationAdoptionPolicy adoption, PlacesProvider placesProvider,
                                     PlacesCandidatePolicy placesPolicy) {
        this.transactions = transactions;
        this.provider = provider;
        this.adoption = adoption;
        this.placesProvider = placesProvider;
        this.placesPolicy = placesPolicy;
    }

    public void runOnce() {
        requireNoTransaction();
        for (Target target : transactions.candidates()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            process(target);
        }
    }

    public void process(Target target) {
        requireNoTransaction();
        var claimed = transactions.claim(target);
        if (claimed.isEmpty()) {
            return;
        }
        var claim = claimed.orElseThrow();
        if (claim.operation() == Operation.PLACE_DETAILS) {
            processDetails(claim);
            return;
        }
        processGeocoding(claim);
    }

    private void processGeocoding(LocationJobTransactions.Claim claim) {
        GeocodingResult result;
        try {
            result = provider.geocode(claim.geocodingAddress());
        } catch (RuntimeException exception) {
            // No provider message/cause is logged or retained. Unknown transmission is not refunded.
            log.warn("Location worker failure operation=location-provider-call exceptionType={}",
                    exception.getClass().getName());
            result = new Failure(FailureKind.TRANSIENT_ERROR, null);
        }
        Outcome outcome = switch (result) {
            case Candidates candidates -> {
                var decision = adoption.evaluate(claim.geocodingAddress(), candidates);
                yield new Outcome(decision.coordinates(), null, decision.failureCode());
            }
            case GeocodingResult.NoResults ignored -> new Outcome(null, null, "NO_RESULTS");
            case Failure failure -> Outcome.failure(failure.kind());
        };
        // An interrupted executor can stop before a DB write; its durable lease remains recoverable.
        if (!Thread.currentThread().isInterrupted()) {
            transactions.complete(claim, outcome);
        }
    }

    private void processDetails(LocationJobTransactions.Claim claim) {
        PlaceDetailsResult result;
        try {
            result = placesProvider.details(claim.googlePlaceId());
        } catch (RuntimeException exception) {
            log.warn("Location worker failure operation=places-details exceptionType={}",
                    exception.getClass().getName());
            result = new PlaceDetailsResult.Failure(FailureKind.TRANSIENT_ERROR, null);
        }
        if (result == null) {
            result = new PlaceDetailsResult.Failure(FailureKind.INVALID_RESPONSE, null);
        }
        PlacesOutcome outcome = switch (result) {
            case PlaceDetailsResult.Place place -> {
                var candidate = place.candidate();
                if (!claim.googlePlaceId().equals(candidate.placeId())) {
                    yield PlacesOutcome.failure(FailureKind.INVALID_RESPONSE);
                }
                if (!placesPolicy.accepts(candidate)) {
                    yield new PlacesOutcome(null, null, null, null, "PLACE_CONTEXT_MISMATCH");
                }
                yield new PlacesOutcome(placesPolicy.coordinates(candidate), candidate.placeId(),
                        placesPolicy.attributions(candidate), null, null);
            }
            case PlaceDetailsResult.NoResults ignored ->
                    new PlacesOutcome(null, null, null, null, "NO_RESULTS");
            case PlaceDetailsResult.Failure failure -> PlacesOutcome.failure(failure.kind());
        };
        if (!Thread.currentThread().isInterrupted()) {
            transactions.completePlaces(claim, outcome);
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Location worker must run outside a transaction");
        }
    }
}
