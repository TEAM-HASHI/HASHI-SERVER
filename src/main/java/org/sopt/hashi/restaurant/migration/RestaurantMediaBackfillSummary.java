package org.sopt.hashi.restaurant.migration;

import java.util.Map;
import java.util.Objects;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Snapshot;

record RestaurantMediaBackfillSummary(
        RestaurantMediaBackfillTarget target,
        RestaurantMediaBackfillMode mode,
        Status status,
        long scannedCount,
        long inspectedCount,
        long preparedCount,
        long attachedCount,
        long skippedCount,
        long failedCount,
        Map<Reason, Long> sourceFailuresThisExecution
) {

    RestaurantMediaBackfillSummary {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(status, "status is required");
        sourceFailuresThisExecution = Map.copyOf(sourceFailuresThisExecution);
    }

    static RestaurantMediaBackfillSummary fromSnapshot(Status status, Snapshot snapshot) {
        return new RestaurantMediaBackfillSummary(
                snapshot.target(), snapshot.mode(), status,
                snapshot.scannedCount(), 0L, snapshot.preparedCount(),
                snapshot.attachedCount(), snapshot.skippedCount(), snapshot.failedCount(), Map.of()
        );
    }

    RestaurantMediaBackfillSummary withSourceFailures(Map<Reason, Long> failures) {
        return new RestaurantMediaBackfillSummary(
                target, mode, status, scannedCount, inspectedCount, preparedCount,
                attachedCount, skippedCount, failedCount, failures);
    }

    enum Status {
        COMPLETED,
        PAUSED,
        BUSY,
        ALREADY_COMPLETED,
        FAILED,
        LEASE_LOST
    }
}
