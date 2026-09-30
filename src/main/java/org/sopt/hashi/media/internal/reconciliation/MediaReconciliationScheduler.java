package org.sopt.hashi.media.internal.reconciliation;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.sopt.hashi.media.service.MediaReconciliationScanService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hashi.media.reconciliation", name = "enabled", havingValue = "true")
public class MediaReconciliationScheduler {

    private final MediaReconciliationScanService scanner;
    private final Executor executor;
    private final MediaReconciliationMetrics metrics;

    public MediaReconciliationScheduler(
            MediaReconciliationScanService scanner,
            @Qualifier(MediaReconciliationExecutionConfig.EXECUTOR) Executor executor,
            MediaReconciliationMetrics metrics
    ) {
        this.scanner = scanner;
        this.executor = executor;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${hashi.media.reconciliation.scan-interval:6h}",
            initialDelayString = "${hashi.media.reconciliation.scan-interval:6h}")
    public void scheduleReconciliation() {
        try {
            executor.execute(scanner::scan);
        } catch (RejectedExecutionException rejected) {
            metrics.recordDispatchRejected();
        }
    }
}
