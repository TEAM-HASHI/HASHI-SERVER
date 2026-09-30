package org.sopt.hashi.restaurant.migration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Invoked only by the separate CLI. Normal application startup does not run a backfill. */
@Component
public class LocationMaintenanceRunner {
    private final LocationMaintenanceInspection inspection;
    private final LocationMaintenanceTransactions transactions;
    private final LocationRetentionService retention;

    public LocationMaintenanceRunner(LocationMaintenanceInspection inspection,
                                     LocationMaintenanceTransactions transactions, LocationRetentionService retention) {
        this.inspection = inspection;
        this.transactions = transactions;
        this.retention = retention;
    }

    public Object execute(LocationMaintenanceProperties options) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Maintenance runner must run outside a transaction");
        }
        return switch (options.command()) {
            case DRY_RUN -> inspection.inspect(options);
            case STATUS -> transactions.status(options.requiredRunId());
            case PURGE -> {
                options.requireWriteOptIn();
                yield retention.purge(options);
            }
            case STOP -> {
                options.requireWriteOptIn();
                transactions.stop(options.requiredRunId());
                yield transactions.status(options.requiredRunId());
            }
            case START, RESUME -> {
                options.requireWriteOptIn();
                if (options.command() == LocationMaintenanceProperties.Command.START) {
                    transactions.start(options);
                } else {
                    transactions.resume(options.requiredRunId());
                }
                int limit = options.batchSize() * options.maxBatches();
                for (int index = 0; index < limit && !Thread.currentThread().isInterrupted(); index++) {
                    if (!transactions.advance(options.requiredRunId())) {
                        break;
                    }
                }
                yield transactions.status(options.requiredRunId());
            }
        };
    }
}
