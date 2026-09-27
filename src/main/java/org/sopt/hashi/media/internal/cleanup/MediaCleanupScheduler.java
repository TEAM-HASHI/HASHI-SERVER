package org.sopt.hashi.media.internal.cleanup;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.sopt.hashi.media.service.MediaCleanupScanService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hashi.media.cleanup", name = "enabled", havingValue = "true")
public class MediaCleanupScheduler {

    private final MediaCleanupScanService scanner;
    private final Executor executor;
    private final MediaCleanupMetrics metrics;

    public MediaCleanupScheduler(MediaCleanupScanService scanner,
                                   @Qualifier(MediaCleanupExecutionConfig.EXECUTOR) Executor executor,
                                   MediaCleanupMetrics metrics) {
        this.scanner = scanner;
        this.executor = executor;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${hashi.media.cleanup.scan-interval:30m}",
            initialDelayString = "${hashi.media.cleanup.scan-interval:30m}")
    public void scheduleCleanup() {
        try {
            // 공용 스케줄러를 막지 않는다. 이미 실행 중이면 적재하지 않고 다음 주기에 다시 시도한다.
            executor.execute(scanner::scan);
        } catch (RejectedExecutionException rejected) {
            metrics.recordDispatchRejected();
        }
    }
}
