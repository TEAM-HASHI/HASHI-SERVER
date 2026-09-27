package org.sopt.hashi.media.internal.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.media.recovery")
public record MediaRecoveryProperties(
        boolean enabled,
        Duration eprResubmitAge,
        Duration eprResubmitInterval,
        int eprResubmitBatchSize,
        Duration processingStaleAge,
        Duration processingRetryInterval,
        int processingMaxAttempts,
        int scanBatchSize,
        int scanMaxBatches,
        Duration metricsRefreshInterval,
        Duration pendingRetention,
        Duration directUnboundReadyRetention,
        Duration backfillUnboundRetention,
        Duration failedRetention
) {

    private static final Duration DEFAULT_EPR_RESUBMIT_AGE = Duration.ofMinutes(1);
    private static final Duration DEFAULT_EPR_RESUBMIT_INTERVAL = Duration.ofMinutes(1);
    private static final int DEFAULT_EPR_RESUBMIT_BATCH_SIZE = 50;
    private static final Duration DEFAULT_PROCESSING_STALE_AGE = Duration.ofMinutes(10);
    private static final Duration DEFAULT_PROCESSING_RETRY_INTERVAL = Duration.ofMinutes(15);
    private static final int DEFAULT_PROCESSING_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_SCAN_BATCH_SIZE = 100;
    private static final int DEFAULT_SCAN_MAX_BATCHES = 10;
    private static final Duration DEFAULT_METRICS_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_PENDING_RETENTION = Duration.ofHours(24);
    private static final Duration DEFAULT_DIRECT_UNBOUND_READY_RETENTION = Duration.ofHours(24);
    private static final Duration DEFAULT_BACKFILL_UNBOUND_RETENTION = Duration.ofDays(7);
    private static final Duration DEFAULT_FAILED_RETENTION = Duration.ofDays(7);

    public MediaRecoveryProperties {
        eprResubmitAge = defaultDuration(eprResubmitAge, DEFAULT_EPR_RESUBMIT_AGE);
        eprResubmitInterval = defaultDuration(
                eprResubmitInterval, DEFAULT_EPR_RESUBMIT_INTERVAL);
        eprResubmitBatchSize = defaultPositive(
                eprResubmitBatchSize, DEFAULT_EPR_RESUBMIT_BATCH_SIZE);
        processingStaleAge = defaultDuration(
                processingStaleAge, DEFAULT_PROCESSING_STALE_AGE);
        processingRetryInterval = defaultDuration(
                processingRetryInterval, DEFAULT_PROCESSING_RETRY_INTERVAL);
        processingMaxAttempts = defaultPositive(
                processingMaxAttempts, DEFAULT_PROCESSING_MAX_ATTEMPTS);
        scanBatchSize = defaultPositive(scanBatchSize, DEFAULT_SCAN_BATCH_SIZE);
        scanMaxBatches = defaultPositive(scanMaxBatches, DEFAULT_SCAN_MAX_BATCHES);
        metricsRefreshInterval = defaultDuration(
                metricsRefreshInterval, DEFAULT_METRICS_REFRESH_INTERVAL);
        pendingRetention = defaultDuration(pendingRetention, DEFAULT_PENDING_RETENTION);
        directUnboundReadyRetention = defaultDuration(
                directUnboundReadyRetention, DEFAULT_DIRECT_UNBOUND_READY_RETENTION);
        backfillUnboundRetention = defaultDuration(
                backfillUnboundRetention, DEFAULT_BACKFILL_UNBOUND_RETENTION);
        failedRetention = defaultDuration(failedRetention, DEFAULT_FAILED_RETENTION);
    }

    private static Duration defaultDuration(Duration value, Duration defaultValue) {
        Duration resolved = value == null ? defaultValue : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException("media recovery durations must be positive");
        }
        return resolved;
    }

    private static int defaultPositive(int value, int defaultValue) {
        int resolved = value == 0 ? defaultValue : value;
        if (resolved < 1) {
            throw new IllegalArgumentException("media recovery counts must be positive");
        }
        return resolved;
    }
}
