package org.sopt.hashi.user.migration;

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
import org.sopt.hashi.media.MediaBackfillTarget;
import org.sopt.hashi.media.MediaBackfillSourceException.Reason;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Acquisition;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.AcquisitionState;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Lease;
import org.sopt.hashi.user.migration.UserProfileBackfillCheckpointStore.Snapshot;
import org.sopt.hashi.user.migration.UserProfileBackfillSummary.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "hashi.user.profile-backfill",
        name = "enabled",
        havingValue = "true"
)
class UserProfileBackfillRunner {

    private final UserProfileBackfillProperties properties;
    private final UserProfileBackfillCandidateReader candidateReader;
    private final UserProfileBackfillCheckpointStore checkpointStore;
    private final UserProfileBackfillAttachmentService attachmentService;
    private final MediaBackfillPort mediaBackfillPort;
    private final MeterRegistry meterRegistry;

    UserProfileBackfillRunner(
            UserProfileBackfillProperties properties,
            UserProfileBackfillCandidateReader candidateReader,
            UserProfileBackfillCheckpointStore checkpointStore,
            UserProfileBackfillAttachmentService attachmentService,
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

    @Async(UserProfileBackfillConfiguration.EXECUTOR)
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        try {
            UserProfileBackfillSummary summary = execute();
            log.info(
                    "User profile backfill finished: mode={}, status={}, "
                            + "scanned={}, inspected={}, prepared={}, attached={}, skipped={}, failed={}, "
                            + "sourceFailuresThisExecution={}",
                    summary.mode(), summary.status(), summary.scannedCount(),
                    summary.inspectedCount(), summary.preparedCount(), summary.attachedCount(),
                    summary.skippedCount(), summary.failedCount(), summary.sourceFailuresThisExecution()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "User profile backfill could not start: mode={}, errorType={}",
                    properties.mode(), exception.getClass().getSimpleName()
            );
        }
    }

    UserProfileBackfillSummary execute() {
        UserProfileBackfillSourceFailures sourceFailures = new UserProfileBackfillSourceFailures(
                properties.mode(), meterRegistry);
        UserProfileBackfillSummary summary = properties.mode() == UserProfileBackfillMode.DRY_RUN
                ? executeDryRun(sourceFailures) : executePersistent(sourceFailures);
        return summary.withSourceFailures(sourceFailures.snapshot());
    }

    private UserProfileBackfillSummary executeDryRun(UserProfileBackfillSourceFailures sourceFailures) {
        MutableSummary summary = new MutableSummary(properties.mode());
        try {
            return scanDryRun(sourceFailures, summary);
        } catch (RuntimeException exception) {
            log.error(
                    "User profile backfill dry run stopped: mode={}, errorType={}, "
                            + "sourceFailuresThisExecution={}",
                    properties.mode(), exception.getClass().getSimpleName(),
                    sourceFailures.snapshot()
            );
            return summary.finish(Status.FAILED);
        }
    }

    private UserProfileBackfillSummary scanDryRun(
            UserProfileBackfillSourceFailures sourceFailures,
            MutableSummary summary
    ) {
        long upperBound = candidateReader.findUpperBound();
        long cursor = 0L;

        for (int batchNumber = 0; batchNumber < properties.maxBatches(); batchNumber++) {
            requireNotInterrupted();
            List<UserProfileBackfillCandidate> candidates = findBatch(cursor, upperBound);
            if (candidates.isEmpty()) {
                return summary.finish(Status.COMPLETED);
            }
            for (UserProfileBackfillCandidate candidate : candidates) {
                requireNotInterrupted();
                summary.scanned++;
                if (!candidate.hasUsableLegacyKey()) {
                    summary.failed++;
                    sourceFailures.record(Reason.INVALID_SOURCE);
                    cursor = candidate.userId();
                    continue;
                }
                try {
                    inspect(candidate);
                    summary.inspected++;
                } catch (MediaBackfillSourceException exception) {
                    summary.failed++;
                    sourceFailures.record(exception.getReason());
                }
                cursor = candidate.userId();
            }
            if (candidates.size() < properties.batchSize()) {
                return summary.finish(Status.COMPLETED);
            }
        }
        Status status = hasMore(cursor, upperBound) ? Status.PAUSED : Status.COMPLETED;
        return summary.finish(status);
    }

    private UserProfileBackfillSummary executePersistent(UserProfileBackfillSourceFailures sourceFailures) {
        long initialUpperBound = candidateReader.findUpperBound();
        Acquisition acquisition = checkpointStore.acquire(
                properties.requiredRunId(), properties.mode(),
                initialUpperBound, properties.leaseDuration()
        );
        if (acquisition.state() == AcquisitionState.BUSY) {
            return UserProfileBackfillSummary.fromSnapshot(Status.BUSY, acquisition.snapshot());
        }
        if (acquisition.state() == AcquisitionState.COMPLETED) {
            return UserProfileBackfillSummary.fromSnapshot(
                    Status.ALREADY_COMPLETED, acquisition.snapshot());
        }

        Lease lease = acquisition.lease();
        long cursor = acquisition.snapshot().cursorId();
        try {
            for (int batchNumber = 0; batchNumber < properties.maxBatches(); batchNumber++) {
                requireNotInterrupted();
                List<UserProfileBackfillCandidate> candidates = findBatch(cursor, lease.upperBoundId());
                if (candidates.isEmpty()) {
                    return completedSummary(lease);
                }
                for (UserProfileBackfillCandidate candidate : candidates) {
                    requireNotInterrupted();
                    processAndRecord(candidate, lease, sourceFailures);
                    cursor = candidate.userId();
                }
                if (candidates.size() < properties.batchSize()) {
                    return completedSummary(lease);
                }
            }
            if (!hasMore(cursor, lease.upperBoundId())) {
                return completedSummary(lease);
            }
            boolean paused = checkpointStore.pause(lease);
            return UserProfileBackfillSummary.fromSnapshot(
                    paused ? Status.PAUSED : Status.LEASE_LOST, checkpointStore.find(lease.runId()));
        } catch (UserProfileBackfillLeaseLostException exception) {
            return UserProfileBackfillSummary.fromSnapshot(
                    Status.LEASE_LOST, checkpointStore.find(lease.runId()));
        } catch (RuntimeException exception) {
            boolean paused = checkpointStore.pause(lease);
            log.error(
                    "User profile backfill stopped: mode={}, errorType={}",
                    properties.mode(), exception.getClass().getSimpleName()
            );
            return UserProfileBackfillSummary.fromSnapshot(
                    paused ? Status.FAILED : Status.LEASE_LOST,
                    checkpointStore.find(lease.runId()));
        }
    }

    private void processAndRecord(
            UserProfileBackfillCandidate candidate,
            Lease lease,
            UserProfileBackfillSourceFailures sourceFailures
    ) {
        if (!candidate.hasUsableLegacyKey()) {
            checkpointStore.recordProgress(
                    lease, candidate.userId(),
                    UserProfileBackfillOutcome.FAILED, properties.leaseDuration());
            sourceFailures.record(Reason.INVALID_SOURCE);
            return;
        }
        try {
            MediaBackfillInspectionInfo inspection = inspect(candidate);
            if (properties.mode() == UserProfileBackfillMode.PREPARE) {
                UserProfileBackfillOutcome outcome = prepare(candidate, inspection);
                checkpointStore.recordProgress(
                        lease, candidate.userId(), outcome, properties.leaseDuration());
                return;
            }
            attachOrRecord(candidate, inspection, lease);
        } catch (MediaBackfillSourceException exception) {
            checkpointStore.recordProgress(
                    lease, candidate.userId(),
                    UserProfileBackfillOutcome.FAILED, properties.leaseDuration());
            sourceFailures.record(exception.getReason());
        }
    }

    private MediaBackfillInspectionInfo inspect(UserProfileBackfillCandidate candidate) {
        MediaBackfillReference reference = reference(candidate);
        return withStorageRetry(() -> mediaBackfillPort.inspect(reference));
    }

    private UserProfileBackfillOutcome prepare(
            UserProfileBackfillCandidate candidate,
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

    private UserProfileBackfillOutcome preparedState(MediaBackfillAssetInfo.State state) {
        return switch (state) {
            case PENDING_COPY, PROCESSING, READY -> UserProfileBackfillOutcome.PREPARED;
            case FAILED, EXPIRED, BOUND, RETIRED, PURGING, PURGED ->
                    UserProfileBackfillOutcome.FAILED;
        };
    }

    private void attachOrRecord(
            UserProfileBackfillCandidate candidate,
            MediaBackfillInspectionInfo inspection,
            Lease lease
    ) {
        Optional<MediaBackfillAssetInfo> asset = inspection.asset();
        if (asset.isPresent() && asset.get().state() == MediaBackfillAssetInfo.State.READY) {
            attachmentService.attachAndRecord(
                    candidate, asset.get(), lease, properties.leaseDuration());
            return;
        }
        UserProfileBackfillOutcome outcome = asset.isPresent()
                && terminal(asset.get().state())
                ? UserProfileBackfillOutcome.FAILED
                : UserProfileBackfillOutcome.SKIPPED;
        checkpointStore.recordProgress(
                lease, candidate.userId(), outcome, properties.leaseDuration());
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
            throw new IllegalStateException("user profile backfill was interrupted");
        }
    }

    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("user profile backfill was interrupted");
        }
    }

    private MediaBackfillReference reference(UserProfileBackfillCandidate candidate) {
        return new MediaBackfillReference(
                MediaBackfillTarget.USER_PROFILE, candidate.userId(), candidate.legacyKey());
    }

    private List<UserProfileBackfillCandidate> findBatch(long cursor, long upperBound) {
        return candidateReader.findBatch(
                cursor, upperBound, properties.batchSize());
    }

    private boolean hasMore(long cursor, long upperBound) {
        return !candidateReader.findBatch(cursor, upperBound, 1).isEmpty();
    }

    private UserProfileBackfillSummary completedSummary(Lease lease) {
        Snapshot snapshot = checkpointStore.complete(lease);
        return UserProfileBackfillSummary.fromSnapshot(Status.COMPLETED, snapshot);
    }

    private static final class MutableSummary {

        private final UserProfileBackfillMode mode;
        private long scanned;
        private long inspected;
        private long failed;

        private MutableSummary(
                UserProfileBackfillMode mode
        ) {
            this.mode = mode;
        }

        private UserProfileBackfillSummary finish(Status status) {
            return new UserProfileBackfillSummary(
                    mode, status, scanned, inspected, 0L, 0L, 0L, failed, Map.of());
        }
    }
}
