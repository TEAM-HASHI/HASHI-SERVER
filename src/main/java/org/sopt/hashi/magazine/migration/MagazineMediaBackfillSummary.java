package org.sopt.hashi.magazine.migration;

import java.util.Map;
import java.util.Objects;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;

record MagazineMediaBackfillSummary(
        MagazineMediaBackfillTarget target,
        MagazineMediaBackfillMode mode,
        Status status,
        long scannedCount,
        long inspectedCount,
        long preparedCount,
        long attachedCount,
        long skippedCount,
        long failedCount,
        Map<Reason, Long> sourceFailuresThisExecution
) {

    MagazineMediaBackfillSummary {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(status, "status is required");
        sourceFailuresThisExecution = Map.copyOf(sourceFailuresThisExecution);
    }

    static MagazineMediaBackfillSummary fromSnapshot(Status status, Snapshot snapshot) {
        return new MagazineMediaBackfillSummary(
                snapshot.target(), snapshot.mode(), status,
                snapshot.scannedCount(), 0L, snapshot.preparedCount(),
                snapshot.attachedCount(), snapshot.skippedCount(), snapshot.failedCount(), Map.of()
        );
    }

    MagazineMediaBackfillSummary withSourceFailures(Map<Reason, Long> failures) {
        return new MagazineMediaBackfillSummary(
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
