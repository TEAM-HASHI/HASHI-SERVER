package org.sopt.hashi.user.migration;

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
import org.sopt.hashi.media.MediaBackfillTarget;
import org.sopt.hashi.shared.migration.BoundedKeysetLoop;
import org.sopt.hashi.shared.migration.BoundedRetry;
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
            logSummary(summary);
        } catch (RuntimeException exception) {
            log.error(
                    "User profile backfill could not start: mode={}, errorType={}",
                    properties.mode(), failureType(exception)
            );
        }
    }

    private void logSummary(UserProfileBackfillSummary summary) {
        if (summary.mode() == UserProfileBackfillMode.DRY_RUN) {
            log.info(
                    "User profile backfill finished: mode={}, status={}, scanned={}, inspected={}, "
                            + "failed={}, sourceFailuresThisExecution={}",
                    summary.mode(), summary.status(), summary.scannedCount(),
                    summary.inspectedCount(), summary.failedCount(), summary.sourceFailuresThisExecution()
            );
            return;
        }
        log.info(
                "User profile backfill finished: mode={}, status={}, "
                        + "scanned={}, prepared={}, attached={}, skipped={}, failed={}, "
                        + "sourceFailuresThisExecution={}",
                summary.mode(), summary.status(), summary.scannedCount(), summary.preparedCount(),
                summary.attachedCount(), summary.skippedCount(), summary.failedCount(),
                summary.sourceFailuresThisExecution()
        );
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
                    properties.mode(), failureType(exception),
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
        BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                0L, upperBound, properties.batchSize(), properties.maxBatches(),
                candidateReader::findBatch, UserProfileBackfillCandidate::userId,
                candidate -> inspectAndCount(candidate, summary, sourceFailures));
        return summary.finish(result == BoundedKeysetLoop.Result.EXHAUSTED ? Status.COMPLETED : Status.PAUSED);
    }

    private void inspectAndCount(UserProfileBackfillCandidate candidate, MutableSummary summary,
            UserProfileBackfillSourceFailures sourceFailures) {
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
        try {
            BoundedKeysetLoop.Result result = BoundedKeysetLoop.run(
                    acquisition.snapshot().cursorId(), lease.upperBoundId(),
                    properties.batchSize(), properties.maxBatches(),
                    candidateReader::findBatch, UserProfileBackfillCandidate::userId,
                    candidate -> processAndRecord(candidate, lease, sourceFailures));
            if (result == BoundedKeysetLoop.Result.EXHAUSTED) {
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
                    properties.mode(), failureType(exception)
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
            if (exception.getReason() == Reason.STORAGE_UNAVAILABLE) {
                sourceFailures.record(exception.getReason());
                throw exception;
            }
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

    private MediaBackfillReference reference(UserProfileBackfillCandidate candidate) {
        return new MediaBackfillReference(
                MediaBackfillTarget.USER_PROFILE, candidate.userId(), candidate.legacyKey());
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
