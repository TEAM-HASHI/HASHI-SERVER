package org.sopt.hashi.media.internal.recovery;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.internal.metrics.MediaPipelineMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hashi.media.queue", name = "enabled", havingValue = "true")
public class MediaProcessingRecoveryScheduler {

    private final MediaRecoveryProperties properties;
    private final MediaProcessingRecoveryReader reader;
    private final MediaProcessingRecoveryTransactionService transactionService;
    private final MediaPipelineMetrics metrics;
    private final Clock clock;

    public MediaProcessingRecoveryScheduler(
            MediaRecoveryProperties properties,
            MediaProcessingRecoveryReader reader,
            MediaProcessingRecoveryTransactionService transactionService,
            MediaPipelineMetrics metrics,
            @Qualifier("japanClock") Clock clock) {
        this.properties = properties;
        this.reader = reader;
        this.transactionService = transactionService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${hashi.media.recovery.processing-retry-interval:15m}",
            initialDelayString = "${hashi.media.recovery.processing-stale-age:10m}"
    )
    public void scheduleRecovery() {
        recoverStalledProcessing();
    }

    public void recoverOnStartup() {
        recoverStalledProcessing();
    }

    int recoverStalledProcessing() {
        if (!properties.enabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime staleBefore = now.minus(properties.processingStaleAge());
        LocalDateTime retryBefore = now.minus(properties.processingRetryInterval());
        MediaProcessingRecoveryCursor cursor = MediaProcessingRecoveryCursor.initial();
        int requestedCount = 0;

        for (int batch = 0; batch < properties.scanMaxBatches(); batch++) {
            List<MediaProcessingRecoveryCandidate> candidates = reader.findBatch(
                    staleBefore,
                    retryBefore,
                    properties.processingMaxAttempts(),
                    cursor,
                    properties.scanBatchSize()
            );
            if (candidates.isEmpty()) {
                break;
            }
            for (MediaProcessingRecoveryCandidate candidate : candidates) {
                if (transactionService.requestRetryIfStillStalled(
                        candidate,
                        properties.processingStaleAge(),
                        properties.processingRetryInterval(),
                        properties.processingMaxAttempts())) {
                    requestedCount++;
                    metrics.recordRecovery("requested");
                } else {
                    metrics.recordRecovery("stale");
                }
            }
            cursor = candidates.getLast().nextCursor();
            if (candidates.size() < properties.scanBatchSize()) {
                break;
            }
        }
        return requestedCount;
    }
}
