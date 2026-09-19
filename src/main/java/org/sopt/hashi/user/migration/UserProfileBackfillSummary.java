package org.sopt.hashi.user.migration;

import java.util.Objects;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Snapshot;

record UserProfileBackfillSummary(
        UserProfileBackfillMode mode,
        Status status,
        long scannedCount,
        long inspectedCount,
        long preparedCount,
        long attachedCount,
        long skippedCount,
        long failedCount
) {

    UserProfileBackfillSummary {
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(status, "status is required");
    }

    static UserProfileBackfillSummary fromSnapshot(Status status, Snapshot snapshot) {
        return new UserProfileBackfillSummary(
                snapshot.mode(), status,
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
