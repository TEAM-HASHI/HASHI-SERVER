package org.sopt.hashi.restaurant.migration;

import java.time.Duration;
import java.util.List;
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

    RestaurantMediaBackfillRunner(
            RestaurantMediaBackfillProperties properties,
            RestaurantMediaBackfillCandidateReader candidateReader,
            RestaurantMediaBackfillCheckpointStore checkpointStore,
            RestaurantMediaBackfillAttachmentService attachmentService,
            MediaBackfillPort mediaBackfillPort
    ) {
        this.properties = properties;
        this.candidateReader = candidateReader;
        this.checkpointStore = checkpointStore;
        this.attachmentService = attachmentService;
        this.mediaBackfillPort = mediaBackfillPort;
    }

    @Async(RestaurantMediaBackfillConfiguration.EXECUTOR)
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        try {
            RestaurantMediaBackfillSummary summary = execute();
            log.info(
                    "Restaurant media backfill finished: target={}, mode={}, status={}, "
                            + "scanned={}, inspected={}, prepared={}, attached={}, skipped={}, failed={}",
                    summary.target(), summary.mode(), summary.status(), summary.scannedCount(),
                    summary.inspectedCount(), summary.preparedCount(), summary.attachedCount(),
                    summary.skippedCount(), summary.failedCount()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Restaurant media backfill could not start: target={}, mode={}, errorType={}",
                    properties.target(), properties.mode(), exception.getClass().getSimpleName()
            );
        }
    }

    RestaurantMediaBackfillSummary execute() {
        if (properties.mode() == RestaurantMediaBackfillMode.DRY_RUN) {
            return executeDryRun();
        }
        return executePersistent();
    }

    private RestaurantMediaBackfillSummary executeDryRun() {
        long upperBound = candidateReader.findUpperBound(properties.target());
        long cursor = 0L;
        MutableSummary summary = new MutableSummary(properties.target(), properties.mode());

        for (int batchNumber = 0; batchNumber < properties.maxBatches(); batchNumber++) {
            requireNotInterrupted();
            List<RestaurantMediaBackfillCandidate> candidates = findBatch(cursor, upperBound);
            if (candidates.isEmpty()) {
                return summary.finish(Status.COMPLETED);
            }
            for (RestaurantMediaBackfillCandidate candidate : candidates) {
                requireNotInterrupted();
                summary.scanned++;
                if (!candidate.hasUsableLegacyKey()) {
                    summary.failed++;
                    cursor = candidate.associationId();
                    continue;
                }
                try {
                    inspect(candidate);
                    summary.inspected++;
                } catch (MediaBackfillSourceException exception) {
                    summary.failed++;
                }
                cursor = candidate.associationId();
            }
            if (candidates.size() < properties.batchSize()) {
                return summary.finish(Status.COMPLETED);
            }
        }
        Status status = hasMore(cursor, upperBound) ? Status.PAUSED : Status.COMPLETED;
        return summary.finish(status);
    }

    private RestaurantMediaBackfillSummary executePersistent() {
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
        long cursor = acquisition.snapshot().cursorId();
        try {
            for (int batchNumber = 0; batchNumber < properties.maxBatches(); batchNumber++) {
                requireNotInterrupted();
                List<RestaurantMediaBackfillCandidate> candidates = findBatch(cursor, lease.upperBoundId());
                if (candidates.isEmpty()) {
                    return completedSummary(lease);
                }
                for (RestaurantMediaBackfillCandidate candidate : candidates) {
                    requireNotInterrupted();
                    processAndRecord(candidate, lease);
                    cursor = candidate.associationId();
                }
                if (candidates.size() < properties.batchSize()) {
                    return completedSummary(lease);
                }
            }
            if (!hasMore(cursor, lease.upperBoundId())) {
                return completedSummary(lease);
            }
            checkpointStore.pause(lease);
            return RestaurantMediaBackfillSummary.fromSnapshot(
                    Status.PAUSED, checkpointStore.find(lease.runId()));
        } catch (RestaurantMediaBackfillLeaseLostException exception) {
            return RestaurantMediaBackfillSummary.fromSnapshot(
                    Status.LEASE_LOST, checkpointStore.find(lease.runId()));
        } catch (RuntimeException exception) {
            boolean paused = checkpointStore.pause(lease);
            log.error(
                    "Restaurant media backfill stopped: target={}, mode={}, errorType={}",
                    properties.target(), properties.mode(), exception.getClass().getSimpleName()
            );
            return RestaurantMediaBackfillSummary.fromSnapshot(
                    paused ? Status.FAILED : Status.LEASE_LOST,
                    checkpointStore.find(lease.runId()));
        }
    }

    private void processAndRecord(RestaurantMediaBackfillCandidate candidate, Lease lease) {
        if (!candidate.hasUsableLegacyKey()) {
            checkpointStore.recordProgress(
                    lease, candidate.associationId(),
                    RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
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
            checkpointStore.recordProgress(
                    lease, candidate.associationId(),
                    RestaurantMediaBackfillOutcome.FAILED, properties.leaseDuration());
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

    private Duration backoff(Duration initialDelay, int attempt) {
        return initialDelay.multipliedBy(1L << (attempt - 1));
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("restaurant media backfill was interrupted");
        }
    }

    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("restaurant media backfill was interrupted");
        }
    }

    private MediaBackfillReference reference(RestaurantMediaBackfillCandidate candidate) {
        return new MediaBackfillReference(
                candidate.target().mediaTarget(), candidate.associationId(), candidate.legacyKey());
    }

    private List<RestaurantMediaBackfillCandidate> findBatch(long cursor, long upperBound) {
        return candidateReader.findBatch(
                properties.target(), cursor, upperBound, properties.batchSize());
    }

    private boolean hasMore(long cursor, long upperBound) {
        return !candidateReader.findBatch(properties.target(), cursor, upperBound, 1).isEmpty();
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
                    target, mode, status, scanned, inspected, 0L, 0L, 0L, failed);
        }
    }
}
