package org.sopt.hashi.restaurant.migration;

import io.micrometer.core.instrument.MeterRegistry;
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
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Lease;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.restaurant.migration.RestaurantMediaBackfillSummary.Status;
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
        prefix = "hashi.restaurant.media-backfill",
        name = "enabled",
        havingValue = "true"
)
class RestaurantMediaBackfillRunner {

    private final RestaurantMediaBackfillProperties properties;
    private final RestaurantMediaBackfillCandidateReader candidateReader;
    private final RestaurantMediaBackfillCheckpointStore checkpointStore;
    private final RestaurantMediaBackfillAttachmentService attachmentService;
    private final MediaBackfillPort mediaBackfillPort;
    private final MeterRegistry meterRegistry;

    RestaurantMediaBackfillRunner(
            RestaurantMediaBackfillProperties properties,
            RestaurantMediaBackfillCandidateReader candidateReader,
            RestaurantMediaBackfillCheckpointStore checkpointStore,
            RestaurantMediaBackfillAttachmentService attachmentService,
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

    @Async(RestaurantMediaBackfillConfiguration.EXECUTOR)
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        try {
            RestaurantMediaBackfillSummary summary = execute();
            logSummary(summary);
        } catch (RuntimeException exception) {
            log.error(
                    "Restaurant media backfill could not start: target={}, mode={}, errorType={}",
                    properties.target(), properties.mode(), failureType(exception)
            );
        }
    }

    private void logSummary(RestaurantMediaBackfillSummary summary) {
        if (summary.mode() == RestaurantMediaBackfillMode.DRY_RUN) {
            log.info(
                    "Restaurant media backfill finished: target={}, mode={}, status={}, "
                            + "scanned={}, inspected={}, failed={}, sourceFailuresThisExecution={}",
                    summary.target(), summary.mode(), summary.status(), summary.scannedCount(),
                    summary.inspectedCount(), summary.failedCount(), summary.sourceFailuresThisExecution()
            );
            return;
        }
        log.info(
                "Restaurant media backfill finished: target={}, mode={}, status={}, "
                        + "scanned={}, prepared={}, attached={}, skipped={}, failed={}, "
                        + "sourceFailuresThisExecution={}",
                summary.target(), summary.mode(), summary.status(), summary.scannedCount(),
                summary.preparedCount(), summary.attachedCount(), summary.skippedCount(),
                summary.failedCount(), summary.sourceFailuresThisExecution()
        );
    }

    RestaurantMediaBackfillSummary execute() {
        RestaurantMediaBackfillSourceFailures sourceFailures = new RestaurantMediaBackfillSourceFailures(
                properties.target(), properties.mode(), meterRegistry);
        RestaurantMediaBackfillSummary summary = properties.mode() == RestaurantMediaBackfillMode.DRY_RUN
                ? executeDryRun(sourceFailures) : executePersistent(sourceFailures);
        return summary.withSourceFailures(sourceFailures.snapshot());
    }

    private RestaurantMediaBackfillSummary executeDryRun(RestaurantMediaBackfillSourceFailures sourceFailures) {
        MutableSummary summary = new MutableSummary(properties.target(), properties.mode());
        try {
            return scanDryRun(sourceFailures, summary);
        } catch (RuntimeException exception) {
            log.error(
                    "Restaurant media backfill dry run stopped: target={}, mode={}, errorType={}, "
                            + "sourceFailuresThisExecution={}",
                    properties.target(), properties.mode(), failureType(exception),
                    sourceFailures.snapshot()
            );
            return summary.finish(Status.FAILED);
        }
    }

    private RestaurantMediaBackfillSummary scanDryRun(
            RestaurantMediaBackfillSourceFailures sourceFailures,
            MutableSummary summary
    ) {
        long upperBound = candidateReader.findUpperBound(properties.target());
        BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                0L, upperBound, properties.batchSize(), properties.maxBatches(),
                (cursor, upper, limit) -> candidateReader.findBatch(properties.target(), cursor, upper, limit),
                RestaurantMediaBackfillCandidate::associationId,
                candidate -> inspectAndCount(candidate, summary, sourceFailures));
        return summary.finish(result == BoundedKeysetLoop.Result.EXHAUSTED ? Status.COMPLETED : Status.PAUSED);
    }

    private void inspectAndCount(RestaurantMediaBackfillCandidate candidate, MutableSummary summary,
            RestaurantMediaBackfillSourceFailures sourceFailures) {
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

    private RestaurantMediaBackfillSummary executePersistent(RestaurantMediaBackfillSourceFailures sourceFailures) {
        long initialUpperBound = candidateReader.findUpperBound(properties.target());
        Acquisition acquisition = checkpointStore.acquire(
                properties.requiredRunId(), properties.target(), properties.mode(),
                initialUpperBound, properties.leaseDuration()
        );
        if (acquisition.state() == AcquisitionState.BUSY) {
            return RestaurantMediaBackfillSummary.fromSnapshot(Status.BUSY, acquisition.snapshot());
        }
        if (acquisition.state() == AcquisitionState.COMPLETED) {
            return RestaurantMediaBackfillSummary.fromSnapshot(
                    Status.ALREADY_COMPLETED, acquisition.snapshot());
        }

        Lease lease = acquisition.lease();
        try {
            BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                    acquisition.snapshot().cursorId(), lease.upperBoundId(),
                    properties.batchSize(), properties.maxBatches(),
                    (cursor, upper, limit) -> candidateReader.findBatch(properties.target(), cursor, upper, limit),
                    RestaurantMediaBackfillCandidate::associationId,
                    candidate -> processAndRecord(candidate, lease, sourceFailures));
            if (result == BoundedKeysetLoop.Result.EXHAUSTED) {
                return completedSummary(lease);
            }
            boolean paused = checkpointStore.pause(lease);
            return RestaurantMediaBackfillSummary.fromSnapshot(
                    paused ? Status.PAUSED : Status.LEASE_LOST,
                    checkpointStore.find(lease.runId()));
        } catch (RestaurantMediaBackfillLeaseLostException exception) {
            return RestaurantMediaBackfillSummary.fromSnapshot(
                    Status.LEASE_LOST, checkpointStore.find(lease.runId()));
        } catch (RuntimeException exception) {
            boolean paused = checkpointStore.pause(lease);
            log.error(
                    "Restaurant media backfill stopped: target={}, mode={}, errorType={}",
                    properties.target(), properties.mode(), failureType(exception)
            );
            return RestaurantMediaBackfillSummary.fromSnapshot(
                    paused ? Status.FAILED : Status.LEASE_LOST,
                    checkpointStore.find(lease.runId()));
        }
    }

    private void processAndRecord(
            RestaurantMediaBackfillCandidate candidate,
            Lease lease,
            RestaurantMediaBackfillSourceFailures sourceFailures
    ) {
        if (!candidate.hasUsableLegacyKey()) {
            checkpointStore.recordProgress(
                    lease, candidate.associationId(),
                    RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
            sourceFailures.record(Reason.INVALID_SOURCE);
            return;
        }
        try {
            MediaBackfillInspectionInfo inspection = inspect(candidate);
            if (properties.mode() == RestaurantMediaBackfillMode.PREPARE) {
                RestaurantMediaBackfillOutcome outcome = prepare(candidate, inspection);
                checkpointStore.recordProgress(
                        lease, candidate.associationId(), outcome, properties.leaseDuration());
                return;
            }
            attachOrRecord(candidate, inspection, lease);
        } catch (MediaBackfillSourceException exception) {
            if (exception.getReason() == Reason.STORAGE_UNAVAILABLE) {
                sourceFailures.record(exception.getReason());
                throw exception;
            }
            checkpointStore.recordProgress(
                    lease, candidate.associationId(),
                    RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
            sourceFailures.record(exception.getReason());
        }
    }

    private MediaBackfillInspectionInfo inspect(RestaurantMediaBackfillCandidate candidate) {
        MediaBackfillReference reference = reference(candidate);
        return withStorageRetry(() -> mediaBackfillPort.inspect(reference));
    }

    private RestaurantMediaBackfillOutcome prepare(
            RestaurantMediaBackfillCandidate candidate,
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

    private RestaurantMediaBackfillOutcome preparedState(MediaBackfillAssetInfo.State state) {
        return switch (state) {
            case PENDING_COPY, PROCESSING, READY -> RestaurantMediaBackfillOutcome.PREPARED;
            case FAILED, EXPIRED, BOUND, RETIRED, PURGING, PURGED ->
                    RestaurantMediaBackfillOutcome.FAILED;
        };
    }

    private void attachOrRecord(
            RestaurantMediaBackfillCandidate candidate,
            MediaBackfillInspectionInfo inspection,
            Lease lease
    ) {
        Optional<MediaBackfillAssetInfo> asset = inspection.asset();
        if (asset.isPresent() && asset.get().state() == MediaBackfillAssetInfo.State.READY) {
            attachmentService.attachAndRecord(
                    candidate, asset.get(), lease, properties.leaseDuration());
            return;
        }
        RestaurantMediaBackfillOutcome outcome = asset.isPresent()
                && terminal(asset.get().state())
                ? RestaurantMediaBackfillOutcome.FAILED
                : RestaurantMediaBackfillOutcome.SKIPPED;
        checkpointStore.recordProgress(
                lease, candidate.associationId(), outcome, properties.leaseDuration());
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

    private MediaBackfillReference reference(RestaurantMediaBackfillCandidate candidate) {
        return new MediaBackfillReference(
                candidate.target().mediaTarget(), candidate.associationId(), candidate.legacyKey());
    }

    private RestaurantMediaBackfillSummary completedSummary(Lease lease) {
        Snapshot snapshot = checkpointStore.complete(lease);
        return RestaurantMediaBackfillSummary.fromSnapshot(Status.COMPLETED, snapshot);
    }

    private static final class MutableSummary {

        private final RestaurantMediaBackfillTarget target;
        private final RestaurantMediaBackfillMode mode;
        private long scanned;
        private long inspected;
        private long failed;

        private MutableSummary(
                RestaurantMediaBackfillTarget target,
                RestaurantMediaBackfillMode mode
        ) {
            this.target = target;
            this.mode = mode;
        }

        private RestaurantMediaBackfillSummary finish(Status status) {
            return new RestaurantMediaBackfillSummary(
                    target, mode, status, scanned, inspected, 0L, 0L, 0L, failed, Map.of());
        }
    }
}
