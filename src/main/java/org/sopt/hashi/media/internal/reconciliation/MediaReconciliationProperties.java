package org.sopt.hashi.media.internal.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.media.reconciliation")
public record MediaReconciliationProperties(
        boolean enabled,
        Mode mode,
        Duration orphanRetention,
        Duration scanInterval,
        int scanPageSize,
        int scanMaxPages,
        Duration scanWorkBudget,
        Duration storageApiTimeout,
        Duration storageAttemptTimeout,
        Duration shutdownAwait
) {

    private static final Duration MINIMUM_RETENTION = Duration.ofDays(7);

    public MediaReconciliationProperties {
        mode = mode == null ? Mode.DRY_RUN : mode;
        orphanRetention = positiveDuration(orphanRetention, MINIMUM_RETENTION);
        if (orphanRetention.compareTo(MINIMUM_RETENTION) < 0) {
            throw new IllegalArgumentException("media reconciliation retention must be at least seven days");
        }
        scanInterval = positiveDuration(scanInterval, Duration.ofHours(6));
        scanPageSize = boundedCount(scanPageSize, 100, 1000);
        scanMaxPages = boundedCount(scanMaxPages, 2, 100);
        scanWorkBudget = boundedDuration(scanWorkBudget, Duration.ofMinutes(2), Duration.ofMinutes(30));
        storageApiTimeout = positiveDuration(storageApiTimeout, Duration.ofSeconds(15));
        storageAttemptTimeout = positiveDuration(storageAttemptTimeout, Duration.ofSeconds(5));
        if (storageAttemptTimeout.compareTo(storageApiTimeout) > 0) {
            throw new IllegalArgumentException("reconciliation attempt timeout must not exceed the API timeout");
        }
        shutdownAwait = boundedDuration(shutdownAwait, Duration.ofSeconds(20), Duration.ofMinutes(1));
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
            throw new IllegalArgumentException("media reconciliation durations must be positive");
        }
        return resolved;
    }

    private static int boundedCount(int value, int defaultValue, int maximum) {
        int resolved = value == 0 ? defaultValue : value;
        if (resolved < 1 || resolved > maximum) {
            throw new IllegalArgumentException("media reconciliation count is outside the supported range");
        }
        return resolved;
    }

    private static Duration boundedDuration(Duration value, Duration defaultValue, Duration maximum) {
        Duration resolved = positiveDuration(value, defaultValue);
        if (resolved.compareTo(maximum) > 0 || resolved.toMillis() < 1) {
            throw new IllegalArgumentException("media reconciliation duration is outside the supported range");
        }
        return resolved;
    }
}
