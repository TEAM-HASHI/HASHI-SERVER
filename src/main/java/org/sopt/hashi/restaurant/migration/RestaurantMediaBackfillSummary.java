package org.sopt.hashi.restaurant.migration;

import java.util.Objects;
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
        long failedCount
) {

    RestaurantMediaBackfillSummary {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(status, "status is required");
    }

    static RestaurantMediaBackfillSummary fromSnapshot(Status status, Snapshot snapshot) {
        return new RestaurantMediaBackfillSummary(
                snapshot.target(), snapshot.mode(), status,
                snapshot.scannedCount(), 0L, snapshot.preparedCount(),
                snapshot.attachedCount(), snapshot.skippedCount(), snapshot.failedCount()
        );
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
