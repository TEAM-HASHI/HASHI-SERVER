package org.sopt.hashi.restaurant.migration;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hashi.map.maintenance")
public record LocationMaintenanceProperties(
        Command command, Mode mode, boolean execute, UUID runId, long afterId, Long upperId,
        Integer batchSize, Integer maxBatches, Integer maxRegistrations, Integer maxCalls,
        Duration refreshAhead, Duration purgeAhead, boolean retentionEnabled, Duration pollDelay
) {
    /** #224 validates max-attempts in [1,8], including after application restarts. */
    public static final int CALLS_PER_JOB = 8;

    public LocationMaintenanceProperties {
        command = command == null ? Command.DRY_RUN : command;
        mode = mode == null ? Mode.BACKFILL : mode;
        batchSize = batchSize == null ? 50 : batchSize;
        maxBatches = maxBatches == null ? 1 : maxBatches;
        maxRegistrations = maxRegistrations == null ? 10 : maxRegistrations;
        maxCalls = maxCalls == null ? 80 : maxCalls;
        refreshAhead = refreshAhead == null ? Duration.ofDays(1) : refreshAhead;
        purgeAhead = purgeAhead == null ? Duration.ofHours(1) : purgeAhead;
        pollDelay = pollDelay == null ? Duration.ofMinutes(1) : pollDelay;
        range(batchSize, 1, 100, "batch-size");
        range(maxBatches, 1, 100, "max-batches");
        range(maxRegistrations, 1, 10000, "max-registrations");
        range(maxCalls, 0, 80000, "max-calls");
        if (afterId < 0 || (upperId != null && upperId < afterId)) {
            throw new IllegalArgumentException("Invalid maintenance ID range");
        }
        boolean invalidWindows = pollDelay.compareTo(Duration.ofSeconds(1)) < 0
                || pollDelay.compareTo(Duration.ofHours(1)) > 0
                || purgeAhead.compareTo(pollDelay.multipliedBy(2)) < 0
                || refreshAhead.compareTo(purgeAhead) <= 0
                || refreshAhead.compareTo(Duration.ofDays(30)) > 0;
        if (invalidWindows) {
            throw new IllegalArgumentException("Require 2 * poll-delay <= purge-ahead < refresh-ahead <= 30d");
        }
        if (refreshAhead.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("refresh-ahead must use whole microseconds");
        }
    }

    void requireWriteOptIn() {
        if (!execute) {
            throw new IllegalArgumentException("Mutation requires hashi.map.maintenance.execute=true");
        }
    }

    UUID requiredRunId() {
        if (runId == null) {
            throw new IllegalArgumentException("run-id is required");
        }
        return runId;
    }

    long requiredUpperId() {
        if (upperId == null) {
            throw new IllegalArgumentException("START requires the upper-id reviewed in DRY_RUN");
        }
        return upperId;
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
    }

    public enum Command { DRY_RUN, START, RESUME, STATUS, STOP, PURGE }

    public enum Mode { BACKFILL, REFRESH }
}
