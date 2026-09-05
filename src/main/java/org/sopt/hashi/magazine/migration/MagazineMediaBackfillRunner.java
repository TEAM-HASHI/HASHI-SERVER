package org.sopt.hashi.magazine.migration;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.media.MediaBackfillAssetInfo;
import org.sopt.hashi.media.MediaBackfillInspectionInfo;
import org.sopt.hashi.media.MediaBackfillPort;
import org.sopt.hashi.media.MediaBackfillReference;
import org.sopt.hashi.media.MediaBackfillSourceException;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.magazine.migration.MagazineMediaBackfillSummary.Status;
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
        long upperBound = candidateReader.findUpperBound(properties.target());
        long cursor = 0L;
        MutableSummary summary = new MutableSummary(properties.target(), properties.mode());

        for (int batchNumber = 0; batchNumber < properties.maxBatches(); batchNumber++) {
            requireNotInterrupted();
            List<MagazineMediaBackfillCandidate> candidates = findBatch(cursor, upperBound);
            if (candidates.isEmpty()) {
                return summary.finish(Status.COMPLETED);
            }
            for (MagazineMediaBackfillCandidate candidate : candidates) {
                requireNotInterrupted();
                summary.scanned++;
                if (!candidate.hasUsableLegacyKey()) {
                    summary.failed++;
                    sourceFailures.record(Reason.INVALID_SOURCE);
                    cursor = candidate.magazineId();
                    continue;
                }
                try {
                    inspect(candidate);
                    summary.inspected++;
                } catch (MediaBackfillSourceException exception) {
                    sourceFailures.record(exception.getReason());
                    rethrowInfrastructureFailure(exception);
                    summary.failed++;
                }
                cursor = candidate.magazineId();
            }
            if (candidates.size() < properties.batchSize()) {
                return summary.finish(Status.COMPLETED);
            }
        }
        Status status = hasMore(cursor, upperBound) ? Status.PAUSED : Status.COMPLETED;
        return summary.finish(status);
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
        long cursor = acquisition.snapshot().cursorId();
        try {
            for (int batchNumber = 0; batchNumber < properties.maxBatches(); batchNumber++) {
                requireNotInterrupted();
                List<MagazineMediaBackfillCandidate> candidates = findBatch(cursor, lease.upperBoundId());
                if (candidates.isEmpty()) {
                    return completedSummary(lease);
                }
                for (MagazineMediaBackfillCandidate candidate : candidates) {
                    requireNotInterrupted();
                    processAndRecord(candidate, lease, sourceFailures);
                    cursor = candidate.magazineId();
                }
                if (candidates.size() < properties.batchSize()) {
                    return completedSummary(lease);
                }
            }
            if (!hasMore(cursor, lease.upperBoundId())) {
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
            sourceFailures.record(exception.getReason());
            rethrowInfrastructureFailure(exception);
            checkpointStore.recordProgress(
                    lease, candidate.magazineId(),
                    MagazineMediaBackfillOutcome.FAILED, properties.leaseDuration());
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
        int attempt = 1;
        while (true) {
            try {
                return operation.get();
            } catch (MediaBackfillSourceException exception) {
                boolean retryable = exception.getReason() == Reason.STORAGE_UNAVAILABLE;
                if (!retryable || attempt >= properties.maxAttempts()) {
                    throw exception;
                }
                sleep(backoff(properties.retryInitialDelay(), attempt));
                attempt++;
            }
        }
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

    private Duration backoff(Duration initialDelay, int attempt) {
        return initialDelay.multipliedBy(1L << (attempt - 1));
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("magazine media backfill was interrupted");
        }
    }

    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("magazine media backfill was interrupted");
        }
    }

    private MediaBackfillReference reference(MagazineMediaBackfillCandidate candidate) {
        return new MediaBackfillReference(
                candidate.target().mediaTarget(), candidate.magazineId(), candidate.legacyKey());
    }

    private List<MagazineMediaBackfillCandidate> findBatch(long cursor, long upperBound) {
        return candidateReader.findBatch(
                properties.target(), cursor, upperBound, properties.batchSize());
    }

    private boolean hasMore(long cursor, long upperBound) {
        return !candidateReader.findBatch(properties.target(), cursor, upperBound, 1).isEmpty();
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
