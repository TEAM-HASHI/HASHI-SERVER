package org.sopt.hashi.media.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.sopt.hashi.media.internal.cleanup.MediaCleanupWorkBudget;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectLocation;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersion;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersionCursor;
import org.sopt.hashi.media.internal.reconciliation.MediaObjectVersionPage;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationMetrics;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationProperties;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationProperties.Mode;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationStorage;
import org.sopt.hashi.media.internal.reconciliation.MediaReconciliationStorageException;
import org.sopt.hashi.media.service.MediaReconciliationScanResult.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "hashi.media.reconciliation", name = "enabled", havingValue = "true")
@Transactional(propagation = Propagation.NEVER)
public class MediaReconciliationScanService {

    private static final MediaObjectLocation[] LOCATIONS = MediaObjectLocation.values();

    private final MediaReconciliationTransactionService transactions;
    private final MediaReconciliationStorage storage;
    private final MediaReconciliationProperties properties;
    private final MediaReconciliationMetrics metrics;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<MediaObjectLocation, MediaObjectVersionCursor> cursors =
            new EnumMap<>(MediaObjectLocation.class);
    private int nextLocation;

    @Autowired
    public MediaReconciliationScanService(
            MediaReconciliationTransactionService transactions,
            MediaReconciliationStorage storage,
            MediaReconciliationProperties properties,
            MediaReconciliationMetrics metrics,
            @Qualifier("japanClock") Clock clock
    ) {
        this(transactions, storage, properties, metrics, clock, System::nanoTime);
    }

    MediaReconciliationScanService(
            MediaReconciliationTransactionService transactions,
            MediaReconciliationStorage storage,
            MediaReconciliationProperties properties,
            MediaReconciliationMetrics metrics,
            Clock clock,
            LongSupplier nanoTime
    ) {
        this.transactions = transactions;
        this.storage = storage;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    public MediaReconciliationScanResult scan() {
        if (!properties.enabled()) {
            return skipped(Status.DISABLED);
        }
        if (!running.compareAndSet(false, true)) {
            return skipped(Status.ALREADY_RUNNING);
        }
        Progress progress = new Progress();
        MediaCleanupWorkBudget budget = new MediaCleanupWorkBudget(properties.scanWorkBudget(), nanoTime);
        try {
            scanPages(progress, budget, clock.instant().minus(properties.orphanRetention()));
            MediaReconciliationScanResult result = progress.result();
            metrics.recordRun(properties.mode(), result, budget.elapsed());
            return result;
        } finally {
            running.set(false);
        }
    }

    private void scanPages(Progress progress, MediaCleanupWorkBudget budget, Instant eligibleBefore) {
        Set<MediaObjectLocation> exhausted = EnumSet.noneOf(MediaObjectLocation.class);
        while (exhausted.size() < LOCATIONS.length) {
            if (stopRequested(progress, budget)) {
                return;
            }
            if (progress.pages >= properties.scanMaxPages()) {
                progress.status = Status.PAGE_LIMIT_REACHED;
                return;
            }
            MediaObjectLocation location = LOCATIONS[nextLocation];
            nextLocation = (nextLocation + 1) % LOCATIONS.length;
            if (exhausted.contains(location)) {
                continue;
            }

            MediaObjectVersionCursor cursor = cursors.getOrDefault(location, MediaObjectVersionCursor.initial());
            MediaObjectVersionPage page;
            try {
                page = storage.listObjectVersions(location, cursor, properties.scanPageSize());
                progress.pages++;
            } catch (RuntimeException failure) {
                progress.fail(location, failure, metrics);
                if (isInterrupted(failure)) {
                    progress.status = Status.INTERRUPTED;
                    return;
                }
                exhausted.add(location);
                continue;
            }

            boolean completedPage = processPage(page, location, eligibleBefore, progress, budget);
            if (!completedPage) {
                return;
            }
            if (page.hasNext()) {
                cursors.put(location, page.nextCursor());
            } else {
                cursors.remove(location);
                exhausted.add(location);
            }
        }
    }

    private boolean processPage(MediaObjectVersionPage page, MediaObjectLocation location,
                                Instant eligibleBefore, Progress progress, MediaCleanupWorkBudget budget) {
        for (MediaObjectVersion object : page.objects()) {
            if (stopRequested(progress, budget)) {
                return false;
            }
            progress.inspected++;
            try {
                MediaReconciliationDecision decision = transactions.assess(object, eligibleBefore);
                metrics.recordDecision(location, properties.mode(), decision);
                switch (decision) {
                    case PROTECT -> progress.protectedObjects++;
                    case UNKNOWN -> progress.unknown++;
                    case DELETE -> deleteOrReport(object, progress);
                }
            } catch (RuntimeException failure) {
                progress.fail(location, failure, metrics);
                if (isInterrupted(failure)) {
                    progress.status = Status.INTERRUPTED;
                    return false;
                }
            }
        }
        return true;
    }

    private void deleteOrReport(MediaObjectVersion object, Progress progress) {
        if (properties.mode() == Mode.DRY_RUN) {
            progress.wouldDelete++;
            return;
        }
        // assess의 row lock transaction이 끝난 뒤에만 exact key+version 삭제를 호출한다.
        storage.deleteObjectVersion(object);
        progress.deleted++;
        metrics.recordDeleted(object.location());
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
        if (failure instanceof MediaReconciliationStorageException storageFailure
                && storageFailure.getReason() == MediaReconciliationStorageException.Reason.INTERRUPTED) {
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

    private MediaReconciliationScanResult skipped(Status status) {
        MediaReconciliationScanResult result = new MediaReconciliationScanResult(
                status, 0, 0, 0, 0, 0, 0, 0);
        metrics.recordRun(properties.mode(), result, java.time.Duration.ZERO);
        return result;
    }

    private static final class Progress {
        private Status status = Status.COMPLETED;
        private int pages;
        private int inspected;
        private int wouldDelete;
        private int deleted;
        private int protectedObjects;
        private int unknown;
        private int failed;

        private void fail(MediaObjectLocation location, RuntimeException failure,
                          MediaReconciliationMetrics metrics) {
            failed++;
            metrics.recordFailure(location, failure);
        }

        private MediaReconciliationScanResult result() {
            if (status == Status.COMPLETED && failed > 0) {
                status = Status.PARTIAL_FAILURE;
            }
            return new MediaReconciliationScanResult(status, pages, inspected, wouldDelete,
                    deleted, protectedObjects, unknown, failed);
        }
    }
}
