package org.sopt.hashi.media.internal.cleanup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.media.cleanup")
public record MediaCleanupProperties(
        boolean enabled,
        Mode mode,
        Duration uploadSafetyWindow,
        Duration retryInterval,
        Duration scanInterval,
        int scanBatchSize,
        int scanMaxBatches,
        int storagePageSize,
        int storageMaxPages,
        Duration storageApiTimeout,
        Duration storageAttemptTimeout
) {

    public MediaCleanupProperties {
        mode = mode == null ? Mode.DRY_RUN : mode;
        if (enabled && uploadSafetyWindow == null) {
            throw new IllegalArgumentException("media cleanup requires an explicit upload safety window");
        }
        if (uploadSafetyWindow != null && (uploadSafetyWindow.isZero() || uploadSafetyWindow.isNegative())) {
            throw new IllegalArgumentException("media cleanup upload safety window must be positive");
        }
        retryInterval = positiveDuration(retryInterval, Duration.ofMinutes(15));
        scanInterval = positiveDuration(scanInterval, Duration.ofMinutes(30));
        scanBatchSize = boundedCount(scanBatchSize, 25, 1000);
        scanMaxBatches = boundedCount(scanMaxBatches, 2, 100);
        storagePageSize = boundedCount(storagePageSize, 1000, 1000);
        storageMaxPages = boundedCount(storageMaxPages, 10, 100);
        storageApiTimeout = positiveDuration(storageApiTimeout, Duration.ofSeconds(15));
        storageAttemptTimeout = positiveDuration(storageAttemptTimeout, Duration.ofSeconds(5));
        if (storageAttemptTimeout.compareTo(storageApiTimeout) > 0) {
            throw new IllegalArgumentException("cleanup attempt timeout must not exceed the API timeout");
        }
    }

    public boolean canDelete() {
        return enabled && mode == Mode.DELETE;
    }

    public enum Mode {
        DRY_RUN,
        DELETE
    }

    private static Duration positiveDuration(Duration value, Duration defaultValue) {
        Duration resolved = value == null ? defaultValue : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException("media cleanup durations must be positive");
        }
        return resolved;
    }

    private static int boundedCount(int value, int defaultValue, int maximum) {
        int resolved = value == 0 ? defaultValue : value;
        if (resolved < 1 || resolved > maximum) {
            throw new IllegalArgumentException("media cleanup count is outside the supported range");
        }
        return resolved;
    }
}
