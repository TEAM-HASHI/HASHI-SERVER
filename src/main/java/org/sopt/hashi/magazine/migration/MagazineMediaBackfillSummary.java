package org.sopt.hashi.magazine.migration;

import java.util.Objects;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Snapshot;

record MagazineMediaBackfillSummary(
        MagazineMediaBackfillTarget target,
        MagazineMediaBackfillMode mode,
        Status status,
        long scannedCount,
        long inspectedCount,
        long preparedCount,
        long attachedCount,
        long skippedCount,
        long failedCount
) {

    MagazineMediaBackfillSummary {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(status, "status is required");
    }

    static MagazineMediaBackfillSummary fromSnapshot(Status status, Snapshot snapshot) {
        return new MagazineMediaBackfillSummary(
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
