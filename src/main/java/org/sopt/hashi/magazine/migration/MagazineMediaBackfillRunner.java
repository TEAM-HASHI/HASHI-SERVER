package org.sopt.hashi.magazine.migration;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillSummary.Status;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.shared.migration.BoundedKeysetLoop;
import org.sopt.hashi.shared.migration.BoundedRetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "hashi.magazine.media-backfill",
        name = "enabled",
        havingValue = "true"
)
class MagazineMediaBackfillRunner {

    private final MagazineMediaBackfillProperties properties;
    private final MagazineMediaBackfillCandidateReader candidateReader;
    private final MagazineMediaBackfillCheckpointStore checkpointStore;
    private final MagazineMediaBackfillAttachmentService attachmentService;
    private final MediaBackfillPort mediaBackfillPort;
    private final MeterRegistry meterRegistry;

    MagazineMediaBackfillRunner(
            MagazineMediaBackfillProperties properties,
            MagazineMediaBackfillCandidateReader candidateReader,
            MagazineMediaBackfillCheckpointStore checkpointStore,
            MagazineMediaBackfillAttachmentService attachmentService,
            MediaBackfillPort mediaBackfillPort,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.candidateReader = candidateReader;
        this.checkpointStore = checkpointStore;
        this.attachmentService = attachmentService;
        this.mediaBackfillPort = mediaBackfillPort;
        this.meterRegistry = meterRegistry;
    }

    @Async(MagazineMediaBackfillConfiguration.EXECUTOR)
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        try {
            MagazineMediaBackfillSummary summary = execute();
            logSummary(summary);
        } catch (RuntimeException exception) {
            log.error(
                    "Magazine media backfill could not start: target={}, mode={}, errorType={}",
                    properties.target(), properties.mode(), failureType(exception)
            );
        }
    }

    private void logSummary(MagazineMediaBackfillSummary summary) {
        if (summary.mode() == MagazineMediaBackfillMode.DRY_RUN) {
            log.info(
                    "Magazine media backfill finished: target={}, mode={}, status={}, "
                            + "scanned={}, inspected={}, failed={}, sourceFailuresThisExecution={}",
                    summary.target(), summary.mode(), summary.status(), summary.scannedCount(),
                    summary.inspectedCount(), summary.failedCount(), summary.sourceFailuresThisExecution()
            );
            return;
        }
        log.info(
                "Magazine media backfill finished: target={}, mode={}, status={}, "
                        + "scanned={}, prepared={}, attached={}, skipped={}, failed={}, "
                        + "sourceFailuresThisExecution={}",
                summary.target(), summary.mode(), summary.status(), summary.scannedCount(),
                summary.preparedCount(), summary.attachedCount(), summary.skippedCount(),
                summary.failedCount(), summary.sourceFailuresThisExecution()
        );
    }

    MagazineMediaBackfillSummary execute() {
        MagazineMediaBackfillSourceFailures sourceFailures = new MagazineMediaBackfillSourceFailures(
                properties.target(), properties.mode(), meterRegistry);
        MagazineMediaBackfillSummary summary = properties.mode() == MagazineMediaBackfillMode.DRY_RUN
                ? executeDryRun(sourceFailures) : executePersistent(sourceFailures);
        return summary.withSourceFailures(sourceFailures.snapshot());
    }

    private MagazineMediaBackfillSummary executeDryRun(MagazineMediaBackfillSourceFailures sourceFailures) {
        MutableSummary summary = new MutableSummary(properties.target(), properties.mode());
        try {
            return scanDryRun(sourceFailures, summary);
        } catch (RuntimeException exception) {
            log.error(
                    "Magazine media backfill dry run stopped: target={}, mode={}, errorType={}, "
                            + "sourceFailuresThisExecution={}",
                    properties.target(), properties.mode(), failureType(exception),
                    sourceFailures.snapshot()
            );
            return summary.finish(Status.FAILED);
        }
    }

    private MagazineMediaBackfillSummary scanDryRun(
            MagazineMediaBackfillSourceFailures sourceFailures,
            MutableSummary summary
    ) {
        long upperBound = candidateReader.findUpperBound(properties.target());
        BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                0L, upperBound, properties.batchSize(), properties.maxBatches(),
                (cursor, upper, limit) -> candidateReader.findBatch(properties.target(), cursor, upper, limit),
                MagazineMediaBackfillCandidate::magazineId,
                candidate -> inspectAndCount(candidate, summary, sourceFailures));
        return summary.finish(result == BoundedKeysetLoop.Result.EXHAUSTED ? Status.COMPLETED : Status.PAUSED);
    }

    private void inspectAndCount(
            MagazineMediaBackfillCandidate candidate,
            MutableSummary summary,
            MagazineMediaBackfillSourceFailures sourceFailures
    ) {
        summary.scanned++;
        if (!candidate.hasUsableLegacyKey()) {
            summary.failed++;
            sourceFailures.record(Reason.INVALID_SOURCE);
            return;
        }
        try {
            inspect(candidate);
            summary.inspected++;
        } catch (MediaBackfillSourceException exception) {
            sourceFailures.record(exception.getReason());
            rethrowInfrastructureFailure(exception);
            summary.failed++;
        }
    }

    private MagazineMediaBackfillSummary executePersistent(MagazineMediaBackfillSourceFailures sourceFailures) {
        long initialUpperBound = candidateReader.findUpperBound(properties.target());
        Acquisition acquisition = checkpointStore.acquire(
                properties.requiredRunId(), properties.target(), properties.mode(),
                initialUpperBound, properties.leaseDuration()
        );
        if (acquisition.state() == AcquisitionState.BUSY) {
            return MagazineMediaBackfillSummary.fromSnapshot(Status.BUSY, acquisition.snapshot());
        }
        if (acquisition.state() == AcquisitionState.COMPLETED) {
            return MagazineMediaBackfillSummary.fromSnapshot(
                    Status.ALREADY_COMPLETED, acquisition.snapshot());
        }

        Lease lease = acquisition.lease();
        try {
            BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                    acquisition.snapshot().cursorId(), lease.upperBoundId(),
                    properties.batchSize(), properties.maxBatches(),
                    (cursor, upper, limit) -> candidateReader.findBatch(properties.target(), cursor, upper, limit),
                    MagazineMediaBackfillCandidate::magazineId,
                    candidate -> processAndRecord(candidate, lease, sourceFailures));
            if (result == BoundedKeysetLoop.Result.EXHAUSTED) {
                return completedSummary(lease);
            }
            boolean paused = checkpointStore.pause(lease);
            return MagazineMediaBackfillSummary.fromSnapshot(
                    paused ? Status.PAUSED : Status.LEASE_LOST, checkpointStore.find(lease.runId()));
        } catch (MagazineMediaBackfillLeaseLostException exception) {
            return MagazineMediaBackfillSummary.fromSnapshot(
                    Status.LEASE_LOST, checkpointStore.find(lease.runId()));
        } catch (RuntimeException exception) {
            boolean paused = checkpointStore.pause(lease);
            log.error(
                    "Magazine media backfill stopped: target={}, mode={}, errorType={}",
                    properties.target(), properties.mode(), failureType(exception)
            );
            return MagazineMediaBackfillSummary.fromSnapshot(
                    paused ? Status.FAILED : Status.LEASE_LOST,
                    checkpointStore.find(lease.runId()));
        }
    }

    private void processAndRecord(
            MagazineMediaBackfillCandidate candidate,
            Lease lease,
            MagazineMediaBackfillSourceFailures sourceFailures
    ) {
        if (!candidate.hasUsableLegacyKey()) {
            checkpointStore.recordProgress(
                    lease, candidate.magazineId(),
                    MagazineMediaBackfillOutcome.FAILED, properties.leaseDuration());
            sourceFailures.record(Reason.INVALID_SOURCE);
            return;
        }
        try {
            MediaBackfillInspectionInfo inspection = inspect(candidate);
            if (properties.mode() == MagazineMediaBackfillMode.PREPARE) {
                MagazineMediaBackfillOutcome outcome = prepare(candidate, inspection);
                checkpointStore.recordProgress(
                        lease, candidate.magazineId(), outcome, properties.leaseDuration());
                return;
            }
            attachOrRecord(candidate, inspection, lease);
        } catch (MediaBackfillSourceException exception) {
            if (exception.getReason() == Reason.STORAGE_UNAVAILABLE) {
                sourceFailures.record(exception.getReason());
                throw exception;
            }
            checkpointStore.recordProgress(
                    lease, candidate.magazineId(),
                    MagazineMediaBackfillOutcome.FAILED, properties.leaseDuration());
            sourceFailures.record(exception.getReason());
        }
    }

    private MediaBackfillInspectionInfo inspect(MagazineMediaBackfillCandidate candidate) {
        MediaBackfillReference reference = reference(candidate);
        return withStorageRetry(() -> mediaBackfillPort.inspect(reference));
    }

    private MagazineMediaBackfillOutcome prepare(
            MagazineMediaBackfillCandidate candidate,
            MediaBackfillInspectionInfo inspection
    ) {
        Optional<MediaBackfillAssetInfo> existing = inspection.asset();
        if (existing.isPresent() && existing.get().state() != MediaBackfillAssetInfo.State.PENDING_COPY) {
            return preparedState(existing.get().state());
        }
        MediaBackfillAssetInfo prepared = withStorageRetry(() -> mediaBackfillPort.prepare(
                reference(candidate), inspection.identityHash()));
        return preparedState(prepared.state());
    }

    private MagazineMediaBackfillOutcome preparedState(MediaBackfillAssetInfo.State state) {
        return switch (state) {
            case PENDING_COPY, PROCESSING, READY -> MagazineMediaBackfillOutcome.PREPARED;
            case FAILED, EXPIRED, BOUND, RETIRED, PURGING, PURGED ->
                    MagazineMediaBackfillOutcome.FAILED;
        };
    }

    private void attachOrRecord(
            MagazineMediaBackfillCandidate candidate,
            MediaBackfillInspectionInfo inspection,
            Lease lease
    ) {
        Optional<MediaBackfillAssetInfo> asset = inspection.asset();
        if (asset.isPresent() && asset.get().state() == MediaBackfillAssetInfo.State.READY) {
            attachmentService.attachAndRecord(
                    candidate, asset.get(), lease, properties.leaseDuration());
            return;
        }
        MagazineMediaBackfillOutcome outcome = asset.isPresent()
                && terminal(asset.get().state())
                ? MagazineMediaBackfillOutcome.FAILED
                : MagazineMediaBackfillOutcome.SKIPPED;
        checkpointStore.recordProgress(
                lease, candidate.magazineId(), outcome, properties.leaseDuration());
    }

    private boolean terminal(MediaBackfillAssetInfo.State state) {
        return switch (state) {
            case FAILED, EXPIRED, BOUND, RETIRED, PURGING, PURGED -> true;
            case PENDING_COPY, PROCESSING, READY -> false;
        };
    }

    private <T> T withStorageRetry(Supplier<T> operation) {
        return BoundedRetry.execute(
                properties.maxAttempts(), properties.retryInitialDelay(), operation,
                failure -> failure instanceof MediaBackfillSourceException source
                        && source.getReason() == Reason.STORAGE_UNAVAILABLE);
    }

    private void rethrowInfrastructureFailure(MediaBackfillSourceException exception) {
        if (exception.getReason() == Reason.STORAGE_UNAVAILABLE) {
            throw exception;
        }
    }

    private String failureType(RuntimeException exception) {
        if (exception instanceof MediaBackfillSourceException sourceException) {
            return sourceException.getReason().name();
        }
        return exception.getClass().getSimpleName();
    }

    private MediaBackfillReference reference(MagazineMediaBackfillCandidate candidate) {
        return new MediaBackfillReference(
                candidate.target().mediaTarget(), candidate.magazineId(), candidate.legacyKey());
    }

    private MagazineMediaBackfillSummary completedSummary(Lease lease) {
        Snapshot snapshot = checkpointStore.complete(lease);
        return MagazineMediaBackfillSummary.fromSnapshot(Status.COMPLETED, snapshot);
    }

    private static final class MutableSummary {

        private final MagazineMediaBackfillTarget target;
        private final MagazineMediaBackfillMode mode;
        private long scanned;
        private long inspected;
        private long failed;

        private MutableSummary(
                MagazineMediaBackfillTarget target,
                MagazineMediaBackfillMode mode
        ) {
            this.target = target;
            this.mode = mode;
        }

        private MagazineMediaBackfillSummary finish(Status status) {
            return new MagazineMediaBackfillSummary(
                    target, mode, status, scanned, inspected, 0L, 0L, 0L, failed, Map.of());
        }
    }
}
