package org.sopt.hashi.user.migration;

import java.util.Map;
import java.util.Objects;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Snapshot;

record UserProfileBackfillSummary(
        UserProfileBackfillMode mode,
        Status status,
        long scannedCount,
        long inspectedCount,
        long preparedCount,
        long attachedCount,
        long skippedCount,
        long failedCount,
        Map<Reason, Long> sourceFailuresThisExecution
) {

    UserProfileBackfillSummary {
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(status, "status is required");
        sourceFailuresThisExecution = Map.copyOf(sourceFailuresThisExecution);
    }

    static UserProfileBackfillSummary fromSnapshot(Status status, Snapshot snapshot) {
        return new UserProfileBackfillSummary(
                snapshot.mode(), status,
                snapshot.scannedCount(), 0L, snapshot.preparedCount(),
                snapshot.attachedCount(), snapshot.skippedCount(), snapshot.failedCount(), Map.of()
        );
    }

    UserProfileBackfillSummary withSourceFailures(Map<Reason, Long> failures) {
        return new UserProfileBackfillSummary(
                mode, status, scannedCount, inspectedCount, preparedCount,
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
