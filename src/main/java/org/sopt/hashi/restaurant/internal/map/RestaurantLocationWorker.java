package org.sopt.hashi.restaurant.internal.map;

import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.service.LocationAdoptionPolicy;
import org.sopt.hashi.restaurant.service.LocationJobTransactions;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Outcome;
import org.sopt.hashi.restaurant.service.LocationJobTransactions.Target;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** HTTP 대기 구간에는 transaction이 없다. DB 단계는 별도 proxy Bean을 호출한다. */
@Component
public class RestaurantLocationWorker {
    private final LocationJobTransactions transactions;
    private final GeocodingProvider provider;
    private final LocationAdoptionPolicy adoption;

    public RestaurantLocationWorker(LocationJobTransactions transactions, GeocodingProvider provider,
                                     LocationAdoptionPolicy adoption) {
        this.transactions = transactions;
        this.provider = provider;
        this.adoption = adoption;
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
        GeocodingResult result;
        try {
            result = provider.geocode(claim.address());
        } catch (RuntimeException exception) {
            // No provider message/cause is logged or retained. Unknown transmission is not refunded.
            result = new Failure(FailureKind.TRANSIENT_ERROR, null);
        }
        Outcome outcome = switch (result) {
            case Candidates candidates -> {
                var decision = adoption.evaluate(claim.address(), candidates);
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

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Location worker must run outside a transaction");
        }
    }
}
