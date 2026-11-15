package org.sopt.hashi.restaurant.internal.map;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.map.maintenance")
public record LocationRetentionProperties(
        Integer batchSize, Integer maxBatches, Duration refreshAhead, Duration purgeAhead,
        boolean retentionEnabled, Duration pollDelay
) {
    public LocationRetentionProperties {
        batchSize = batchSize == null ? 50 : batchSize;
        maxBatches = maxBatches == null ? 1 : maxBatches;
        refreshAhead = refreshAhead == null ? Duration.ofDays(3) : refreshAhead;
        purgeAhead = purgeAhead == null ? Duration.ZERO : purgeAhead;
        pollDelay = pollDelay == null ? Duration.ofMinutes(1) : pollDelay;
        range(batchSize, 1, 100, "batch-size");
        range(maxBatches, 1, 100, "max-batches");
        boolean invalidWindows = pollDelay.compareTo(Duration.ofSeconds(1)) < 0
                || pollDelay.compareTo(Duration.ofHours(1)) > 0
                || purgeAhead.isNegative()
                || refreshAhead.compareTo(purgeAhead) <= 0
                || refreshAhead.compareTo(Duration.ofDays(30)) > 0;
        if (invalidWindows) {
            throw new IllegalArgumentException("Require 0 <= purge-ahead < refresh-ahead <= 30d");
        }
        if (refreshAhead.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("refresh-ahead must use whole microseconds");
        }
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
    }
}
