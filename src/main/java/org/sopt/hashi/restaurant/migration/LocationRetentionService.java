package org.sopt.hashi.restaurant.migration;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LocationRetentionService {
    private final LocationMaintenanceReader reader;
    private final LocationRetentionTransactions transactions;

    public LocationRetentionService(LocationMaintenanceReader reader, LocationRetentionTransactions transactions) {
        this.reader = reader;
        this.transactions = transactions;
    }

    public Report purge(LocationMaintenanceProperties options) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Retention loop must run outside a transaction");
        }
        int inspected = 0;
        int purged = 0;
        for (int batch = 0; batch < options.maxBatches() && !Thread.currentThread().isInterrupted(); batch++) {
            var candidates = reader.purgeCandidates(reader.now().plus(options.purgeAhead()), options.batchSize());
            for (var candidate : candidates) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                inspected++;
                if (transactions.purge(candidate, options.purgeAhead())) {
                    purged++;
                }
            }
            if (candidates.size() < options.batchSize()) {
                break;
            }
        }
        var now = reader.now();
        return new Report(now.toInstant(ZoneOffset.UTC), inspected, purged,
                reader.purgeRemaining(now.plus(options.purgeAhead())), reader.purgeRemaining(now));
    }

    public record Report(Instant asOfUtc, int inspected, int purged, long dueRemaining, long overdueRemaining) {
    }
}
