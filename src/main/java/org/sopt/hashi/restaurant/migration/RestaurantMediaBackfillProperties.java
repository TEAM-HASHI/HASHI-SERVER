package org.sopt.hashi.restaurant.migration;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.restaurant.media-backfill")
public record RestaurantMediaBackfillProperties(
        boolean enabled,
        String runId,
        RestaurantMediaBackfillTarget target,
        RestaurantMediaBackfillMode mode,
        int batchSize,
        int maxBatches,
        Duration leaseDuration,
        int maxAttempts,
        Duration retryInitialDelay
) {

    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_BATCH_COUNT = 1_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration MAX_LEASE_DURATION = Duration.ofMinutes(30);
    private static final Duration MAX_RETRY_INITIAL_DELAY = Duration.ofSeconds(10);

    public RestaurantMediaBackfillProperties {
        Objects.requireNonNull(target, "restaurant media backfill target is required");
        Objects.requireNonNull(mode, "restaurant media backfill mode is required");
        Objects.requireNonNull(leaseDuration, "restaurant media backfill lease duration is required");
        Objects.requireNonNull(retryInitialDelay, "restaurant media backfill retry delay is required");
        requireRange(batchSize, 1, MAX_BATCH_SIZE, "batchSize");
        requireRange(maxBatches, 1, MAX_BATCH_COUNT, "maxBatches");
        requireRange(maxAttempts, 1, MAX_ATTEMPTS, "maxAttempts");
        if (leaseDuration.compareTo(MIN_LEASE_DURATION) < 0
                || leaseDuration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw new IllegalArgumentException("leaseDuration must be between 30 seconds and 30 minutes");
        }
        if (retryInitialDelay.isNegative()
                || retryInitialDelay.compareTo(MAX_RETRY_INITIAL_DELAY) > 0) {
            throw new IllegalArgumentException("retryInitialDelay must be between 0 and 10 seconds");
        }
        long retryMultiplier = (1L << (maxAttempts - 1)) - 1L;
        if (retryInitialDelay.multipliedBy(retryMultiplier).compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("retry backoff budget must be shorter than the lease duration");
        }
        if (enabled && mode.usesCheckpoint()) {
            parseRunId(runId);
        }
    }

    UUID requiredRunId() {
        if (!mode.usesCheckpoint()) {
            throw new IllegalStateException("dry-run does not use a persistent run id");
        }
        return parseRunId(runId);
    }

    private static UUID parseRunId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runId is required for PREPARE and ATTACH");
        }
        try {
            UUID runId = UUID.fromString(value);
            if (!runId.toString().equals(value)) {
                throw new IllegalArgumentException("runId must be a lowercase canonical UUID");
            }
            return runId;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("runId must be a lowercase canonical UUID", exception);
        }
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("%s must be between %d and %d".formatted(name, min, max));
        }
    }
}
