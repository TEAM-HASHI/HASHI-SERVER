package org.sopt.hashi.media.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.sopt.hashi.media.domain.ImageBindingStatus;
import org.sopt.hashi.media.domain.ImageProcessingStatus;
import org.sopt.hashi.media.domain.MediaCreationOrigin;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupMetrics;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupMetrics.Stage;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupProperties;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupStorageException;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupWorkBudget;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCandidate;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCandidateReader;
import org.sopt.hashi.media.internal.cleanup.MediaPurgeCursor;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidate;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateCursor;
import org.sopt.hashi.media.internal.recovery.MediaCleanupCandidateReader;
import org.sopt.hashi.media.service.MediaCleanupScanResult.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "hashi.media.cleanup", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class MediaCleanupScanService {

    private static final Lane[] LANES = Lane.values();

    private final MediaCleanupService cleanup;
    private final MediaCleanupCandidateReader candidates;
    private final MediaPurgeCandidateReader purges;
    private final MediaCleanupEligibilityPolicy policy;
    private final MediaCleanupProperties properties;
    private final MediaCleanupMetrics metrics;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<Lane, MediaCleanupCandidateCursor> cursors = new EnumMap<>(Lane.class);
    private MediaPurgeCursor purgeCursor = MediaPurgeCursor.initial();
    private int nextLane;

    @Autowired
    public MediaCleanupScanService(MediaCleanupService cleanup, MediaCleanupCandidateReader candidates,
                                    MediaPurgeCandidateReader purges, MediaCleanupEligibilityPolicy policy,
                                    MediaCleanupProperties properties, MediaCleanupMetrics metrics,
                                    @Qualifier("japanClock") Clock clock) {
        this(cleanup, candidates, purges, policy, properties, metrics, clock, System::nanoTime);
    }

    MediaCleanupScanService(MediaCleanupService cleanup, MediaCleanupCandidateReader candidates,
                             MediaPurgeCandidateReader purges, MediaCleanupEligibilityPolicy policy,
                             MediaCleanupProperties properties, MediaCleanupMetrics metrics,
                             Clock clock, LongSupplier nanoTime) {
        this.cleanup = cleanup;
        this.candidates = candidates;
        this.purges = purges;
        this.policy = policy;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /** cursor는 실행 간 유지하지만 삭제 안전성은 매번 DB 잠금 안에서 다시 판단한다. */
    public MediaCleanupScanResult scan() {
        if (!properties.enabled()) {
            return skipped(Status.DISABLED);
        }
        if (!running.compareAndSet(false, true)) {
            return skipped(Status.ALREADY_RUNNING);
        }
        Progress progress = new Progress();
        try {
            MediaCleanupWorkBudget budget = new MediaCleanupWorkBudget(properties.scanWorkBudget(), nanoTime);
            try {
                scanBatches(progress, budget, LocalDateTime.now(clock));
            } catch (RuntimeException failure) {
                progress.failed++;
                progress.status = isInterrupted(failure) ? Status.INTERRUPTED : Status.FAILED;
                metrics.recordFailure(properties.mode(), Stage.SCAN, failure);
            }
            MediaCleanupScanResult result = progress.result();
            metrics.recordRun(properties.mode(), result, budget.elapsed());
            return result;
        } finally {
            running.set(false);
        }
    }

    private void scanBatches(Progress progress, MediaCleanupWorkBudget budget, LocalDateTime now) {
        Set<Lane> exhausted = EnumSet.noneOf(Lane.class);
        while (exhausted.size() < LANES.length) {
            if (stopRequested(progress, budget)) {
                return;
            }
            if (progress.batches >= properties.scanMaxBatches()) {
                progress.status = Status.WORK_LIMIT_REACHED;
                return;
            }
            Lane lane = LANES[nextLane];
            nextLane = (nextLane + 1) % LANES.length;
            if (exhausted.contains(lane)) {
                continue;
            }
            List<ScanItem> batch;
            try {
                batch = readBatch(lane, now);
                if (batch.size() > properties.scanBatchSize()) {
                    throw new IllegalStateException("cleanup reader exceeded the batch limit");
                }
            } catch (RuntimeException failure) {
                progress.failed++;
                metrics.recordFailure(properties.mode(), Stage.SCAN, failure);
                if (isInterrupted(failure)) {
                    progress.status = Status.INTERRUPTED;
                    return;
                }
                exhausted.add(lane);
                continue;
            }
            if (!batch.isEmpty()) {
                progress.batches++;
            }
            for (ScanItem item : batch) {
                if (stopRequested(progress, budget)) {
                    return;
                }
                attempt(item, lane, progress);
                if (progress.status == Status.INTERRUPTED) {
                    return;
                }
            }
            if (batch.size() < properties.scanBatchSize()) {
                resetCursor(lane);
                exhausted.add(lane);
            }
        }
    }

    private List<ScanItem> readBatch(Lane lane, LocalDateTime now) {
        if (lane == Lane.RESUME) {
            return purges.findBatch(now.minus(properties.retryInterval()), purgeCursor, properties.scanBatchSize())
                    .stream().map(candidate -> new ScanItem(null, candidate)).toList();
        }
        MediaCleanupCandidateCursor cursor = cursors.getOrDefault(lane, MediaCleanupCandidateCursor.initial());
        LocalDateTime cutoff = now.minus(policy.retentionFor(lane.state, lane.origin, lane.binding));
        List<MediaCleanupCandidate> batch = lane.binding == ImageBindingStatus.BOUND
                ? candidates.findFailedBoundBatch(lane.origin, cutoff, cursor, properties.scanBatchSize())
                : candidates.findUnboundBatch(lane.origin, List.of(lane.state), cutoff,
                        cursor, properties.scanBatchSize());
        return batch.stream().map(candidate -> new ScanItem(candidate, null)).toList();
    }

    private void attempt(ScanItem item, Lane lane, Progress progress) {
        Stage stage = lane == Lane.RESUME ? Stage.RESUME : Stage.INITIAL;
        progress.attempted++;
        try {
            MediaCleanupOutcome outcome = lane == Lane.RESUME
                    ? cleanup.resume(item.purge.work()) : cleanup.clean(item.candidate);
            metrics.recordAttempt(properties.mode(), stage, outcome);
            progress.hasIncomplete |= outcome == MediaCleanupOutcome.INCOMPLETE;
        } catch (RuntimeException failure) {
            progress.failed++;
            metrics.recordFailure(properties.mode(), stage, failure);
            if (isInterrupted(failure)) {
                progress.status = Status.INTERRUPTED;
            }
        } finally {
            // 읽어 둔 마지막 행이 아니라 실제 시도한 행까지만 전진한다. 실패 행은 다음 순회에서 재시도한다.
            if (lane == Lane.RESUME) {
                purgeCursor = item.purge.nextCursor();
            } else {
                cursors.put(lane, item.candidate.nextCursor());
            }
        }
    }

    private void resetCursor(Lane lane) {
        if (lane == Lane.RESUME) {
            purgeCursor = MediaPurgeCursor.initial();
        } else {
            cursors.remove(lane);
        }
    }

    private boolean stopRequested(Progress progress, MediaCleanupWorkBudget budget) {
        if (Thread.currentThread().isInterrupted()) {
            progress.status = Status.INTERRUPTED;
            return true;
        }
        if (!budget.hasTimeLeft()) {
            progress.status = Status.TIME_LIMIT_REACHED;
            return true;
        }
        return false;
    }

    private boolean isInterrupted(RuntimeException failure) {
        if (failure instanceof MediaCleanupStorageException storage
                && storage.getReason() == MediaCleanupStorageException.Reason.INTERRUPTED) {
            Thread.currentThread().interrupt();
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            cause = cause.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private MediaCleanupScanResult skipped(Status status) {
        MediaCleanupScanResult result = new MediaCleanupScanResult(status, 0, 0, 0);
        metrics.recordRun(properties.mode(), result, Duration.ZERO);
        return result;
    }

    private record ScanItem(MediaCleanupCandidate candidate, MediaPurgeCandidate purge) {
        @Override
        public String toString() {
            return "ScanItem[redacted]";
        }
    }

    private static final class Progress {
        private Status status = Status.COMPLETED;
        private int attempted;
        private int batches;
        private int failed;
        private boolean hasIncomplete;

        private MediaCleanupScanResult result() {
            if (status == Status.COMPLETED) {
                status = failed > 0 ? Status.PARTIAL_FAILURE : hasIncomplete ? Status.RETRY_PENDING : Status.COMPLETED;
            }
            return new MediaCleanupScanResult(status, attempted, batches, failed);
        }
    }

    private enum Lane {
        RESUME(null, null, null),
        DIRECT_PENDING(MediaCreationOrigin.DIRECT_UPLOAD,
                ImageProcessingStatus.PENDING_UPLOAD, ImageBindingStatus.UNBOUND),
        DIRECT_EXPIRED(MediaCreationOrigin.DIRECT_UPLOAD, ImageProcessingStatus.EXPIRED, ImageBindingStatus.UNBOUND),
        DIRECT_READY(MediaCreationOrigin.DIRECT_UPLOAD, ImageProcessingStatus.READY, ImageBindingStatus.UNBOUND),
        DIRECT_FAILED(MediaCreationOrigin.DIRECT_UPLOAD, ImageProcessingStatus.FAILED, ImageBindingStatus.UNBOUND),
        BACKFILL_PENDING(MediaCreationOrigin.SYSTEM_BACKFILL,
                ImageProcessingStatus.PENDING_UPLOAD, ImageBindingStatus.UNBOUND),
        BACKFILL_EXPIRED(MediaCreationOrigin.SYSTEM_BACKFILL,
                ImageProcessingStatus.EXPIRED, ImageBindingStatus.UNBOUND),
        BACKFILL_READY(MediaCreationOrigin.SYSTEM_BACKFILL, ImageProcessingStatus.READY, ImageBindingStatus.UNBOUND),
        BACKFILL_FAILED(MediaCreationOrigin.SYSTEM_BACKFILL, ImageProcessingStatus.FAILED, ImageBindingStatus.UNBOUND),
        DIRECT_BOUND_FAILED(MediaCreationOrigin.DIRECT_UPLOAD, ImageProcessingStatus.FAILED, ImageBindingStatus.BOUND),
        BACKFILL_BOUND_FAILED(MediaCreationOrigin.SYSTEM_BACKFILL,
                ImageProcessingStatus.FAILED, ImageBindingStatus.BOUND);

        private final MediaCreationOrigin origin;
        private final ImageProcessingStatus state;
        private final ImageBindingStatus binding;

        Lane(MediaCreationOrigin origin, ImageProcessingStatus state, ImageBindingStatus binding) {
            this.origin = origin;
            this.state = state;
            this.binding = binding;
        }
    }
}
